package com.soulon.app.support

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DeviceInfoCollector {
    fun collect(context: Context, appVersion: String, now: Long = System.currentTimeMillis()): String {
        val res = context.resources
        val dm = res.displayMetrics
        val cfg = res.configuration

        val localeTag = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cfg.locales.get(0)?.toLanguageTag()
            } else {
                @Suppress("DEPRECATION")
                cfg.locale?.toLanguageTag()
            }
        }.getOrNull().orEmpty()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { info -> am?.getMemoryInfo(info) }

        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        val totalBytes = stat?.totalBytes ?: -1L
        val freeBytes = stat?.availableBytes ?: -1L

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val networkType = caps?.let { networkSummary(it) }.orEmpty()

        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))

        return buildString {
            appendLine("App: $appVersion")
            appendLine("Package: ${context.packageName}")
            appendLine("Time: $timeText")
            appendLine("Timezone: ${TimeZone.getDefault().id}")
            if (localeTag.isNotBlank()) appendLine("Locale: $localeTag")
            if (networkType.isNotBlank()) appendLine("Network: $networkType")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("Hardware: ${Build.HARDWARE}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                appendLine("SecurityPatch: ${Build.VERSION.SECURITY_PATCH}")
            }
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Screen: ${dm.widthPixels}x${dm.heightPixels} px, density=${"%.2f".format(dm.density)}")
            appendLine("Memory: avail=${formatBytes(mi.availMem)}, total=${formatBytes(mi.totalMem)}, low=${mi.lowMemory}")
            if (totalBytes > 0 && freeBytes >= 0) {
                appendLine("Storage: free=${formatBytes(freeBytes)}, total=${formatBytes(totalBytes)}")
            }
        }
    }

    private fun networkSummary(caps: NetworkCapabilities): String {
        val parts = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts.add("WIFI")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts.add("CELLULAR")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts.add("ETHERNET")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts.add("VPN")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) parts.add("BT")
        if (parts.isEmpty()) parts.add("UNKNOWN")
        return parts.joinToString("+")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "n/a"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
            else -> String.format(Locale.US, "%.2f KB", bytes / kb)
        }
    }
}
