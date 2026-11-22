package com.gunakarna.jazzrecovery

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Resets the local consecutive-attempt sequence after any successful keyguard unlock, including biometrics. */
class UnlockCaptureUserPresentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val platformBaseline = runCatching { policy.currentFailedPasswordAttempts }.getOrDefault(0)
        prefs.edit()
            .putInt("unlock_capture_local_count", 0)
            .putInt("unlock_capture_platform_count", platformBaseline)
            .putInt("unlock_capture_platform_baseline", platformBaseline)
            .putBoolean("unlock_capture_triggered", false)
            .putBoolean("unlock_capture_pending", false)
            .putLong("unlock_capture_pending_at", 0L)
            .putInt("unlock_capture_retries", 0)
            .apply()
        Log.i(TAG, "User-present event received; consecutive failed-unlock state reset")
    }

    companion object {
        private const val TAG = "JazzUnlockCapture"
        private const val PREFS = "jazz_recovery_standalone"
    }
}
