package com.soulon.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 同步管理器
 * 
 * 负责：
 * 1. 网络状态监听
 * 2. 离线数据队列管理
 * 3. 自动同步调度
 * 4. 冲突解决
 */
class SyncManager private constructor(private val context: Context) {
    
    private val cloudRepo by lazy { CloudDataRepository.getInstance(context) }
    private val migrationManager by lazy { DataMigrationManager(context) }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 网络状态
    private val _isOnline = MutableStateFlow(true)
    val isOnline = _isOnline.asStateFlow()
    
    // 同步状态
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()
    
    // 待同步数量
    private val _pendingSyncCount = MutableStateFlow(0)
    val pendingSyncCount = _pendingSyncCount.asStateFlow()
    
    // 当前钱包
    private var currentWallet: String? = null
    
    companion object {
        private const val TAG = "SyncManager"
        private const val SYNC_WORK_NAME = "data_sync_work"
        private const val SYNC_INTERVAL_MINUTES = 15L
        
        @Volatile
        private var INSTANCE: SyncManager? = null
        
        fun getInstance(context: Context): SyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    init {
        setupNetworkCallback()
    }
    
    /**
     * 设置网络状态监听
     */
    private fun setupNetworkCallback() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.d("$TAG: 网络已连接")
                _isOnline.value = true
                
                // 网络恢复时自动同步
                scope.launch {
                    delay(1000) // 等待网络稳定
                    syncIfNeeded()
                }
            }
            
            override fun onLost(network: Network) {
                Timber.d("$TAG: 网络已断开")
                _isOnline.value = false
            }
        })
        
        // 初始化网络状态
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        _isOnline.value = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    
    /**
     * 初始化同步（钱包连接时调用）
     */
    suspend fun initialize(walletAddress: String) {
        currentWallet = walletAddress
        
        // 初始化云端仓库
        cloudRepo.initialize(walletAddress)
        
        // 检查是否需要数据迁移
        if (migrationManager.needsMigration()) {
            Timber.i("$TAG: 检测到需要数据迁移")
            // 在后台执行迁移
            scope.launch {
                migrationManager.migrateAll(walletAddress) { progress ->
                    Timber.d("$TAG: 迁移进度: ${(progress * 100).toInt()}%")
                }
            }
        }
        
        // 启动定期同步任务
        schedulePeriodicSync()
        
        // 立即执行一次同步
        syncIfNeeded()
    }
    
    /**
     * 启动定期同步任务
     */
    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val syncRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(
            SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
        
        Timber.i("$TAG: 定期同步任务已启动，间隔 $SYNC_INTERVAL_MINUTES 分钟")
    }
    
    /**
     * 如果需要则同步
     */
    suspend fun syncIfNeeded(): Boolean {
        if (!_isOnline.value) {
            Timber.d("$TAG: 离线状态，跳过同步")
            return false
        }
        
        if (_isSyncing.value) {
            Timber.d("$TAG: 同步进行中，跳过")
            return false
        }
        
        return try {
            _isSyncing.value = true
            val success = cloudRepo.syncIfNeeded()
            Timber.i("$TAG: 同步${if (success) "完成" else "无需更新"}")
            success
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 同步失败")
            false
        } finally {
            _isSyncing.value = false
        }
    }
    
    /**
     * 强制同步
     */
    suspend fun forceSync(): Boolean {
        if (!_isOnline.value) {
            Timber.w("$TAG: 离线状态，无法强制同步")
            return false
        }
        
        return try {
            _isSyncing.value = true
            val success = cloudRepo.syncFullProfile()
            Timber.i("$TAG: 强制同步${if (success) "成功" else "失败"}")
            success
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 强制同步异常")
            false
        } finally {
            _isSyncing.value = false
        }
    }
    
    /**
     * 断开连接（钱包断开时调用）
     */
    fun disconnect() {
        // 取消定期同步
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        
        // 清理状态
        currentWallet = null
        cloudRepo.disconnect()
        
        Timber.i("$TAG: 已断开同步")
    }
    
    /**
     * 获取迁移状态
     */
    fun getMigrationStatus(): DataMigrationManager.MigrationStatus {
        return migrationManager.getMigrationStatus()
    }
    
    /**
     * 检查网络是否可用
     */
    fun checkNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}

/**
 * 数据同步 Worker
 */
class DataSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val syncManager = SyncManager.getInstance(applicationContext)
            
            if (!syncManager.checkNetworkAvailable()) {
                Timber.d("DataSyncWorker: 无网络，重试")
                return Result.retry()
            }
            
            val success = syncManager.forceSync()
            
            if (success) {
                Timber.i("DataSyncWorker: 同步成功")
                Result.success()
            } else {
                Timber.w("DataSyncWorker: 同步失败，重试")
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "DataSyncWorker: 同步异常")
            Result.retry()
        }
    }
}
