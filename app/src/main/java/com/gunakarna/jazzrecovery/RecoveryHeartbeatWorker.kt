package com.gunakarna.jazzrecovery

import android.content.Context
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Best-effort background reconnect. This worker authenticates with the existing
 * paired device token, refreshes the FCM registration, posts a heartbeat, and
 * processes only the harmless set_recovery_mode command. Camera/location/ring
 * commands remain exclusively in the existing owner-enabled foreground service.
 *
 * WorkManager is intentionally network constrained and inexact. It cannot
 * promise an always-open socket or exact 15-minute execution.
 */
class RecoveryHeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = (prefs.getString("server", "") ?: "").trim().trimEnd('/')
        val deviceId = prefs.getString("device_id", "") ?: ""
        val encryptedToken = prefs.getString("secret", "") ?: ""

        if (!base.startsWith("https://") || deviceId.isBlank() || encryptedToken.isBlank()) {
            Log.w(TAG, "Reconnect deferred: serverConfigured=${base.startsWith("https://")}, deviceIdConfigured=${deviceId.isNotBlank()}, deviceTokenConfigured=${encryptedToken.isNotBlank()}")
            return Result.retry()
        }

        val token = runCatching { decrypt(encryptedToken) }.getOrElse {
            Log.e(TAG, "Reconnect cannot decrypt the saved device token; existing device pairing needs repair", it)
            return Result.failure()
        }
        if (token.length < 32) {
            Log.e(TAG, "Reconnect rejected: decrypted device token has an invalid length")
            return Result.failure()
        }

        return try {
            val firebaseConfigPresent = BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
                BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
                BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
                BuildConfig.FIREBASE_SENDER_ID.isNotBlank()
            val firebaseAvailable = FirebaseApp.getApps(applicationContext)
                .any { it.name == FirebaseApp.DEFAULT_APP_NAME }
            val pushTokenRegistered = when {
                !firebaseConfigPresent -> {
                    Log.w(TAG, "Firebase Gradle values are absent; heartbeat and periodic background polling continue without FCM")
                    true
                }
                !firebaseAvailable -> {
                    Log.w(TAG, "Firebase is configured but the default app is not initialized; retrying registration")
                    false
                }
                else -> registerPushTokenIfAvailable(base, deviceId, token, prefs)
            }

            val battery = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val payload = JSONObject()
                .put("deviceId", deviceId)
                .put("deviceName", android.os.Build.MODEL)
                .put("mode", if (prefs.getBoolean("lost_mode", false)) "LOST_MODE" else "NORMAL_MODE")
                .put("status", JSONObject()
                    .put("online", true)
                    .put("battery", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    .put("charging", battery.isCharging)
                    .put("network", "CONNECTED")
                    .put("source", "workmanager_background")
                    .put("readinessEnabled", prefs.getBoolean("readiness_enabled", false))
                    .put("agentServiceActive", prefs.getBoolean("readiness_service_active", false) &&
                        (System.currentTimeMillis() - prefs.getLong("readiness_service_heartbeat_at", 0L) < 120_000L)))

            val connection = URL("$base/android/device/heartbeat").openConnection() as HttpURLConnection
            val heartbeatCode = try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-Jazz-Lost-Device-Id", deviceId)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }

            if (heartbeatCode !in 200..299) {
                Log.w(TAG, "Heartbeat rejected by server: HTTP $heartbeatCode")
                return when {
                    heartbeatCode == 408 || heartbeatCode == 429 || heartbeatCode >= 500 -> Result.retry()
                    else -> Result.failure()
                }
            }
            Log.i(TAG, "Authenticated heartbeat accepted by server: HTTP $heartbeatCode")

            if (!uploadPendingUnlockCapture(base, deviceId, token, prefs)) {
                Log.w(TAG, "Pending Unlock Capture photo upload is not yet confirmed; WorkManager will retry")
                return Result.retry()
            }

            val commandSyncOk = processBackgroundSafeCommand(base, deviceId, token, prefs)
            if (!commandSyncOk) {
                Log.w(TAG, "Background-safe command synchronization was incomplete; WorkManager will retry")
                return Result.retry()
            }

            if (!pushTokenRegistered) {
                Log.w(TAG, "Heartbeat succeeded, but FCM token registration is pending; WorkManager will retry")
                return Result.retry()
            }

            Log.i(TAG, "Background reconnect completed: heartbeat accepted and background-safe command queue checked")
            Result.success()
        } catch (error: Exception) {
            Log.w(TAG, "Background reconnect failed: ${error.javaClass.simpleName}; WorkManager will retry", error)
            Result.retry()
        }
    }

    /** Upload an already-captured, app-private encrypted photo when connectivity returns. */
    private fun uploadPendingUnlockCapture(
        base: String,
        deviceId: String,
        token: String,
        prefs: android.content.SharedPreferences
    ): Boolean {
        val file = File(applicationContext.filesDir, "unlock-capture-pending.jpg")
        if (!file.exists()) return true
        return try {
            val encrypted = file.readBytes()
            require(encrypted.size > 12) { "Encrypted pending photo is invalid" }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey("jazz_recovery_key_v1", null) as? SecretKey
                ?: error("Recovery encryption key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            val plain = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
            require(plain.isNotEmpty() && plain.size <= 1_200 * 1024) { "Pending photo size is invalid" }
            val body = JSONObject().put("result", JSONObject()
                .put("ok", true).put("camera", "front").put("mimeType", "image/jpeg")
                .put("imageBase64", Base64.encodeToString(plain, Base64.NO_WRAP))
                .put("timestamp", prefs.getLong("unlock_capture_photo_timestamp", System.currentTimeMillis())))
            val connection = URL("$base/android/device/unlock-capture-result").openConnection() as HttpURLConnection
            val code = try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-Jazz-Lost-Device-Id", deviceId)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val response = if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    Log.w(TAG, "Unlock Capture relay rejected: HTTP ${connection.responseCode}")
                    return false
                }
                JSONObject(response).optBoolean("uploaded")
            } finally { connection.disconnect() }
            if (code) {
                file.delete()
                prefs.edit().putString("unlock_capture_last_status", "UPLOADED_VERIFIED")
                    .putLong("unlock_capture_last_uploaded_at", System.currentTimeMillis()).apply()
                Log.i(TAG, "Pending Unlock Capture photo relay verified by Worker")
                true
            } else false
        } catch (error: Exception) {
            Log.w(TAG, "Pending Unlock Capture photo remains queued: ${error.javaClass.simpleName}", error)
            false
        }
    }

    private fun registerPushTokenIfAvailable(
        base: String,
        deviceId: String,
        token: String,
        prefs: android.content.SharedPreferences
    ): Boolean {
        val fcmToken = prefs.getString("fcm_token_pending", null)
            ?: runCatching {
                Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
            }.onFailure {
                Log.w(TAG, "FCM token retrieval failed: ${it.javaClass.simpleName}")
            }.getOrNull()

        if (fcmToken.isNullOrBlank()) {
            Log.w(TAG, "FCM token is not available yet")
            return false
        }

        val connection = (URL("$base/android/device/push-token").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-Jazz-Lost-Device-Id", deviceId)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val body = JSONObject().put("token", fcmToken).put("platform", "android")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                prefs.edit().remove("fcm_token_pending").apply()
                Log.i(TAG, "FCM token registered with recovery server: HTTP $code")
                true
            } else {
                Log.w(TAG, "FCM token registration rejected: HTTP $code")
                false
            }
        } catch (error: Exception) {
            Log.w(TAG, "FCM token registration failed: ${error.javaClass.simpleName}", error)
            false
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The background worker intentionally handles only Lost Mode. It never
     * invokes camera, location, ringing, or other foreground-only operations.
     */
    private fun processBackgroundSafeCommand(
        base: String,
        deviceId: String,
        token: String,
        prefs: android.content.SharedPreferences
    ): Boolean {
        val connection = URL("$base/android/device/commands/next?backgroundSafe=1")
            .openConnection() as HttpURLConnection
        val command = try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-Jazz-Lost-Device-Id", deviceId)
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Background-safe command fetch rejected: HTTP $code")
                return false
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(raw).optJSONObject("command")
        } finally {
            connection.disconnect()
        }

        if (command == null) {
            Log.d(TAG, "No pending background-safe command")
            return true
        }

        val id = command.optString("id")
        val type = command.optString("type")
        if (id.isBlank() || type != "set_recovery_mode") {
            Log.w(TAG, "Ignored unexpected background-safe command payload")
            return false
        }

        val enabled = command.optJSONObject("args")?.optBoolean("enabled", true) ?: true
        if (!prefs.edit().putBoolean("lost_mode", enabled).commit()) {
            Log.e(TAG, "Could not persist Lost Mode state; command will be retried")
            return false
        }

        val result = JSONObject().put(
            "result",
            JSONObject().put("ok", true)
                .put("mode", if (enabled) "LOST_MODE" else "NORMAL_MODE")
        )
        val resultConnection = URL("$base/android/device/commands/$id/result")
            .openConnection() as HttpURLConnection
        return try {
            resultConnection.requestMethod = "POST"
            resultConnection.connectTimeout = 8000
            resultConnection.readTimeout = 10000
            resultConnection.instanceFollowRedirects = false
            resultConnection.setRequestProperty("Authorization", "Bearer $token")
            resultConnection.setRequestProperty("X-Jazz-Lost-Device-Id", deviceId)
            resultConnection.setRequestProperty("Content-Type", "application/json")
            resultConnection.doOutput = true
            resultConnection.outputStream.use { it.write(result.toString().toByteArray(Charsets.UTF_8)) }
            val code = resultConnection.responseCode
            if (code in 200..299) {
                Log.i(TAG, "Lost Mode command applied and acknowledged: HTTP $code")
                true
            } else {
                Log.w(TAG, "Lost Mode command acknowledgement rejected: HTTP $code")
                false
            }
        } catch (error: Exception) {
            Log.w(TAG, "Lost Mode command acknowledgement failed: ${error.javaClass.simpleName}", error)
            false
        } finally {
            resultConnection.disconnect()
        }
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Invalid encrypted token" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("jazz_recovery_key_v1", null) as? SecretKey
            ?: error("Recovery key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "JazzRecoverySync"
        private const val PREFS = "jazz_recovery_standalone"
    }
}
