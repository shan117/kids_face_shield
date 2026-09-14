package com.shantanu.shield.remote

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Weekly child-side sync. A plain [CoroutineWorker] that resolves its one dependency from the Hilt graph
 * via an [EntryPoint] — this sidesteps the heavier hilt-work / WorkerFactory / Configuration.Provider
 * setup. All real logic (and the opt-in gate) lives in [RemoteReportSync]; the worker only schedules and
 * maps the outcome to success/retry.
 */
class RemoteReportWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun remoteReportSync(): RemoteReportSync
    }

    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors
            .fromApplication(applicationContext, SyncEntryPoint::class.java)
            .remoteReportSync()
        return when (sync.syncNow()) {
            // Nothing to retry — either it worked, or there's deliberately nothing to send.
            RemoteReportSync.Result.SUCCESS,
            RemoteReportSync.Result.DISABLED,
            RemoteReportSync.Result.NOT_CHILD,
            RemoteReportSync.Result.NOT_PAIRED -> Result.success()
            // Transient (offline / relay hiccup) — let WorkManager back off and try again.
            RemoteReportSync.Result.FAILED -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "remote_report_sync"

        /** Periodic upload every [repeatDays] (daily/weekly cadence). UPDATE so changing the cadence
         *  re-schedules the existing work instead of keeping the old period. */
        fun schedule(context: Context, repeatDays: Long = 7) {
            val request = PeriodicWorkRequestBuilder<RemoteReportWorker>(repeatDays, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}
