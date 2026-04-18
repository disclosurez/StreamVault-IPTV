package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class BackgroundEpgSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackgroundEpgSyncWorkerEntryPoint {
        fun syncManager(): SyncManager
    }

    override suspend fun doWork(): Result {
        val providerId = inputData.getLong(KEY_PROVIDER_ID, INVALID_PROVIDER_ID)
        if (providerId == INVALID_PROVIDER_ID) {
            return Result.failure()
        }

        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                BackgroundEpgSyncWorkerEntryPoint::class.java
            )
            when (val result = entryPoint.syncManager().syncEpg(providerId, force = true)) {
                is com.streamvault.domain.model.Result.Success -> Result.success()
                is com.streamvault.domain.model.Result.Error -> {
                    if (result.message.contains("not found", ignoreCase = true)) {
                        Result.success()
                    } else if (shouldRetry(result.exception)) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                com.streamvault.domain.model.Result.Loading -> Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background EPG work failed for provider $providerId", e)
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
        private const val TAG = "BackgroundEpgWorker"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val INVALID_PROVIDER_ID = -1L
        private const val UNIQUE_WORK_PREFIX = "background-epg-sync-"

        fun enqueue(context: Context, providerId: Long) {
            if (providerId <= 0L) return
            val request = OneTimeWorkRequestBuilder<BackgroundEpgSyncWorker>()
                .setInputData(Data.Builder().putLong(KEY_PROVIDER_ID, providerId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(providerId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, providerId: Long) {
            if (providerId <= 0L) return
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(providerId))
        }

        private fun uniqueWorkName(providerId: Long): String = "$UNIQUE_WORK_PREFIX$providerId"
    }
}
