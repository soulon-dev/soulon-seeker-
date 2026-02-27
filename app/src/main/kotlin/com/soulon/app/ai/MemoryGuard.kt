package com.soulon.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import timber.log.Timber

/**
 * 内存哨兵
 * 
 * 功能：
 * - 监控系统 RAM
 * - 自动释放模型
 * - 防止 OOM
 * 
 * Phase 3 Week 1: Task_Memory_Guard
 */
class MemoryGuard(private val context: Context) {
    
    companion object {
        // 内存阈值（GB）
        private const val MIN_FREE_MEMORY_GB = 1.0
        
        // Qwen-1.8B Int4 模型约 800MB
        private const val MODEL_SIZE_MB = 800
        
        // 推理运行时内存峰值约 1.5GB
        private const val INFERENCE_OVERHEAD_MB = 700
        
        // 监控间隔
        private const val CHECK_INTERVAL_MS = 5000L
        
        // 转换单位
        private const val MB_TO_GB = 1024.0
        private const val BYTES_TO_MB = 1024.0 * 1024.0
    }
    
    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    
    /**
     * 检查是否可以加载模型
     * 
     * 评估标准：
     * - 可用内存 >= MIN_FREE_MEMORY_GB + MODEL_SIZE + INFERENCE_OVERHEAD
     * - 系统未处于低内存状态
     * 
     * @return true 如果内存充足，可以加载模型
     */
    fun canLoadModel(): Boolean {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val freeMemoryMB = memInfo.availMem / BYTES_TO_MB
        val freeMemoryGB = freeMemoryMB / MB_TO_GB
        val requiredMB = MODEL_SIZE_MB + INFERENCE_OVERHEAD_MB
        val requiredGB = requiredMB / MB_TO_GB
        
        val hasEnoughMemory = freeMemoryMB >= requiredMB
        val isSystemHealthy = !memInfo.lowMemory
        val canLoad = hasEnoughMemory && isSystemHealthy
        
        Timber.d("""
            内存检查 - 模型加载评估:
              可用内存: ${freeMemoryMB.toInt()} MB (${String.format("%.2f", freeMemoryGB)} GB)
              需要内存: ${requiredMB} MB (${String.format("%.2f", requiredGB)} GB)
              系统状态: ${if (isSystemHealthy) "正常" else "低内存警告"}
              评估结果: ${if (canLoad) "✅ 可以加载" else "❌ 内存不足"}
        """.trimIndent())
        
        if (!canLoad) {
            Timber.w("""
                ⚠️ 无法加载模型:
                  - 可用内存不足: ${freeMemoryMB.toInt()} MB < ${requiredMB} MB
                  - 建议用户关闭后台应用释放内存
            """.trimIndent())
        }
        
        return canLoad
    }
    
    /**
     * 检查内存是否不足（推理过程中调用）
     * 
     * 用于在推理过程中持续监控，如果内存不足则中止生成。
     * 
     * @return true 如果内存不足，需要中止推理
     */
    fun isMemoryLow(): Boolean {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val freeMemoryGB = memInfo.availMem / (BYTES_TO_MB * MB_TO_GB)
        val isLow = freeMemoryGB < MIN_FREE_MEMORY_GB || memInfo.lowMemory
        
        if (isLow) {
            Timber.w("""
                ⚠️ 内存不足警告:
                  可用内存: ${String.format("%.2f", freeMemoryGB)} GB
                  阈值: ${MIN_FREE_MEMORY_GB} GB
                  系统标志: ${if (memInfo.lowMemory) "低内存" else "正常"}
            """.trimIndent())
        }
        
        return isLow
    }
    
    /**
     * 获取当前内存使用情况（用于性能监控和调试）
     * 
     * @return 详细的内存统计数据
     */
    fun getMemoryStats(): MemoryStats {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / BYTES_TO_MB
        val appHeapMB = Runtime.getRuntime().totalMemory() / BYTES_TO_MB
        val freeHeapMB = Runtime.getRuntime().freeMemory() / BYTES_TO_MB
        val usedHeapMB = appHeapMB - freeHeapMB
        
        return MemoryStats(
            totalSystemMemoryMB = memInfo.totalMem / BYTES_TO_MB,
            availableSystemMemoryMB = memInfo.availMem / BYTES_TO_MB,
            nativeHeapMB = nativeHeapMB,
            appHeapMB = appHeapMB,
            usedHeapMB = usedHeapMB,
            freeHeapMB = freeHeapMB,
            isLowMemory = memInfo.lowMemory
        )
    }
    
    /**
     * 打印详细的内存报告（用于调试和性能分析）
     */
    fun logMemoryReport() {
        val stats = getMemoryStats()
        
        Timber.i("""
            ═══════════════════════════════════════════════
            📊 内存使用报告
            ═══════════════════════════════════════════════
            
            系统内存:
              • 总计: ${stats.totalSystemMemoryMB.toInt()} MB
              • 可用: ${stats.availableSystemMemoryMB.toInt()} MB
              • 使用率: ${String.format("%.1f", (1 - stats.availableSystemMemoryMB / stats.totalSystemMemoryMB) * 100)}%
              • 状态: ${if (stats.isLowMemory) "⚠️ 低内存" else "✅ 正常"}
            
            应用内存:
              • Native Heap: ${stats.nativeHeapMB.toInt()} MB
              • App Heap 总计: ${stats.appHeapMB.toInt()} MB
              • App Heap 已用: ${stats.usedHeapMB.toInt()} MB
              • App Heap 空闲: ${stats.freeHeapMB.toInt()} MB
            
            模型加载能力:
              • 需要内存: ${MODEL_SIZE_MB + INFERENCE_OVERHEAD_MB} MB
              • 可以加载: ${if (canLoadModel()) "✅ 是" else "❌ 否"}
            
            ═══════════════════════════════════════════════
        """.trimIndent())
    }
    
    /**
     * 估算当前可以容纳的最大模型大小（MB）
     */
    fun estimateMaxModelSize(): Int {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val freeMemoryMB = memInfo.availMem / BYTES_TO_MB
        val reservedMB = MIN_FREE_MEMORY_GB * MB_TO_GB + INFERENCE_OVERHEAD_MB
        val maxModelSizeMB = (freeMemoryMB - reservedMB).toInt()
        
        return maxModelSizeMB.coerceAtLeast(0)
    }
}

/**
 * 内存统计数据
 */
data class MemoryStats(
    /** 系统总内存（MB） */
    val totalSystemMemoryMB: Double,
    
    /** 系统可用内存（MB） */
    val availableSystemMemoryMB: Double,
    
    /** Native Heap 使用量（MB） */
    val nativeHeapMB: Double,
    
    /** 应用 Heap 总量（MB） */
    val appHeapMB: Double,
    
    /** 应用 Heap 已用（MB） */
    val usedHeapMB: Double,
    
    /** 应用 Heap 空闲（MB） */
    val freeHeapMB: Double,
    
    /** 系统是否处于低内存状态 */
    val isLowMemory: Boolean
) {
    /**
     * 格式化输出为人类可读的字符串
     */
    fun toReadableString(): String {
        return """
            系统: ${availableSystemMemoryMB.toInt()}/${totalSystemMemoryMB.toInt()} MB 可用
            应用: ${usedHeapMB.toInt()}/${appHeapMB.toInt()} MB 已用
            状态: ${if (isLowMemory) "低内存" else "正常"}
        """.trimIndent()
    }
}
