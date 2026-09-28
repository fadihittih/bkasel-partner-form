package com.fadi.healthsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WorkManager job that runs one SyncEngine pass.
 * Used both for the hourly background sync and for the "Sync now" button.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        SyncEngine(applicationContext).run()
        Result.success()
    } catch (e: IOException) {
        // Network trouble: let WorkManager retry with backoff (a few times).
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    } catch (e: Exception) {
        // Permission/config problems won't fix themselves by retrying.
        Result.failure()
    }

    companion object {
        const val PERIODIC_WORK = "healthsync-periodic"
        const val MANUAL_WORK = "healthsync-manual"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Hourly sync. KEEP = don't reset the schedule every time the app starts. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(60, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** "Sync now" button. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
