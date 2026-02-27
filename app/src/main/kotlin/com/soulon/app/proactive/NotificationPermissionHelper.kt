package com.soulon.app.proactive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 通知权限帮助工具
 * 
 * Android 13 (API 33) 及以上需要运行时请求 POST_NOTIFICATIONS 权限
 */
object NotificationPermissionHelper {
    
    private const val TAG = "NotificationPermission"
    
    /**
     * 检查是否有通知权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
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
     * 是否需要请求权限
     */
    fun shouldRequestPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission(context)
    }
    
    /**
     * 创建权限请求启动器
     * 
     * 需要在 Activity onCreate 中调用
     */
    fun createPermissionLauncher(
        activity: ComponentActivity,
        onResult: (Boolean) -> Unit
    ): ActivityResultLauncher<String> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            Timber.d("$TAG: 通知权限请求结果: $isGranted")
            onResult(isGranted)
        }
    }
    
    /**
     * 请求通知权限
     * 
     * @param launcher 权限请求启动器
     */
    fun requestPermission(launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Timber.d("$TAG: 请求通知权限")
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Compose 中使用的通知权限状态
 */
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    val hasPermission = remember { mutableStateOf(NotificationPermissionHelper.hasNotificationPermission(context)) }
    
    return remember {
        NotificationPermissionState(
            hasPermission = hasPermission.value,
            shouldRequest = NotificationPermissionHelper.shouldRequestPermission(context)
        )
    }
}

data class NotificationPermissionState(
    val hasPermission: Boolean,
    val shouldRequest: Boolean
)
