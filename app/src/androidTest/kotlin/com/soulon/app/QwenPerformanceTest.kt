package com.soulon.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soulon.app.ai.MemoryGuard
import com.soulon.app.ai.QwenManager
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import kotlin.system.measureTimeMillis

/**
 * Qwen 性能基准测试
 * 
 * Phase 3 Week 1: 性能验证
 * 
 * 在 Seeker 真机上运行此测试，验证 Qwen 性能是否达标。
 * 
 * 运行方法:
 * ```bash
 * ./gradlew connectedAndroidTest
 * ```
 * 
 * 或在 Android Studio 中右键此文件 → Run 'QwenPerformanceTest'
 */
@RunWith(AndroidJUnit4::class)
class QwenPerformanceTest {
    
    private lateinit var context: Context
    private lateinit var memoryGuard: MemoryGuard
    private lateinit var qwenManager: QwenManager
    
    companion object {
        // 性能目标
        private const val TARGET_INIT_TIME_MS = 10000L // 10 秒
        private const val TARGET_FIRST_TOKEN_MS = 1000L // 1 秒
        private const val TARGET_TOKENS_PER_SECOND = 8.0 // 8 tokens/s
        private const val TARGET_MEMORY_MB = 2048 // 2GB
        
        // 测试参数
        private const val TEST_PROMPT = "请介绍一下你自己"
        private const val TEST_MAX_TOKENS = 100
    }
    
    @Before
    fun setup() {
        // 初始化 Timber（用于日志输出）
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        
        context = InstrumentationRegistry.getInstrumentation().targetContext
        memoryGuard = MemoryGuard(context)
        qwenManager = QwenManager(context, memoryGuard)
        
        Timber.i("════════════════════════════════════════")
        Timber.i("🧪 Qwen 性能基准测试")
        Timber.i("════════════════════════════════════════")
    }
    
    @After
    fun tearDown() {
        qwenManager.release()
        Timber.i("════════════════════════════════════════")
        Timber.i("✅ 测试完成，资源已释放")
        Timber.i("════════════════════════════════════════")
    }
    
    /**
     * 测试 1: 内存检查
     * 
     * 验证设备是否有足够的内存加载模型。
     */
    @Test
    fun test01_MemoryCheck() {
        Timber.i("测试 1: 内存检查")
        Timber.i("─────────────────────────────────────────")
        
        memoryGuard.logMemoryReport()
        
        val canLoad = memoryGuard.canLoadModel()
        val maxModelSize = memoryGuard.estimateMaxModelSize()
        
        Timber.i("结果:")
        Timber.i("  • 可以加载模型: ${if (canLoad) "✅ 是" else "❌ 否"}")
        Timber.i("  • 最大模型大小: $maxModelSize MB")
        
        assertTrue(
            "❌ 内存不足，无法加载 Qwen 模型（需要约 1500MB）",
            canLoad
        )
        
        Timber.i("✅ 测试通过: 内存充足")
    }
    
    /**
     * 测试 2: 模型初始化时间
     * 
     * 验证模型加载时间是否在可接受范围内。
     */
    @Test
    fun test02_ModelInitializationTime() = runBlocking {
        Timber.i("测试 2: 模型初始化时间")
        Timber.i("─────────────────────────────────────────")
        
        val initTime = measureTimeMillis {
            val success = qwenManager.initialize()
            assertTrue("❌ 模型初始化失败", success)
        }
        
        Timber.i("结果:")
        Timber.i("  • 初始化耗时: $initTime ms")
        Timber.i("  • 目标: <$TARGET_INIT_TIME_MS ms")
        Timber.i("  • 评估: ${if (initTime < TARGET_INIT_TIME_MS) "✅ 达标" else "⚠️ 超出预期"}")
        
        // 初始化时间允许超标（不是致命问题）
        if (initTime >= TARGET_INIT_TIME_MS) {
            Timber.w("⚠️ 初始化时间超出目标，但不影响使用")
        }
        
        assertTrue("❌ 模型未加载", qwenManager.isModelLoaded())
        Timber.i("✅ 测试通过: 模型初始化成功")
    }
    
    /**
     * 测试 3: 首 Token 延迟（关键指标）
     * 
     * 验证从开始推理到生成第一个 Token 的时间。
     * 这是最重要的用户体验指标。
     */
    @Test
    fun test03_FirstTokenLatency() = runBlocking {
        Timber.i("测试 3: 首 Token 延迟")
        Timber.i("─────────────────────────────────────────")
        
        // 初始化模型
        val initSuccess = qwenManager.initialize()
        assertTrue("❌ 模型初始化失败", initSuccess)
        
        // 预热模型
        Timber.i("预热模型...")
        qwenManager.warmUp()
        
        // 测试首 Token 延迟
        Timber.i("开始测试...")
        var firstTokenTime = 0L
        var firstTokenReceived = false
        
        val totalTime = measureTimeMillis {
            qwenManager.generateStream(
                prompt = TEST_PROMPT,
                maxNewTokens = 1 // 只生成 1 个 Token
            ).collect { token ->
                if (!firstTokenReceived) {
                    firstTokenReceived = true
                    firstTokenTime = System.currentTimeMillis()
                }
            }
        }
        
        // 首 Token 延迟 = 总时间（因为只生成 1 个 Token）
        val latency = totalTime
        
        Timber.i("结果:")
        Timber.i("  • 首 Token 延迟: $latency ms")
        Timber.i("  • 目标: <$TARGET_FIRST_TOKEN_MS ms")
        
        when {
            latency < TARGET_FIRST_TOKEN_MS -> {
                Timber.i("  • 评估: ⭐⭐⭐⭐⭐ 优秀")
            }
            latency < TARGET_FIRST_TOKEN_MS * 2 -> {
                Timber.i("  • 评估: ⭐⭐⭐⭐☆ 良好")
            }
            latency < TARGET_FIRST_TOKEN_MS * 3 -> {
                Timber.i("  • 评估: ⭐⭐⭐☆☆ 可接受")
            }
            else -> {
                Timber.w("  • 评估: ⭐⭐☆☆☆ 不达标")
            }
        }
        
        // 断言：首 Token 延迟必须 <3 秒（绝对底线）
        assertTrue(
            "❌ 首 Token 延迟过高: $latency ms > ${TARGET_FIRST_TOKEN_MS * 3} ms",
            latency < TARGET_FIRST_TOKEN_MS * 3
        )
        
        Timber.i("✅ 测试通过: 首 Token 延迟可接受")
    }
    
    /**
     * 测试 4: Token 生成速度（关键指标）
     * 
     * 验证持续生成 Token 的速度。
     * 影响流式输出的流畅度。
     */
    @Test
    fun test04_TokenGenerationSpeed() = runBlocking {
        Timber.i("测试 4: Token 生成速度")
        Timber.i("─────────────────────────────────────────")
        
        // 初始化模型
        val initSuccess = qwenManager.initialize()
        assertTrue("❌ 模型初始化失败", initSuccess)
        
        // 预热模型
        qwenManager.warmUp()
        
        // 测试生成速度
        val tokens = mutableListOf<String>()
        
        val totalTime = measureTimeMillis {
            qwenManager.generateStream(
                prompt = TEST_PROMPT,
                maxNewTokens = TEST_MAX_TOKENS
            ).collect { token ->
                tokens.add(token)
            }
        }
        
        val tokensPerSecond = if (totalTime > 0) {
            tokens.size / (totalTime / 1000.0)
        } else {
            0.0
        }
        
        Timber.i("结果:")
        Timber.i("  • 生成 Token 数: ${tokens.size}")
        Timber.i("  • 总耗时: $totalTime ms")
        Timber.i("  • 生成速度: ${String.format("%.2f", tokensPerSecond)} tokens/s")
        Timber.i("  • 目标: >$TARGET_TOKENS_PER_SECOND tokens/s")
        
        when {
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND * 1.5 -> {
                Timber.i("  • 评估: ⭐⭐⭐⭐⭐ 优秀")
            }
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND -> {
                Timber.i("  • 评估: ⭐⭐⭐⭐☆ 良好")
            }
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND * 0.7 -> {
                Timber.i("  • 评估: ⭐⭐⭐☆☆ 可接受")
            }
            else -> {
                Timber.w("  • 评估: ⭐⭐☆☆☆ 不达标")
            }
        }
        
        // 断言：生成速度必须 >5 tokens/s（绝对底线）
        assertTrue(
            "❌ Token 生成速度过慢: ${String.format("%.2f", tokensPerSecond)} tokens/s < 5 tokens/s",
            tokensPerSecond >= 5.0
        )
        
        Timber.i("✅ 测试通过: Token 生成速度可接受")
    }
    
    /**
     * 测试 5: 内存峰值
     * 
     * 验证推理过程中的内存占用。
     */
    @Test
    fun test05_MemoryUsage() = runBlocking {
        Timber.i("测试 5: 内存峰值")
        Timber.i("─────────────────────────────────────────")
        
        // 记录初始内存
        val statsBeforeInit = memoryGuard.getMemoryStats()
        Timber.i("初始内存: ${statsBeforeInit.toReadableString()}")
        
        // 初始化模型
        qwenManager.initialize()
        
        // 记录加载后内存
        val statsAfterInit = memoryGuard.getMemoryStats()
        Timber.i("加载后内存: ${statsAfterInit.toReadableString()}")
        
        // 执行推理
        qwenManager.generateStream(
            prompt = TEST_PROMPT,
            maxNewTokens = TEST_MAX_TOKENS
        ).collect { }
        
        // 记录推理后内存
        val statsAfterInference = memoryGuard.getMemoryStats()
        Timber.i("推理后内存: ${statsAfterInference.toReadableString()}")
        
        // 计算内存增长
        val memoryIncreaseAfterInit = statsBeforeInit.availableSystemMemoryMB - statsAfterInit.availableSystemMemoryMB
        val memoryIncreaseAfterInference = statsBeforeInit.availableSystemMemoryMB - statsAfterInference.availableSystemMemoryMB
        
        Timber.i("结果:")
        Timber.i("  • 加载模型后内存增长: ${memoryIncreaseAfterInit.toInt()} MB")
        Timber.i("  • 推理后内存增长: ${memoryIncreaseAfterInference.toInt()} MB")
        Timber.i("  • 目标: <$TARGET_MEMORY_MB MB")
        
        // 断言：内存增长不应超过目标
        assertTrue(
            "❌ 内存占用过高: ${memoryIncreaseAfterInference.toInt()} MB > $TARGET_MEMORY_MB MB",
            memoryIncreaseAfterInference < TARGET_MEMORY_MB
        )
        
        Timber.i("✅ 测试通过: 内存占用可接受")
    }
    
    /**
     * 测试 6: 综合性能报告
     * 
     * 生成完整的性能报告，用于决策。
     */
    @Test
    fun test06_ComprehensivePerformanceReport() = runBlocking {
        Timber.i("测试 6: 综合性能报告")
        Timber.i("─────────────────────────────────────────")
        
        // 初始化
        val initTime = measureTimeMillis {
            qwenManager.initialize()
        }
        
        // 预热
        qwenManager.warmUp()
        
        // 测试首 Token 延迟
        var firstTokenLatency = 0L
        measureTimeMillis {
            qwenManager.generateStream(TEST_PROMPT, maxNewTokens = 1).collect { }
        }.also { firstTokenLatency = it }
        
        // 测试生成速度
        val tokens = mutableListOf<String>()
        val generationTime = measureTimeMillis {
            qwenManager.generateStream(TEST_PROMPT, maxNewTokens = TEST_MAX_TOKENS)
                .collect { tokens.add(it) }
        }
        val tokensPerSecond = tokens.size / (generationTime / 1000.0)
        
        // 内存统计
        val memoryStats = memoryGuard.getMemoryStats()
        
        // 生成报告
        Timber.i("════════════════════════════════════════")
        Timber.i("📊 Qwen 性能报告（Seeker 2026）")
        Timber.i("════════════════════════════════════════")
        Timber.i("")
        Timber.i("⏱️  性能指标:")
        Timber.i("  • 初始化时间: $initTime ms")
        Timber.i("  • 首 Token 延迟: $firstTokenLatency ms")
        Timber.i("  • Token 生成速度: ${String.format("%.2f", tokensPerSecond)} tokens/s")
        Timber.i("")
        Timber.i("💾 内存指标:")
        Timber.i("  • 系统可用内存: ${memoryStats.availableSystemMemoryMB.toInt()} MB")
        Timber.i("  • 应用 Heap 使用: ${memoryStats.usedHeapMB.toInt()} MB")
        Timber.i("  • Native Heap: ${memoryStats.nativeHeapMB.toInt()} MB")
        Timber.i("")
        Timber.i("🎯 目标对比:")
        Timber.i("  • 首 Token 延迟: ${firstTokenLatency} ms / $TARGET_FIRST_TOKEN_MS ms (${if (firstTokenLatency < TARGET_FIRST_TOKEN_MS) "✅" else "⚠️"})")
        Timber.i("  • 生成速度: ${String.format("%.2f", tokensPerSecond)} tokens/s / $TARGET_TOKENS_PER_SECOND tokens/s (${if (tokensPerSecond >= TARGET_TOKENS_PER_SECOND) "✅" else "⚠️"})")
        Timber.i("")
        Timber.i("📈 最终评估:")
        
        val overallScore = calculateOverallScore(firstTokenLatency, tokensPerSecond)
        
        when {
            overallScore >= 4.5 -> {
                Timber.i("  ⭐⭐⭐⭐⭐ 优秀 - 继续完整方案 A")
            }
            overallScore >= 3.5 -> {
                Timber.i("  ⭐⭐⭐⭐☆ 良好 - 继续完整方案 A")
            }
            overallScore >= 2.5 -> {
                Timber.i("  ⭐⭐⭐☆☆ 可接受 - 尝试优化或考虑方案 B")
            }
            overallScore >= 1.5 -> {
                Timber.w("  ⭐⭐☆☆☆ 不达标 - 考虑方案 B（混合模式）")
            }
            else -> {
                Timber.e("  ⭐☆☆☆☆ 不可用 - 转向方案 C（云端）或 D（无 AI）")
            }
        }
        
        Timber.i("")
        Timber.i("════════════════════════════════════════")
        
        // 根据评分给出建议
        if (overallScore < 3.0) {
            fail("❌ 性能不达标，建议转向备选方案")
        }
    }
    
    /**
     * 计算综合评分（0-5 分）
     */
    private fun calculateOverallScore(firstTokenLatency: Long, tokensPerSecond: Double): Double {
        // 首 Token 延迟评分（权重 50%）
        val latencyScore = when {
            firstTokenLatency < TARGET_FIRST_TOKEN_MS -> 5.0
            firstTokenLatency < TARGET_FIRST_TOKEN_MS * 1.5 -> 4.0
            firstTokenLatency < TARGET_FIRST_TOKEN_MS * 2 -> 3.0
            firstTokenLatency < TARGET_FIRST_TOKEN_MS * 3 -> 2.0
            else -> 1.0
        }
        
        // 生成速度评分（权重 50%）
        val speedScore = when {
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND * 1.5 -> 5.0
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND -> 4.0
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND * 0.7 -> 3.0
            tokensPerSecond >= 5.0 -> 2.0
            else -> 1.0
        }
        
        return (latencyScore * 0.5 + speedScore * 0.5)
    }
}
