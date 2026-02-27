package com.soulon.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.soulon.app.MainActivity
import com.soulon.app.R

/**
 * Firebase Cloud Messaging 服务
 * 处理推送通知的接收和显示
 */
class SoulonFirebaseMessagingService : FirebaseMessagingService() {
    
    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "memory_ai_notifications"
        private const val CHANNEL_NAME = "Memory AI 通知"
    }
    
    /**
     * 当 FCM Token 刷新时调用
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "新的 FCM Token: ${token.take(20)}...")
        
        // 保存新 Token
        PushNotificationService.getInstance(applicationContext).saveFcmToken(token)
    }
    
    /**
     * 收到消息时调用
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "收到推送消息: from=${remoteMessage.from}")
        
        // 处理数据消息
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "消息数据: ${remoteMessage.data}")
            PushNotificationService.getInstance(applicationContext)
                .handleNotification(remoteMessage.data)
        }
        
        // 处理通知消息（应用在前台时需要手动显示）
        remoteMessage.notification?.let { notification ->
            showNotification(
                title = notification.title ?: "Soulon",
                body = notification.body ?: "",
                data = remoteMessage.data
            )
        }
    }
    
    /**
     * 显示通知
     */
    private fun showNotification(
        title: String,
        body: String,
        data: Map<String, String>
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // 创建通知渠道（Android 8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Soulon 订阅和系统通知"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // 创建点击意图
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // 传递通知数据
            data.forEach { (key, value) -> putExtra(key, value) }
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 根据通知类型选择图标
        val iconResId = when (data["type"]) {
            "subscription_reminder" -> android.R.drawable.ic_dialog_info
            "payment_success" -> android.R.drawable.ic_dialog_info
            "payment_failed" -> android.R.drawable.ic_dialog_alert
            else -> android.R.drawable.ic_dialog_info
        }
        
        // 构建通知
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconResId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()
        
        // 显示通知
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)
        
        Log.i(TAG, "通知已显示: $title")
    }
}
