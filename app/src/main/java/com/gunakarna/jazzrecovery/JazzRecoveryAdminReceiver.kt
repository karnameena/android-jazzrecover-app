package com.gunakarna.jazzrecovery

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log

/**
 * Owner-consented Device Admin receiver. WATCH_LOGIN is used only to receive
 * Android's official failed/successful device-credential callbacks. It does not
 * read, store, or transmit the PIN, password, or pattern.
 */
class JazzRecoveryAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            Log.i(TAG, "Failed unlock observed while Unlock Capture is OFF")
            return
        }

        val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val platformCount = runCatching { policy.currentFailedPasswordAttempts }.getOrDefault(0)
        val platformBaseline = prefs.getInt(KEY_PLATFORM_BASELINE, 0)
        val platformCountSinceEnabled = (platformCount - platformBaseline).coerceAtLeast(0)
        val localCount = prefs.getInt(KEY_LOCAL_COUNT, 0) + 1
        val consecutiveCount = maxOf(platformCountSinceEnabled, localCount)
        val editor = prefs.edit().putInt(KEY_LOCAL_COUNT, consecutiveCount)
            .putInt(KEY_PLATFORM_COUNT, platformCount)

        if (consecutiveCount >= TRIGGER_ATTEMPTS && !prefs.getBoolean(KEY_TRIGGERED, false)) {
            editor.putBoolean(KEY_TRIGGERED, true)
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                .putInt(KEY_CAPTURE_RETRIES, 0)
            Log.i(TAG, "Unlock Capture trigger queued after $consecutiveCount consecutive failed attempts")
        } else {
            Log.i(TAG, "Failed unlock count=$consecutiveCount; trigger threshold=$TRIGGER_ATTEMPTS")
        }
        editor.apply()
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LOCAL_COUNT, 0)
            .putInt(KEY_PLATFORM_COUNT, 0)
            .putInt(KEY_PLATFORM_BASELINE, 0)
            .putBoolean(KEY_TRIGGERED, false)
            .putBoolean(KEY_PENDING, false)
            .putLong(KEY_PENDING_AT, 0L)
            .putInt(KEY_CAPTURE_RETRIES, 0)
            .apply()
        Log.i(TAG, "Successful device unlock; consecutive failed-attempt counter reset")
    }

    companion object {
        private const val TAG = "JazzUnlockCapture"
        private const val PREFS = "jazz_recovery_standalone"
        private const val TRIGGER_ATTEMPTS = 2
        private const val KEY_ENABLED = "unlock_capture_enabled"
        private const val KEY_LOCAL_COUNT = "unlock_capture_local_count"
        private const val KEY_PLATFORM_COUNT = "unlock_capture_platform_count"
        private const val KEY_PLATFORM_BASELINE = "unlock_capture_platform_baseline"
        private const val KEY_TRIGGERED = "unlock_capture_triggered"
        private const val KEY_PENDING = "unlock_capture_pending"
        private const val KEY_PENDING_AT = "unlock_capture_pending_at"
        private const val KEY_CAPTURE_RETRIES = "unlock_capture_retries"
    }
}
