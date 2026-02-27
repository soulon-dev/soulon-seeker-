package com.soulon.app.dapp

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.wallet.SolanaRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * App NFT 管理器
 * 
 * 管理 dApp Store 2.0 的 App NFT 版本检查
 * 
 * dApp Store 发布流程使用三层 NFT 结构：
 * 1. Publisher NFT - 代表开发者身份
 * 2. dApp NFT - 代表应用
 * 3. Release NFT - 代表版本
 * 
 * 参考：https://docs.solanamobile.com/dapp-publishing/overview
 * 
 * @property context Android 上下文
 * @property rpcClient Solana RPC 客户端
 */
class AppNftManager(
    private val context: Context,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "AppNftManager"
        
        // App NFT Mint 地址（发布后填入）
        const val APP_NFT_MINT = "MemAiAppNFTxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        
        // Publisher NFT Mint 地址
        const val PUBLISHER_NFT_MINT = "MemAiPubNFTxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        
        // Metaplex Token Metadata Program
        const val METADATA_PROGRAM_ID = "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"
        
        // dApp Store API
        const val DAPP_STORE_API = "https://api.dappstore.solanamobile.com"
        
        // 缓存
        private const val PREFS_NAME = "app_nft_prefs"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_LATEST_CHANGELOG = "latest_changelog"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_LAST_CHECK = "last_check"
        
        // 检查间隔（6 小时）
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // 当前应用版本（使用 @JvmField 避免与 getter 冲突）
    private val appVersion: String by lazy {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
    
    /**
     * 检查是否有新版本
     * 
     * @param forceCheck 强制检查（忽略缓存）
     * @return 更新信息，如果没有新版本返回 null
     */
    suspend fun checkForUpdates(forceCheck: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: 检查应用更新...")
            
            // 检查缓存
            if (!forceCheck) {
                val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
                if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                    return@withContext loadCachedUpdate()
                }
            }
            
            // 查询链上最新版本
            val latestRelease = queryLatestRelease()
            
            if (latestRelease != null && isNewerVersion(latestRelease.version, appVersion)) {
                val updateInfo = UpdateInfo(
                    currentVersion = appVersion,
                    newVersion = latestRelease.version,
                    changelog = latestRelease.changelog,
                    downloadUrl = latestRelease.downloadUrl,
                    releaseDate = latestRelease.releaseDate,
                    isRequired = latestRelease.isRequired
                )
                
                // 缓存结果
                saveCache(updateInfo)
                
                Timber.i("$TAG: 发现新版本: ${latestRelease.version}")
                return@withContext updateInfo
            }
            
            // 更新检查时间
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            
            Timber.i("$TAG: 当前已是最新版本")
            null
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 检查更新失败")
            null
        }
    }
    
    /**
     * 从链上查询最新的 Release NFT
     */
    private suspend fun queryLatestRelease(): ReleaseInfo? {
        return try {
            // 方式 1: 通过 dApp Store API 查询
            val apiResult = queryFromDappStoreApi()
            if (apiResult != null) {
                return apiResult
            }
            
            // 方式 2: 直接从链上查询 Release NFT
            queryFromChain()
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 查询最新版本失败")
            null
        }
    }
    
    /**
     * 通过 dApp Store API 查询
     */
    private fun queryFromDappStoreApi(): ReleaseInfo? {
        return try {
            val request = Request.Builder()
                .url("$DAPP_STORE_API/apps/$APP_NFT_MINT/releases/latest")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Timber.w("$TAG: dApp Store API 请求失败: ${response.code}")
                return null
            }
            
            val responseBody = response.body?.string() ?: return null
            val json = JSONObject(responseBody)
            
            ReleaseInfo(
                version = json.getString("version"),
                changelog = json.optString("changelog", ""),
                downloadUrl = json.getString("download_url"),
                releaseDate = json.optLong("release_date", System.currentTimeMillis()),
                isRequired = json.optBoolean("is_required", false),
                releaseNftMint = json.optString("release_nft_mint", "")
            )
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: dApp Store API 查询失败")
            null
        }
    }
    
    /**
     * 直接从链上查询 Release NFT
     */
    private suspend fun queryFromChain(): ReleaseInfo? {
        return try {
            // 查询所有关联的 Release NFT
            // TODO: 实现链上查询逻辑
            // 需要：
            // 1. 获取 App NFT 的元数据
            // 2. 解析关联的 Release NFT 列表
            // 3. 获取最新的 Release NFT 元数据
            
            // 开发模式：返回模拟数据
            Timber.w("$TAG: 使用模拟数据（开发模式）")
            null
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 链上查询失败")
            null
        }
    }
    
    /**
     * 加载缓存的更新信息
     */
    private fun loadCachedUpdate(): UpdateInfo? {
        val latestVersion = prefs.getString(KEY_LATEST_VERSION, null) ?: return null
        
        if (!isNewerVersion(latestVersion, appVersion)) {
            return null
        }
        
        return UpdateInfo(
            currentVersion = appVersion,
            newVersion = latestVersion,
            changelog = prefs.getString(KEY_LATEST_CHANGELOG, "") ?: "",
            downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, null),
            releaseDate = 0L,
            isRequired = false
        )
    }
    
    /**
     * 保存缓存
     */
    private fun saveCache(updateInfo: UpdateInfo) {
        prefs.edit().apply {
            putString(KEY_LATEST_VERSION, updateInfo.newVersion)
            putString(KEY_LATEST_CHANGELOG, updateInfo.changelog)
            putString(KEY_DOWNLOAD_URL, updateInfo.downloadUrl)
            putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            apply()
        }
    }
    
    /**
     * 比较版本号
     * 
     * @return true 如果 newVersion > currentVersion
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        return try {
            val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            
            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }
                
                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取当前应用版本
     */
    fun getCurrentVersion(): String = appVersion
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        prefs.edit().clear().apply()
    }
}

/**
 * 更新信息
 */
data class UpdateInfo(
    val currentVersion: String,
    val newVersion: String,
    val changelog: String,
    val downloadUrl: String?,
    val releaseDate: Long,
    val isRequired: Boolean
)

/**
 * Release 信息（内部使用）
 */
internal data class ReleaseInfo(
    val version: String,
    val changelog: String,
    val downloadUrl: String,
    val releaseDate: Long,
    val isRequired: Boolean,
    val releaseNftMint: String
)
