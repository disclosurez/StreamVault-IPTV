package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamvault.data.local.dao.ProviderDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class ProviderSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderSyncWorkerEntryPoint {
        fun providerDao(): ProviderDao
        fun syncManager(): SyncManager
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ProviderSyncWorkerEntryPoint::class.java
            )
            val providers = entryPoint.providerDao().getAllSync()
            if (providers.isEmpty()) {
                return Result.success()
            }

            var sawRetryableFailure = false
            providers.forEach { provider ->
                when (val result = entryPoint.syncManager().sync(provider.id, force = false)) {
                    is com.streamvault.domain.model.Result.Error -> {
                        Log.w(TAG, "Provider sync worker failed for provider ${provider.id}: ${result.message}")
                        if (shouldRetry(result.exception)) {
                            sawRetryableFailure = true
                        }
                    }
                    else -> Unit
                }
            }

            if (sawRetryableFailure) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Provider sync worker failed", e)
            if (shouldRetry(e)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean {
        return when (error) {
            is java.io.IOException -> true
            is SQLiteException -> error.message.orEmpty().contains("locked", ignoreCase = true) ||
                error.message.orEmpty().contains("busy", ignoreCase = true)
            else -> false
        }
    }

    companion object {
        private const val TAG = "ProviderSyncWorker"
        private const val UNIQUE_WORK_NAME = "provider-sync-worker"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProviderSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
