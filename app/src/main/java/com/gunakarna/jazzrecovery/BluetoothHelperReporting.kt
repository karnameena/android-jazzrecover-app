package com.gunakarna.jazzrecovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * BLE encounter helper.
 * Phone A advertises only when its GPS provider AND validated internet connection are both
 * unavailable. A beacon token is fetched and cached while online so offline advertising can
 * continue without a website Lost Mode command. Phone B scans only after explicit consent,
 * with location services and validated internet available. Encounter coordinates belong to
 * Phone B and are not a claim about Phone A's exact position.
 */
class BluetoothHelperReporting(private val context: Context) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b5b3c0a1-7c9e-4d12-9f40-4a415a5a5a01")
        private const val PREFS = "jazz_recovery_standalone"
        const val HELPER_SCAN_CONSENT = "bluetooth_helper_scanning_enabled"
        private const val TOKEN_REFRESH_MS = 45_000L
        private const val TOKEN_DEDUPE_MS = 5 * 60_000L
        private const val BEACON_TOKEN_PREF = "cached_lost_device_beacon_token"
        private const val BEACON_TOKEN_DEVICE_PREF = "cached_lost_device_beacon_device_id"
        private const val BEACON_TOKEN_REFRESH_PREF = "cached_lost_device_beacon_refreshed_at"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val helperConsentEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    private val consentListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HELPER_SCAN_CONSENT && !prefs.getBoolean(HELPER_SCAN_CONSENT, false)) {
            // Invalidate queued encounter work and stop the active BLE scan on opt-out.
            helperConsentEpoch.incrementAndGet()
            stopScanning()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(consentListener)
    }

    private val mainPrefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val btManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    @Volatile private var advertising = false
    @Volatile private var scanning = false
    @Volatile private var tokenRequestRunning = false
    @Volatile private var beaconToken: String? = prefs.getString(BEACON_TOKEN_PREF, null)
        ?.takeIf { isValidBeaconToken(it) && prefs.getString(BEACON_TOKEN_DEVICE_PREF, "") == (prefs.getString("device_id", "") ?: "") }
    @Volatile private var lastTokenRefresh = prefs.getLong(BEACON_TOKEN_REFRESH_PREF, 0L)
    @Volatile private var lastTokenRefreshAttempt = 0L
    @Volatile private var currentBase = ""
    @Volatile private var currentDeviceId = ""
    @Volatile private var currentAuth = ""

    @Suppress("UNUSED_PARAMETER")
    fun tick(base: String, deviceId: String, auth: String, lostMode: Boolean) {
        currentBase = base
        currentDeviceId = deviceId
        currentAuth = auth

        // A beacon token must be obtained while this device is online. Keep the latest
        // server-issued token in app-private storage so Phone A can advertise after it
        // subsequently loses both GPS and internet access.
        if (prefs.getString(BEACON_TOKEN_DEVICE_PREF, "") != deviceId) {
            beaconToken = null
            lastTokenRefresh = 0L
            prefs.edit().remove(BEACON_TOKEN_PREF).remove(BEACON_TOKEN_DEVICE_PREF)
                .remove(BEACON_TOKEN_REFRESH_PREF).apply()
        }
        val internetAvailable = hasValidatedInternet()
        if (internetAvailable && (beaconToken == null || System.currentTimeMillis() - lastTokenRefresh >= TOKEN_REFRESH_MS)) {
            refreshBeaconToken()
        }

        // The requested four-state rule is independent of the website Lost Mode flag:
        // advertise only when GPS is OFF and validated internet is OFF.
        if (!isGpsProviderEnabled() && !internetAvailable) {
            if (!advertising) startAdvertisingIfPossible()
        } else {
            stopAdvertising()
        }

        // A helper must be able to obtain a location and upload its encounter report.
        if (helperPermissionConsent() && isLocationServicesEnabled() && internetAvailable) {
            startScanningIfPossible()
        } else {
            stopScanning()
        }
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(consentListener)
        stopAdvertising()
        stopScanning()
        executor.shutdownNow()
    }

    private fun has(permission: String): Boolean =
        Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // Android permissions are necessary but are not user consent. Helper scanning requires
    // a separate, explicit in-app opt-in plus the relevant runtime permissions.
    private fun helperPermissionConsent(): Boolean {
        if (!prefs.getBoolean(HELPER_SCAN_CONSENT, false)) return false
        val locationGranted = has(Manifest.permission.ACCESS_FINE_LOCATION) ||
            has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= 31) {
            has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            locationGranted && has(Manifest.permission.BLUETOOTH)
        }
        return locationGranted && bluetoothGranted
    }

    private fun bluetoothReady(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    private fun hasValidatedInternet(): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    // Phone A's condition is specifically the GPS provider state, not SIM presence.
    private fun isGpsProviderEnabled(): Boolean = try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { false }

    // Phone B may use any Android location provider, but the system Location switch
    // must be enabled before it scans and attempts an encounter-location report.
    private fun isLocationServicesEnabled(): Boolean = try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }

    private fun isValidBeaconToken(value: String): Boolean = try {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).size == 10
    } catch (_: Exception) { false }

    private fun refreshBeaconToken() {
        val now = System.currentTimeMillis()
        if (!hasValidatedInternet() || tokenRequestRunning || now - lastTokenRefreshAttempt < TOKEN_REFRESH_MS ||
            currentBase.isBlank() || currentDeviceId.isBlank() || currentAuth.isBlank()) return
        tokenRequestRunning = true
        lastTokenRefreshAttempt = now
        executor.execute {
            try {
                val response = api("POST", "/android/device/beacon", JSONObject())
                val token = response?.optString("beaconToken").orEmpty()
                if (isValidBeaconToken(token)) {
                    beaconToken = token
                    lastTokenRefresh = System.currentTimeMillis()
                    prefs.edit().putString(BEACON_TOKEN_PREF, token)
                        .putString(BEACON_TOKEN_DEVICE_PREF, currentDeviceId)
                        .putLong(BEACON_TOKEN_REFRESH_PREF, lastTokenRefresh).apply()
                    // This call is guarded by the same GPS+internet condition as tick().
                    startAdvertisingIfPossible()
                }
            } finally { tokenRequestRunning = false }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingIfPossible() {
        if (advertising || isGpsProviderEnabled() || hasValidatedInternet() || !bluetoothReady()) return
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        if (Build.VERSION.SDK_INT < 31 && !has(Manifest.permission.BLUETOOTH) && !has(Manifest.permission.BLUETOOTH_ADMIN)) return
        val raw = runCatching { Base64.decode(beaconToken ?: return, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull() ?: return
        if (raw.size != 10) return
        val advertiser = try { adapter?.bluetoothLeAdvertiser } catch (_: SecurityException) { null } ?: return
        val data = try {
            AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE_UUID), raw).setIncludeDeviceName(false).build()
        } catch (_: Exception) { return }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).setConnectable(false).build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            advertising = true
        } catch (_: SecurityException) { advertising = false }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!advertising) return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) { }
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true }
        override fun onStartFailure(errorCode: Int) { advertising = false }
    }

    @SuppressLint("MissingPermission")
    private fun startScanningIfPossible() {
        if (scanning || !bluetoothReady()) return
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_SCAN)) return
        if (Build.VERSION.SDK_INT < 31 && !has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val scanner = try { adapter?.bluetoothLeScanner } catch (_: SecurityException) { null } ?: return
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null, null).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        try { scanner.startScan(listOf(filter), settings, scanCallback); scanning = true }
        catch (_: SecurityException) { scanning = false }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // A callback may already be queued when the user opts out.
            if (!prefs.getBoolean(HELPER_SCAN_CONSENT, false)) {
                stopScanning()
                return
            }
            val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (bytes.size != 10) return
            val consentEpoch = helperConsentEpoch.get()
            if (!helperPermissionConsent() || consentEpoch != helperConsentEpoch.get()) return
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val key = "helper_seen_" + token
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(key, 0L) < TOKEN_DEDUPE_MS) return
            prefs.edit().putLong(key, now).apply()
            executor.execute { reportEncounter(token, now, consentEpoch) }
        }
    }

    private fun reportEncounter(beacon: String, observedAt: Long, consentEpoch: Long) {
        if (consentEpoch != helperConsentEpoch.get() || !helperPermissionConsent()) return
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        val latch = java.util.concurrent.CountDownLatch(1)
        var location: Location? = null
        val cancel = CancellationTokenSource()
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                .addOnSuccessListener { location = it; latch.countDown() }
                .addOnFailureListener { latch.countDown() }
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            if (location == null) {
                val cacheLatch = java.util.concurrent.CountDownLatch(1)
                client.lastLocation.addOnSuccessListener { location = it; cacheLatch.countDown() }
                    .addOnFailureListener { cacheLatch.countDown() }
                cacheLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: Exception) { } finally { cancel.cancel() }
        val loc = location ?: return
        val age = System.currentTimeMillis() - loc.time
        if (!loc.latitude.isFinite() || !loc.longitude.isFinite() || loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0 || age !in 0L..900_000L) return
        val body = JSONObject().put("beaconToken", beacon).put("encounterId", UUID.randomUUID().toString())
            .put("observedAt", observedAt).put("locationTimestamp", loc.time)
            .put("latitude", loc.latitude).put("longitude", loc.longitude)
            .put("accuracyMeters", if (loc.hasAccuracy()) loc.accuracy.toDouble() else JSONObject.NULL)
            .put("locationSource", "phone_b_fused").put("locationFresh", age <= 30_000L)
        // Re-check consent after asynchronous location retrieval so opt-out cancels queued reports.
        if (consentEpoch != helperConsentEpoch.get() || !helperPermissionConsent()) return
        api("POST", "/android/helper/encounters", body)
    }

    private fun api(method: String, path: String, body: JSONObject): JSONObject? {
        val base = currentBase.trimEnd('/')
        val deviceId = currentDeviceId
        val auth = currentAuth
        if (!base.startsWith("https://") || deviceId.isBlank() || auth.isBlank()) return null
        return try {
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $auth")
                connection.setRequestProperty("X-Jazz-Lost-Device-Id", deviceId)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) null
                else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
        } catch (_: Exception) { null }
    }
}
