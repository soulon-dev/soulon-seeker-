package com.soulon.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.soulon.app.onboarding.OnboardingState
import com.soulon.app.proactive.ProactiveQuestionWorker
import org.bouncycastle.jce.provider.BouncyCastleProvider
import timber.log.Timber
import java.security.Security

/**
 * Soulon 应用程序类
 * 
 * 负责全局初始化：
 */
class SoulonApplication : Application(), Configuration.Provider {
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化 Timber 日志
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        // 初始化 BouncyCastle Security Provider（用于 Ed25519 签名）
        initializeBouncyCastle()
        
        // 初始化主动提问定时任务（仅在问卷完成后启动）
        initializeProactiveQuestions()
        
        Timber.i("✅ Soulon 应用初始化完成")
    }
    
    /**
     * WorkManager 配置
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
    
    /**
     * 初始化奇遇功能
     */
    private fun initializeProactiveQuestions() {
        try {
            // 只有完成问卷后才启动定期任务
            if (OnboardingState.isCompleted(this)) {
                ProactiveQuestionWorker.schedulePeriodicWork(this)
                Timber.i("✅ 奇遇定时任务已启动")
            } else {
                Timber.d("⏳ 问卷未完成，奇遇功能暂未启动")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ 初始化奇遇功能失败")
        }
    }
    
    /**
     * 初始化 BouncyCastle Security Provider
     * 
     * BouncyCastle 提供 Ed25519 签名算法支持
     * 用于 Arweave DataItem 的真实签名
     */
    private fun initializeBouncyCastle() {
        try {
            // 移除可能存在的旧版 BouncyCastle
            Security.removeProvider("BC")
            
            // 添加 BouncyCastle Provider
            Security.addProvider(BouncyCastleProvider())
            
            Timber.i("✅ BouncyCastle Security Provider 已初始化")
            Timber.d("BouncyCastle 版本: ${BouncyCastleProvider.PROVIDER_NAME} ${Security.getProvider("BC").version}")
            
            // 验证 Ed25519 算法可用
            val algorithms = Security.getProvider("BC").services
                .filter { it.algorithm.contains("Ed25519", ignoreCase = true) }
            
            if (algorithms.isEmpty()) {
                Timber.e("❌ Ed25519 算法不可用！")
                throw IllegalStateException("Ed25519 算法不可用")
            }
            
            Timber.d("✅ Ed25519 算法已就绪")
            
        } catch (e: Exception) {
            Timber.e(e, "❌ BouncyCastle 初始化失败")
            throw e
        }
    }
}
