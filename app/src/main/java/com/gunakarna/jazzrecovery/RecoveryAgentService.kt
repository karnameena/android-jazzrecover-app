package com.gunakarna.jazzrecovery
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap

import android.app.*
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.location.LocationManager
import android.Manifest
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owner-enabled recovery agent. The ongoing notification is intentional and visible.
 * Camera access is attempted only after the owner grants CAMERA permission and starts
 * this foreground service while the app is visible. Android/OEM policy may still block capture.
 */
class RecoveryAgentService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val prefs by lazy { getSharedPreferences("jazz_recovery_standalone", Context.MODE_PRIVATE) }
    private val channelId = "jazz_recovery_ready"
    private val maxPhotoBytes = 5 * 1024 * 1024
    private val jpegLongEdge = 2560
    private val jpegQuality = 90
    private var bluetoothHelper: BluetoothHelperReporting? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (bluetoothHelper == null) bluetoothHelper = BluetoothHelperReporting(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jazz Recovery is ready")
            .setContentText("Owner-enabled recovery and camera readiness are active")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val hasForegroundLocation =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBackgroundLocation = Build.VERSION.SDK_INT < 29 ||
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                (if (hasForegroundLocation && hasBackgroundLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(42, notification, types)
        } else {
            startForeground(42, notification)
        }
        prefs.edit().putBoolean("readiness_service_active", true)
            .putString("readiness_state", "ACTIVE")
            .putLong("readiness_service_started_at", System.currentTimeMillis()).apply()
        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.name = "JazzRecoveryAgent"; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        prefs.edit().putBoolean("readiness_service_active", false)
            .putLong("readiness_service_heartbeat_at", 0L)
            .putString("readiness_state", if (prefs.getBoolean("readiness_enabled", false)) "RESTORE_PENDING" else "OFF").apply()
        bluetoothHelper?.stop()
        bluetoothHelper = null
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "Recovery readiness", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun loop() {
        var lastHeartbeat = 0L
        var lastUnlockSettingSync = 0L
        while (running && !Thread.currentThread().isInterrupted) {
            if (System.currentTimeMillis() - prefs.getLong("readiness_service_heartbeat_at", 0L) >= 15_000L) {
                prefs.edit().putLong("readiness_service_heartbeat_at", System.currentTimeMillis()).apply()
            }
            try {
                val base = (prefs.getString("server", "") ?: "").trimEnd('/')
                val deviceId = prefs.getString("device_id", "") ?: ""
                val token = runCatching { decrypt(prefs.getString("secret", "") ?: "") }.getOrNull().orEmpty()
                val hasCredentials = base.startsWith("https://") && deviceId.isNotBlank() && token.length >= 32
                if (hasCredentials) {
                    if (System.currentTimeMillis() - lastUnlockSettingSync >= 10_000L) {
                        runCatching { syncUnlockCaptureSetting(base, token, deviceId) }
                            .onFailure { Log.w("JazzUnlockCapture", "Setting sync deferred; retaining last known owner setting", it) }
                        lastUnlockSettingSync = System.currentTimeMillis()
                    }
                    // Sync OFF before processing a queued trigger when connectivity has returned.
                    processPendingUnlockCapture(base, token, deviceId)
                    bluetoothHelper?.tick(base, deviceId, token, prefs.getBoolean("lost_mode", false))
                    if (System.currentTimeMillis() - lastHeartbeat >= 30_000) {
                        post(base, "/android/device/heartbeat", token, deviceId, JSONObject()
                            .put("deviceId", deviceId)
                            .put("mode", if (prefs.getBoolean("lost_mode", false)) "LOST_MODE" else "NORMAL_MODE")
                            .put("status", JSONObject()
                                .put("battery", (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                                .put("charging", false)
                                .put("network", "CONNECTED")
                                .put("locationServices", locationState())))
                        lastHeartbeat = System.currentTimeMillis()
                    }
                    val response = request("GET", base + "/android/device/commands/next", token, deviceId, null)
                    val command = response?.optJSONObject("command")
                    if (command != null) handleCommand(base, token, deviceId, command)
                } else {
                    // Preserve offline capture capability using the last synchronized owner setting.
                    processPendingUnlockCapture(base, token, deviceId)
                }
            } catch (_: Exception) { /* Retry on next short poll; do not crash the foreground service. */ }
            try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
        }
    }

    private fun handleCommand(base: String, token: String, deviceId: String, command: JSONObject) {
        val id = command.optString("id")
        val type = command.optString("type")
        val args = command.optJSONObject("args") ?: JSONObject()
        val result = try {
            when (type) {
                "device_status" -> JSONObject().put("ok", true).put("mode", if (prefs.getBoolean("lost_mode", false)) "LOST_MODE" else "NORMAL_MODE")
                    .put("status", JSONObject().put("battery", (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                        .put("network", "CONNECTED").put("locationServices", locationState()))
                "device_location" -> getLocation()
                "recovery_photo" -> capturePhoto(if (args.optString("camera") == "rear") "rear" else "front")
                "ring_device" -> ring(args.optLong("durationMs", 10000L))
                "set_recovery_mode" -> {
                    val enabled = args.optBoolean("enabled", true)
                    prefs.edit().putBoolean("lost_mode", enabled).apply()
                    JSONObject().put("ok", true).put("mode", if (enabled) "LOST_MODE" else "NORMAL_MODE")
                }
                "location_status" -> JSONObject().put("ok", true).put("locationServices", locationState()).put("timestamp", System.currentTimeMillis())
                "location_enable" -> GlobalLocationController.setEnabled(this, true).put("timestamp", System.currentTimeMillis())
                "location_disable" -> GlobalLocationController.setEnabled(this, false).put("timestamp", System.currentTimeMillis())
                else -> JSONObject().put("ok", false).put("error", "UNSUPPORTED_COMMAND")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.javaClass.simpleName)
        }
        post(base, "/android/device/commands/$id/result", token, deviceId, JSONObject().put("result", result))
    }

    /** Pull the owner's authoritative setting. Network failure deliberately preserves the last saved value. */
    private fun syncUnlockCaptureSetting(base: String, token: String, deviceId: String) {
        val response = request("GET", base + "/android/device/unlock-capture-setting", token, deviceId, null)
            ?: return
        if (response.optBoolean("ok")) {
            val enabled = response.optBoolean("enabled", false)
            val wasEnabled = prefs.getBoolean("unlock_capture_enabled", false)
            val editor = prefs.edit().putBoolean("unlock_capture_enabled", enabled)
                .putInt("unlock_capture_trigger_attempts", 2)
                .putLong("unlock_capture_setting_synced_at", System.currentTimeMillis())
            if (enabled && !wasEnabled) {
                val policy = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val baseline = runCatching { policy.currentFailedPasswordAttempts }.getOrDefault(0)
                editor.putInt("unlock_capture_platform_baseline", baseline)
                    .putInt("unlock_capture_local_count", 0)
                    .putBoolean("unlock_capture_triggered", false)
                Log.i("JazzUnlockCapture", "Enabled with current platform failed-attempt count as baseline=$baseline")
            }
            if (!enabled) {
                editor.putInt("unlock_capture_local_count", 0)
                    .putBoolean("unlock_capture_triggered", false)
                    .putBoolean("unlock_capture_pending", false)
                    .putLong("unlock_capture_pending_at", 0L)
            }
            editor.apply()
            Log.i("JazzUnlockCapture", "Owner setting synchronized: enabled=$enabled threshold=2")
        }
    }

    /**
     * The Device Admin callback only queues an event. Camera work is performed by this
     * already-running, user-started camera foreground service. Android 12+ does not
     * allow us to start a camera FGS freely from the background.
     */
    private fun processPendingUnlockCapture(base: String, token: String, deviceId: String) {
        val photoFile = File(filesDir, "unlock-capture-pending.jpg")
        if (photoFile.exists()) {
            uploadPendingUnlockCapture(base, token, deviceId, photoFile)
            return
        }
        if (!prefs.getBoolean("unlock_capture_pending", false)) return
        if (!prefs.getBoolean("unlock_capture_enabled", false)) {
            clearPendingUnlockTrigger("SETTING_OFF")
            return
        }

        val now = System.currentTimeMillis()
        val requestedAt = prefs.getLong("unlock_capture_pending_at", 0L)
        if (requestedAt <= 0L || now - requestedAt > 15_000L) {
            clearPendingUnlockTrigger("CAPTURE_WINDOW_EXPIRED")
            return
        }
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardLocked) {
            clearPendingUnlockTrigger("DEVICE_UNLOCKED_BEFORE_CAPTURE")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            clearPendingUnlockTrigger("CAMERA_PERMISSION_REQUIRED")
            return
        }

        val attempts = prefs.getInt("unlock_capture_retries", 0)
        if (attempts >= 3) {
            clearPendingUnlockTrigger("CAMERA_CAPTURE_RETRIES_EXHAUSTED")
            return
        }
        val result = capturePhoto("front", 1_100 * 1024)
        // A correct PIN/password/pattern or biometric unlock during camera startup cancels this event.
        if (!prefs.getBoolean("unlock_capture_pending", false) || !keyguard.isKeyguardLocked) {
            clearPendingUnlockTrigger("DEVICE_UNLOCKED_DURING_CAPTURE")
            return
        }
        if (result.optBoolean("ok")) {
            val encoded = result.optString("imageBase64", "")
            val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > 1_200 * 1024) {
                clearPendingUnlockTrigger("CAPTURE_SIZE_INVALID")
                return
            }
            if (!prefs.getBoolean("unlock_capture_pending", false) || !keyguard.isKeyguardLocked) {
                clearPendingUnlockTrigger("DEVICE_UNLOCKED_BEFORE_PHOTO_COMMIT")
                return
            }
            val temp = File(filesDir, "unlock-capture-pending.tmp")
            try {
                val encryptedBytes = encryptLocalPhoto(bytes)
                FileOutputStream(temp).use { it.write(encryptedBytes); it.fd.sync() }
                if (photoFile.exists()) photoFile.delete()
                if (!temp.renameTo(photoFile)) throw IllegalStateException("Could not commit pending photo")
                prefs.edit().putLong("unlock_capture_photo_timestamp", result.optLong("timestamp", now))
                    .putString("unlock_capture_last_status", "CAPTURED_PENDING_UPLOAD")
                    .putBoolean("unlock_capture_pending", false)
                    .putLong("unlock_capture_pending_at", 0L)
                    .putInt("unlock_capture_retries", 0).apply()
                Log.i("JazzUnlockCapture", "Front-camera photo saved in app-private storage; upload queued")
                uploadPendingUnlockCapture(base, token, deviceId, photoFile)
                if (photoFile.exists()) enqueuePendingUnlockPhotoSync()
            } catch (failure: Exception) {
                runCatching { temp.delete() }
                Log.e("JazzUnlockCapture", "Could not persist captured photo", failure)
                clearPendingUnlockTrigger("LOCAL_PHOTO_SAVE_FAILED")
            }
        } else {
            val next = attempts + 1
            prefs.edit().putInt("unlock_capture_retries", next)
                .putString("unlock_capture_last_status", result.optString("error", "CAMERA_CAPTURE_FAILED")).apply()
            Log.w("JazzUnlockCapture", "Capture attempt $next failed: ${result.optString("error")}")
            if (next >= 3) clearPendingUnlockTrigger(result.optString("error", "CAMERA_CAPTURE_FAILED"))
        }
    }

    private fun clearPendingUnlockTrigger(status: String) {
        prefs.edit().putBoolean("unlock_capture_pending", false)
            .putLong("unlock_capture_pending_at", 0L)
            .putString("unlock_capture_last_status", status)
            .putInt("unlock_capture_retries", 0).apply()
    }

    /** Keep a successfully captured image locally until the authenticated Worker confirms relay storage. */
    private fun uploadPendingUnlockCapture(base: String, token: String, deviceId: String, photoFile: File) {
        val lastAttempt = prefs.getLong("unlock_capture_upload_attempt_at", 0L)
        if (System.currentTimeMillis() - lastAttempt < 8_000L) return
        prefs.edit().putLong("unlock_capture_upload_attempt_at", System.currentTimeMillis()).apply()
        try {
            val encryptedBytes = photoFile.readBytes()
            val bytes = decryptLocalPhoto(encryptedBytes)
            if (bytes.isEmpty() || bytes.size > 1_200 * 1024) {
                photoFile.delete()
                prefs.edit().putString("unlock_capture_last_status", "PENDING_PHOTO_INVALID").apply()
                return
            }
            val body = JSONObject().put("result", JSONObject()
                .put("ok", true).put("camera", "front").put("mimeType", "image/jpeg")
                .put("imageBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("timestamp", prefs.getLong("unlock_capture_photo_timestamp", System.currentTimeMillis())))
            val response = request("POST", base + "/android/device/unlock-capture-result", token, deviceId, body)
            if (response?.optBoolean("uploaded") == true) {
                photoFile.delete()
                prefs.edit().putString("unlock_capture_last_status", "UPLOADED_VERIFIED")
                    .putLong("unlock_capture_last_uploaded_at", System.currentTimeMillis()).apply()
                Log.i("JazzUnlockCapture", "Worker confirmed Unlock Capture photo relay")
            }
        } catch (failure: Exception) {
            // Retain the app-private file. The existing foreground agent retries after connectivity returns.
            Log.w("JazzUnlockCapture", "Photo upload deferred; retained locally", failure)
        }
    }

    private fun encryptLocalPhoto(plain: ByteArray): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("jazz_recovery_key_v1", null) as? SecretKey
            ?: error("Recovery encryption key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decryptLocalPhoto(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 12) { "Encrypted recovery photo is invalid" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("jazz_recovery_key_v1", null) as? SecretKey
            ?: error("Recovery encryption key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }

    private fun enqueuePendingUnlockPhotoSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<RecoveryHeartbeatWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "jazz-unlock-capture-upload", ExistingWorkPolicy.KEEP, request
        )
    }

    private fun locationState(): String {
        return runCatching { if (GlobalLocationController.isEnabled(this)) "ON" else "OFF" }.getOrDefault("UNKNOWN")
    }

    private fun getLocation(): JSONObject {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("ok", false).put("error", "LOCATION_PERMISSION_REQUIRED")
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true).mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time } ?: return JSONObject().put("ok", false).put("error", "NO_CACHED_LOCATION")
        return JSONObject().put("ok", true).put("latitude", loc.latitude).put("longitude", loc.longitude)
            .put("accuracyMeters", loc.accuracy).put("timestamp", loc.time)
    }

    private fun ring(duration: Long): JSONObject {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_ALARM
        val previousVolume = audioManager.getStreamVolume(stream)
        val maximumVolume = audioManager.getStreamMaxVolume(stream)
        val safeDuration = duration.coerceIn(1000L, 10000L)
        val tone = android.media.ToneGenerator(stream, 100)

        return try {
            // Temporarily raise the alarm stream to its maximum so the owner's
            // remote Ring Device command is audible even if alarm volume was low.
            audioManager.setStreamVolume(stream, maximumVolume, 0)

            val startedAt = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - startedAt < safeDuration) {
                val remaining = safeDuration - (android.os.SystemClock.elapsedRealtime() - startedAt)
                tone.startTone(
                    android.media.ToneGenerator.TONE_PROP_BEEP2,
                    remaining.coerceAtMost(1000L).toInt()
                )
                Thread.sleep(remaining.coerceAtMost(1050L))
            }
            JSONObject().put("ok", true)
                .put("stream", "alarm")
                .put("volumeRaised", true)
                .put("durationMs", safeDuration)
        } catch (error: Exception) {
            Log.e("JazzRecoveryRing", "Unable to play remote ring tone", error)
            JSONObject().put("ok", false).put("error", "RING_PLAYBACK_FAILED")
                .put("message", error.message ?: "Unknown audio error")
        } finally {
            runCatching { tone.stopTone() }
            runCatching { tone.release() }
            // Restore the user's previous alarm volume after the recovery ring.
            runCatching { audioManager.setStreamVolume(stream, previousVolume, 0) }
        }
    }

    private fun capturePhoto(camera: String, photoByteLimit: Int = maxPhotoBytes): JSONObject {
        val captureStartedAt = System.currentTimeMillis()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return photoFailure("CAMERA_PERMISSION_REQUIRED", camera, captureStartedAt)
        }
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val targetFacing = if (camera == "rear") CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            }
        } catch (failure: SecurityException) {
            return photoFailure("CAMERA_PERMISSION_REVOKED", camera, captureStartedAt)
        } catch (failure: Exception) {
            return photoFailure("CAMERA_ENUMERATION_FAILED", camera, captureStartedAt, failure)
        } ?: return photoFailure("CAMERA_NOT_AVAILABLE", camera, captureStartedAt)
        val characteristics = try { manager.getCameraCharacteristics(cameraId) } catch (failure: Exception) {
            return photoFailure("CAMERA_CHARACTERISTICS_FAILED", camera, captureStartedAt, failure)
        }
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            ?: return photoFailure("JPEG_OUTPUT_UNAVAILABLE", camera, captureStartedAt)
        val size = sizes.filter { maxOf(it.width, it.height) <= 2560 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return photoFailure("JPEG_OUTPUT_UNAVAILABLE", camera, captureStartedAt)
        val reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 2)
        val handlerThread = HandlerThread("JazzRecoveryCamera").apply { start() }
        val handler = Handler(handlerThread.looper)
        // Capture one frame at a time. The queue lets us discard unsettled 3A frames
        // without accidentally treating a stale ImageReader buffer as the final photo.
        val imageQueue = LinkedBlockingQueue<ByteArray>(2)
        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var openError: String? = null
        var sessionError = false
        reader.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                if (!imageQueue.offer(bytes)) {
                    imageQueue.poll()
                    imageQueue.offer(bytes)
                }
            }
        }, handler)
        try {
            val opened = CountDownLatch(1)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraDevice = camera; opened.countDown() }
                override fun onDisconnected(camera: CameraDevice) {
                    openError = "CAMERA_OPEN_DISCONNECTED"
                    camera.close()
                    opened.countDown()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    openError = "CAMERA_OPEN_ERROR_$error"
                    camera.close()
                    opened.countDown()
                }
            }, handler)
            if (!opened.await(6, TimeUnit.SECONDS)) return photoFailure("CAMERA_OPEN_TIMEOUT", camera, captureStartedAt)
            if (cameraDevice == null) return photoFailure(openError ?: "CAMERA_OPEN_FAILED", camera, captureStartedAt)
            val configured = CountDownLatch(1)
            cameraDevice!!.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) { session = value; configured.countDown() }
                override fun onConfigureFailed(value: CameraCaptureSession) { sessionError = true; configured.countDown() }
            }, handler)
            if (!configured.await(6, TimeUnit.SECONDS)) return photoFailure("CAMERA_SESSION_TIMEOUT", camera, captureStartedAt)
            if (session == null) return photoFailure(if (sessionError) "CAMERA_SESSION_CONFIG_FAILED" else "CAMERA_SESSION_FAILED", camera, captureStartedAt)
            val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
            val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
            val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val aeMode: Int? = when {
                flashAvailable && aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH) ->
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON) -> CaptureRequest.CONTROL_AE_MODE_ON
                else -> aeModes.firstOrNull()
            }
            val afMode: Int? = when {
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> null
            }

            var selectedBytes: ByteArray? = null
            var selectedResult: TotalCaptureResult? = null
            var threeASettled = false
            // Camera2 needs time to meter and focus. The old single immediate still
            // request could save a frame before auto-exposure had converged, producing
            // very dark photos on devices without a preview stream. Retry at most twice
            // after the initial metering frame; only the last selected frame is uploaded.
            for (attempt in 0 until 3) {
                val resultLatch = CountDownLatch(1)
                val resultRef = AtomicReference<TotalCaptureResult?>()
                val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    if (aeMode != null) set(CaptureRequest.CONTROL_AE_MODE, aeMode)
                    if (afMode != null) set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    }
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        if (attempt == 0) CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                        else CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                    if (afMode != null) {
                        set(CaptureRequest.CONTROL_AF_TRIGGER,
                            if (attempt == 0) CaptureRequest.CONTROL_AF_TRIGGER_START
                            else CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    }
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                }.build()

                session!!.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        resultRef.set(result)
                        resultLatch.countDown()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.w("JazzRecoveryPhoto", "Camera frame failed attempt=" + (attempt + 1) +
                            " reason=" + failure.reason)
                        resultLatch.countDown()
                    }
                }, handler)

                if (!resultLatch.await(5, TimeUnit.SECONDS)) {
                    return photoFailure("CAMERA_CAPTURE_RESULT_TIMEOUT", camera, captureStartedAt)
                }
                val frameResult = resultRef.get()
                    ?: return photoFailure("CAMERA_CAPTURE_RESULT_FAILED", camera, captureStartedAt)
                val frameBytes = imageQueue.poll(5, TimeUnit.SECONDS)
                    ?: return photoFailure("CAMERA_IMAGE_TIMEOUT", camera, captureStartedAt)

                selectedBytes = frameBytes
                selectedResult = frameResult
                val aeState = frameResult.get(CaptureResult.CONTROL_AE_STATE)
                val afState = frameResult.get(CaptureResult.CONTROL_AF_STATE)
                val aeReady = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                val afReady = afMode == null || afState == null ||
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED

                Log.i("JazzRecoveryPhoto", "Camera metering camera=" + camera +
                    " attempt=" + (attempt + 1) + "/3 aeState=" + aeState +
                    " afState=" + afState +
                    " exposureNs=" + frameResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) +
                    " iso=" + frameResult.get(CaptureResult.SENSOR_SENSITIVITY) +
                    " dimensions=" + size.width + "x" + size.height)

                if (aeReady && afReady) {
                    threeASettled = true
                    break
                }
            }

            val sourceBytes = selectedBytes
                ?: return photoFailure("CAMERA_CAPTURE_EMPTY", camera, captureStartedAt)
            if (!threeASettled) {
                Log.w("JazzRecoveryPhoto", "Auto-exposure/focus did not fully converge after 3 frames; using latest frame camera=" + camera)
            }
            val compressed = compressJpegWithinLimit(sourceBytes, photoByteLimit)
                ?: return photoFailure("PHOTO_COMPRESSION_FAILED_OR_TOO_LARGE", camera, captureStartedAt)
            Log.i("JazzRecoveryPhoto", "Capture ready camera=" + camera + " bytes=" + compressed.size +
                " sourceBytes=" + sourceBytes.size +
                " aeState=" + selectedResult?.get(CaptureResult.CONTROL_AE_STATE) +
                " exposureNs=" + selectedResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) +
                " iso=" + selectedResult?.get(CaptureResult.SENSOR_SENSITIVITY) +
                " durationMs=" + (System.currentTimeMillis() - captureStartedAt))
            return JSONObject().put("ok", true).put("camera", camera)
                .put("mimeType", "image/jpeg").put("imageBase64", Base64.encodeToString(compressed, Base64.NO_WRAP))
                .put("timestamp", System.currentTimeMillis())
        } catch (failure: SecurityException) {
            return photoFailure("CAMERA_ACCESS_RESTRICTED", camera, captureStartedAt, failure)
        } catch (failure: CameraAccessException) {
            val code = if (failure.reason == CameraAccessException.CAMERA_IN_USE ||
                failure.reason == CameraAccessException.MAX_CAMERAS_IN_USE) "CAMERA_BUSY" else "CAMERA_ACCESS_ERROR"
            return photoFailure(code, camera, captureStartedAt, failure)
        } catch (failure: Exception) {
            return photoFailure("CAMERA_CAPTURE_FAILED", camera, captureStartedAt, failure)
        } finally {
            runCatching { session?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { reader.close() }
            handlerThread.quitSafely()
        }
    }

    private fun photoFailure(code: String, camera: String, startedAt: Long, cause: Throwable? = null): JSONObject {
        Log.w("JazzRecoveryPhoto", "Capture failed camera=" + camera + " code=" + code +
            " durationMs=" + (System.currentTimeMillis() - startedAt) +
            (cause?.let { " exception=" + it.javaClass.simpleName } ?: ""))
        return JSONObject().put("ok", false).put("status", code).put("error", code)
            .put("camera", camera).put("timestamp", System.currentTimeMillis())
    }

    private fun compressJpegWithinLimit(source: ByteArray, byteLimit: Int = maxPhotoBytes): ByteArray? {
        if (source.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        // Use a power-of-two sample that does not undershoot the target edge.
        // Rounding the ratio up here halved images just above 2560 px (for example 2700 px -> 1350 px).
        val sample = Integer.highestOneBit(maxOf(1, largest / jpegLongEdge)).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        val bitmap = if (maxOf(decoded.width, decoded.height) > jpegLongEdge) {
            val scale = jpegLongEdge.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else decoded
        return try {
            for (quality in listOf(jpegQuality, 86, 82, 78, 74, 70, 66)) {
                val output = java.io.ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                val bytes = output.toByteArray()
                if (bytes.size <= byteLimit) return bytes
            }
            null
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun post(base: String, path: String, token: String, deviceId: String, body: JSONObject) {
        request("POST", base + path, token, deviceId, body)
    }

    private fun request(method: String, url: String, token: String, deviceId: String, body: JSONObject?): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        val isPhotoResult = (url.contains("/android/device/commands/") && url.endsWith("/result") ||
            url.endsWith("/android/device/unlock-capture-result")) &&
            !body?.optJSONObject("result")?.optString("imageBase64").isNullOrBlank()
        try {
            conn.requestMethod = method
            conn.connectTimeout = if (isPhotoResult) 10000 else 5000
            conn.readTimeout = if (isPhotoResult) 60000 else 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer " + token)
            conn.setRequestProperty("X-Jazz-Lost-Device-Id", deviceId)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText().take(4096) } ?: ""
            if (code !in 200..299) {
                Log.w("JazzRecoveryPhoto", "Server returned HTTP " + code + " for " + URL(url).path)
                return null
            }
            return if (text.isNotBlank()) JSONObject(text) else JSONObject().put("ok", true)
        } finally { conn.disconnect() }
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("jazz_recovery_key_v1", null) as? SecretKey ?: error("Recovery key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
