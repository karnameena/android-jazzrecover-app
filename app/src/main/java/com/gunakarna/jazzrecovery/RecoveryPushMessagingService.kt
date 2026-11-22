package com.gunakarna.jazzrecovery

import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

/**
 * FCM is a wake-up hint only. The phone authenticates to the recovery API and
 * fetches its own queued command; no command arguments or recovery data are
 * trusted from the push payload.
 */
class RecoveryPushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("fcm_token_pending", token)
            .apply()
        Log.i(TAG, "FCM token refreshed; queued authenticated registration")
        enqueueSync("token-refresh")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["event"] == "command_wakeup") {
            Log.i(TAG, "Received recovery command wake-up hint")
            enqueueSync("command-wakeup")
        } else {
            Log.d(TAG, "Ignored FCM message without a recognized wake-up event")
        }
    }

    override fun onDeletedMessages() {
        // FCM may discard pending messages if too many accumulate. Reconcile
        // with the authenticated command queue instead of relying on each push.
        Log.w(TAG, "FCM reported deleted messages; scheduling command reconciliation")
        enqueueSync("deleted-messages")
    }

    private fun enqueueSync(reason: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RecoveryHeartbeatWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "jazz-recovery-push-sync",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        Log.i(TAG, "Queued background synchronization: $reason")
    }

    companion object {
        private const val TAG = "JazzRecoveryFCM"
        private const val PREFS = "jazz_recovery_standalone"
    }
}
