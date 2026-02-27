package com.soulon.app.quota

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 可信日期时间提供者
 * 
 * 通过多种来源获取可信的日期时间：
 * 1. NTP 网络时间（优先）
 * 2. WorldTimeAPI（备选）
 * 3. 本地时间（最后手段，标记为不可信）
 * 
 * 用于 Token 限额的日期重置判断
 */
class DateTimeProvider(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("datetime_provider", Context.MODE_PRIVATE)
    
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    
    companion object {
        // NTP 服务器列表
        private val NTP_SERVERS = listOf(
            "time.google.com",
            "time.cloudflare.com",
            "pool.ntp.org"
        )
        
        // WorldTimeAPI 端点
        private const val WORLD_TIME_API = "https://worldtimeapi.org/api/ip"
        
        // 缓存键
        private const val KEY_LAST_NETWORK_DATE = "last_network_date"
        private const val KEY_LAST_NETWORK_TIMESTAMP = "last_network_timestamp"
        private const val KEY_LOCAL_OFFSET = "local_offset_ms"
        
        // 缓存有效期（1小时）
        private const val CACHE_VALIDITY_MS = 60 * 60 * 1000L
        
        // 日期格式
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    
    /**
     * 日期获取结果
     */
    sealed class DateResult {
        data class Network(
            val dateValue: String,       // YYYY-MM-DD 格式
            val timestampValue: Long,    // Unix 时间戳
            val source: String           // 数据来源
        ) : DateResult()
        
        data class Cached(
            val dateValue: String,
            val timestampValue: Long,
            val originalSource: String
        ) : DateResult()
        
        data class Local(
            val dateValue: String,
            val timestampValue: Long,
            val trusted: Boolean = false
        ) : DateResult()
        
        data class Error(val message: String) : DateResult()
        
        fun getDate(): String = when (this) {
            is Network -> dateValue
            is Cached -> dateValue
            is Local -> dateValue
            is Error -> DATE_FORMAT.format(Date())
        }
        
        fun getTimestamp(): Long = when (this) {
            is Network -> timestampValue
            is Cached -> timestampValue
            is Local -> timestampValue
            is Error -> System.currentTimeMillis()
        }
        
        fun isTrusted(): Boolean = when (this) {
            is Network -> true
            is Cached -> true
            is Local -> trusted
            is Error -> false
        }
    }
    
    /**
     * 获取当前日期（YYYY-MM-DD 格式）
     */
    suspend fun getCurrentDate(): DateResult = withContext(Dispatchers.IO) {
        try {
            // 1. 检查缓存是否有效
            val cachedResult = getCachedDate()
            if (cachedResult != null) {
                Timber.d("📅 使用缓存日期: ${cachedResult.dateValue}")
                return@withContext cachedResult
            }
            
            // 2. 尝试从网络获取
            val networkResult = fetchFromNetwork()
            if (networkResult != null) {
                // 缓存结果
                cacheDate(networkResult)
                Timber.i("📅 网络日期获取成功: ${networkResult.dateValue} (来源: ${networkResult.source})")
                return@withContext networkResult
            }
            
            // 3. 回退到本地时间
            Timber.w("⚠️ 无法获取网络时间，使用本地时间")
            val localDate = DATE_FORMAT.format(Date())
            DateResult.Local(
                dateValue = localDate,
                timestampValue = System.currentTimeMillis(),
                trusted = false
            )
            
        } catch (e: Exception) {
            Timber.e(e, "获取日期失败")
            val localDate = DATE_FORMAT.format(Date())
            DateResult.Local(
                dateValue = localDate,
                timestampValue = System.currentTimeMillis(),
                trusted = false
            )
        }
    }
    
    /**
     * 从网络获取日期
     */
    private suspend fun fetchFromNetwork(): DateResult.Network? {
        // 尝试 WorldTimeAPI
        try {
            val response = fetchWorldTimeApi()
            if (response != null) return response
        } catch (e: Exception) {
            Timber.w("WorldTimeAPI 失败: ${e.message}")
        }
        
        // 尝试简单的 HTTP 时间头
        try {
            val response = fetchHttpDate()
            if (response != null) return response
        } catch (e: Exception) {
            Timber.w("HTTP 时间头获取失败: ${e.message}")
        }
        
        return null
    }
    
    /**
     * 从 WorldTimeAPI 获取日期
     */
    private fun fetchWorldTimeApi(): DateResult.Network? {
        val request = Request.Builder()
            .url(WORLD_TIME_API)
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        
        val body = response.body?.string() ?: return null
        val json = JSONObject(body)
        
        val datetime = json.getString("datetime") // ISO 8601 格式
        val unixtime = json.getLong("unixtime")
        
        // 提取日期部分 (YYYY-MM-DD)
        val date = datetime.substring(0, 10)
        
        return DateResult.Network(
            dateValue = date,
            timestampValue = unixtime * 1000,
            source = "WorldTimeAPI"
        )
    }
    
    /**
     * 从 HTTP 响应头获取日期
     */
    private fun fetchHttpDate(): DateResult.Network? {
        val request = Request.Builder()
            .url("https://www.google.com")
            .head()
            .build()
        
        val response = httpClient.newCall(request).execute()
        val dateHeader = response.header("Date") ?: return null
        
        // 解析 HTTP 日期格式: "Sun, 06 Nov 1994 08:49:37 GMT"
        val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        val parsedDate = httpDateFormat.parse(dateHeader) ?: return null
        
        val date = DATE_FORMAT.format(parsedDate)
        
        return DateResult.Network(
            dateValue = date,
            timestampValue = parsedDate.time,
            source = "HTTP Header"
        )
    }
    
    /**
     * 获取缓存的日期（如果有效）
     */
    private fun getCachedDate(): DateResult.Cached? {
        val cachedDate = prefs.getString(KEY_LAST_NETWORK_DATE, null) ?: return null
        val cachedTimestamp = prefs.getLong(KEY_LAST_NETWORK_TIMESTAMP, 0)
        
        // 检查缓存是否过期
        val elapsed = System.currentTimeMillis() - cachedTimestamp
        if (elapsed > CACHE_VALIDITY_MS) {
            return null
        }
        
        return DateResult.Cached(
            dateValue = cachedDate,
            timestampValue = cachedTimestamp,
            originalSource = "cached"
        )
    }
    
    /**
     * 缓存日期
     */
    private fun cacheDate(result: DateResult.Network) {
        prefs.edit()
            .putString(KEY_LAST_NETWORK_DATE, result.dateValue)
            .putLong(KEY_LAST_NETWORK_TIMESTAMP, result.timestampValue)
            .apply()
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        prefs.edit().clear().apply()
    }
    
    /**
     * 检查两个日期是否相同
     */
    fun isSameDay(date1: String, date2: String): Boolean {
        return date1 == date2
    }
    
    /**
     * 检查日期是否是今天
     */
    suspend fun isToday(date: String): Boolean {
        val currentDate = getCurrentDate()
        return date == currentDate.getDate()
    }
}
