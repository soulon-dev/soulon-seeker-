package com.soulon.app.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.soulon.app.MainActivity
import com.soulon.app.R
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import timber.log.Timber

/**
 * 奇遇通知管理器
 * 
 * 负责创建和发送通知，提醒用户探索 AI 的奇遇问题
 */
class ProactiveQuestionNotificationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AdventureNotification"
        
        const val CHANNEL_ID = "ai_adventures"
        
        const val NOTIFICATION_ID_BASE = 1000
        
        // Intent Extra Keys
        const val EXTRA_QUESTION_ID = "question_id"
        const val EXTRA_QUESTION_TEXT = "question_text"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }
    
    init {
        LocaleManager.getSavedLanguageCode(context)?.let { AppStrings.setLanguage(it) }
        createNotificationChannel()
    }
    
    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channelName = AppStrings.tr("AI 奇遇", "AI Adventures")
            val channelDescription = AppStrings.tr(
                "AI 助手的奇遇探索通知，帮助完善您的人格画像",
                "Adventure notifications from your AI assistant to improve your persona profile"
            )
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
                enableLights(true)
                enableVibration(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Timber.d("$TAG: 通知渠道已创建")
        }
    }
    
    /**
     * 检查是否有通知权限
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    
    /**
     * 发送主动提问通知
     * 
     * @param question 要通知的问题
     * @return 是否发送成功
     */
    fun sendQuestionNotification(question: ProactiveQuestionEntity): Boolean {
        if (!hasNotificationPermission()) {
            Timber.w("$TAG: 没有通知权限，无法发送通知")
            return false
        }
        
        try {
            // 创建打开 App 的 Intent
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_QUESTION_ID, question.id)
                putExtra(EXTRA_QUESTION_TEXT, question.questionText)
                putExtra(EXTRA_FROM_NOTIFICATION, true)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                question.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 获取类别信息
            val category = try {
                QuestionCategory.valueOf(question.category)
            } catch (e: Exception) {
                QuestionCategory.DAILY_LIFE
            }
            
            // 构建通知
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification) // 奇遇图标
                .setContentTitle(AppStrings.tr("✨ 新的奇遇等你探索", "✨ A new adventure awaits"))
                .setContentText(question.questionText)
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(question.questionText)
                    .setSummaryText(AppStrings.trf("%s · 奇遇", "%s · Adventure", category.displayName)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(
                    R.drawable.ic_notification,
                    AppStrings.tr("开始探索", "Start"),
                    pendingIntent
                )
                .build()
            
            // 发送通知
            val notificationId = NOTIFICATION_ID_BASE + question.id.hashCode()
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            
            Timber.i("$TAG: 奇遇通知已发送: ${question.questionText.take(30)}...")
            return true
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 发送通知失败")
            return false
        }
    }
    
    /**
     * 发送提醒通知（有待回答的问题）
     */
    fun sendReminderNotification(pendingCount: Int) {
        if (!hasNotificationPermission() || pendingCount == 0) return
        
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_FROM_NOTIFICATION, true)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                "reminder".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(AppStrings.trf("🗺️ 还有 %d 个奇遇等你探索", "🗺️ %d adventures are waiting", pendingCount))
                .setContentText(AppStrings.tr("每一次探索都是了解自己的机会", "Every adventure helps you understand yourself better"))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE, notification)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 发送提醒通知失败")
        }
    }
    
    /**
     * 取消所有主动提问通知
     */
    fun cancelAllNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
