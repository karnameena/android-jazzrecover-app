package com.gunakarna.jazzrecovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules an authenticated background reconnect after boot, user unlock or
 * app replacement. It deliberately does not start RecoveryAgentService:
 * Android restricts starting camera/location foreground services from boot.
 */
class RecoveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val serverConfigured = (prefs.getString("server", "") ?: "").startsWith("https://")
        val deviceConfigured = !(prefs.getString("device_id", "") ?: "").isBlank()
        val tokenConfigured = !(prefs.getString("secret", "") ?: "").isBlank()

        if (!serverConfigured || !deviceConfigured || !tokenConfigured) {
            Log.w(TAG, "Boot reconnect not queued: serverConfigured=$serverConfigured, deviceIdConfigured=$deviceConfigured, deviceTokenConfigured=$tokenConfigured")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RecoveryHeartbeatWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "jazz-recovery-boot-reconnect",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        Log.i(TAG, "Boot/unlock reconnect queued; waiting for a validated network")
    }

    companion object {
        private const val TAG = "JazzRecoveryBoot"
        private const val PREFS = "jazz_recovery_standalone"
    }
}
