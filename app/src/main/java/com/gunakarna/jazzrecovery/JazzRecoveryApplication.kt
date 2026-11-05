package com.gunakarna.jazzrecovery

import android.app.Application
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/**
 * Establishes a fast, network-constrained reconnect when the app process starts,
 * plus a persistent 15-minute fallback. It deliberately does not start the
 * camera/location foreground recovery service during boot.
 */
class JazzRecoveryApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val firebaseReady = initializeFirebase()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // A one-time sync handles app launch/process recreation promptly. The
        // boot receiver and FCM receiver independently enqueue the same worker.
        val startupRequest = OneTimeWorkRequestBuilder<RecoveryHeartbeatWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "jazz-recovery-startup-sync",
            ExistingWorkPolicy.KEEP,
            startupRequest
        )

        val periodicRequest = PeriodicWorkRequestBuilder<RecoveryHeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "jazz-recovery-heartbeat-fallback",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )

        Log.i(TAG, "Background reconnect scheduled; Firebase ready=$firebaseReady")
    }

    private fun initializeFirebase(): Boolean {
        val missing = buildList {
            if (BuildConfig.FIREBASE_API_KEY.isBlank()) add("apiKey")
            if (BuildConfig.FIREBASE_APP_ID.isBlank()) add("appId")
            if (BuildConfig.FIREBASE_PROJECT_ID.isBlank()) add("projectId")
            if (BuildConfig.FIREBASE_SENDER_ID.isBlank()) add("senderId")
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "FCM disabled: Firebase Gradle configuration missing (${missing.joinToString()})")
            return false
        }

        return try {
            val existing = FirebaseApp.getApps(this)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            val app = existing ?: FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
            )

            if (app == null) {
                Log.e(TAG, "Firebase default app initialization returned null")
                return false
            }

            Log.i(TAG, "Firebase default app initialized from Gradle BuildConfig")

            // Explicitly request the token after manual Firebase initialization.
            // onNewToken remains the normal refresh path; this closes the first-run gap.
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && !task.result.isNullOrBlank()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("fcm_token_pending", task.result)
                        .apply()
                    Log.i(TAG, "FCM registration token obtained; queued for authenticated server registration")
                    enqueueTokenSync()
                } else {
                    Log.w(TAG, "FCM token request failed: ${task.exception?.javaClass?.simpleName ?: "unknown"}")
                }
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Firebase default app initialization failed: ${error.javaClass.simpleName}", error)
            false
        }
    }

    private fun enqueueTokenSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RecoveryHeartbeatWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "jazz-recovery-fcm-token-sync",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    companion object {
        private const val TAG = "JazzRecoveryApp"
        private const val PREFS = "jazz_recovery_standalone"
    }
}
