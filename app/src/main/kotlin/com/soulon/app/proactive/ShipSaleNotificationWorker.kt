package com.soulon.app.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.soulon.app.MainActivity
import com.soulon.app.R
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import java.util.concurrent.TimeUnit

class ShipSaleNotificationWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val CHANNEL_ID = "ship_sale"
        private const val KEY_START_AT_SEC = "start_at_sec"
        private const val NOTIFICATION_ID = 230415

        fun scheduleIfPossible(context: Context, startAtSec: Long) {
            if (startAtSec <= 0) return
            val nowSec = System.currentTimeMillis() / 1000
            val notifyAtSec = startAtSec - 15 * 60
            val delaySec = notifyAtSec - nowSec
            if (delaySec <= 0) return

            val data = Data.Builder()
                .putLong(KEY_START_AT_SEC, startAtSec)
                .build()

            val work = OneTimeWorkRequestBuilder<ShipSaleNotificationWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "ship_sale_notify_$startAtSec",
                ExistingWorkPolicy.REPLACE,
                work
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                AppStrings.tr("发售提醒", "Sale Alerts"),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result {
        LocaleManager.getSavedLanguageCode(applicationContext)?.let { AppStrings.setLanguage(it) }
        ensureChannel()

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppStrings.tr("发售即将开始", "Sale is starting soon"))
            .setContentText(AppStrings.tr("15 分钟后开放领取内测门票", "Beta pass mint opens in 15 minutes"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationPermissionHelper.hasNotificationPermission(applicationContext).let { ok ->
            if (!ok) return Result.success()
        }
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        return Result.success()
    }
}

