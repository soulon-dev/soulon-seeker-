package com.soulon.app.sync

import android.content.Context
import androidx.work.*
import com.soulon.app.SeedVaultKeyManager
import com.soulon.app.StorageManager
import com.soulon.app.wallet.WalletManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

class BackendMemoryMigrationWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BackendMemoryMigration"
        private const val WORK_NAME = "backend_memory_migration_work"

        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<BackendMemoryMigrationWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )

            Timber.i("$TAG: 已调度后台迁移任务")
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackendMemoryMigrationWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Timber.i("$TAG: 已触发一次迁移")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val keyManager = SeedVaultKeyManager(applicationContext)
            val walletManager = WalletManager(applicationContext)
            val storageManager = StorageManager(applicationContext, keyManager, walletManager)

            val migrated = storageManager.migrateBackendStoredMemories(maxCount = 20)
            Timber.i("$TAG: 迁移完成 migrated=$migrated")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 迁移失败")
            Result.retry()
        }
    }
}

