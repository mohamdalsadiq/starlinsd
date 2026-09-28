package com.example.network

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.SubscriptionRepository
import com.example.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that reads router clients and triggers PAUSE/RESUME on linked sessions.
 *
 * Runs every 3 minutes when on Wi-Fi (required for local Starlink access).
 * Uses WorkManager for battery-friendly periodic execution that respects Doze mode.
 *
 * The worker does NOT start a foreground service — it's a short bounded operation
 * (read clients → classify → update sessions) that completes in seconds.
 */
class DeviceMonitorWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext
            val db = AppDatabase.getDatabase(app)
            val repo = SubscriptionRepository(app)
            val monitor = DeviceMonitor(repo.asSessionControl(), db.deviceListDao())

            // Read clients from the router using the existing local probe.
            val probe = StarlinkProbe(app)
            val report = probe.run { /* progress callback — no-op in background */ }

            val clients = report.clients ?: return@withContext Result.success()

            monitor.scan(clients)
            Result.success()
        } catch (e: Exception) {
            // Network failures are expected when the phone is off Wi-Fi or the router is
            // unreachable. WorkManager will retry on the next cycle.
            Result.success() // Don't return retry() — we don't want exponential backoff
        }
    }

    companion object {
        private const val WORK_NAME = "device-monitor"
        private const val INTERVAL_MINUTES = 3L

        /**
         * Enqueues the periodic monitor. Call this when the user enables device tracking
         * or when the app starts and tracking was previously enabled.
         */
        fun enable(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                .build()

            val request = PeriodicWorkRequestBuilder<DeviceMonitorWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.SECONDS) // small delay to avoid cold-start burst
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
