package com.soulon.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soulon.app.ai.MemoryGuard
import com.soulon.app.ai.QwenCloudManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import timber.log.Timber

/**
 * Qwen 云 API 性能基准测试
 * 
 * 测试目标：
 * 1. 验证云 API 能在 Seeker 上成功调用
 * 2. 测量关键性能指标
 * 3. 确保满足产品要求
 * 
 * Phase 3 Week 1: Task_Qwen_Init 验证（云 API 版本）
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QwenCloudPerformanceTest {
    
    private lateinit var context: Context
    private lateinit var memoryGuard: MemoryGuard
    private lateinit var qwenManager: QwenCloudManager
    
    companion object {
        // 云 API 性能目标
        private const val TARGET_INIT_TIME_MS = 1000L // 1 秒
        private const val TARGET_FIRST_TOKEN_MS = 1000L // 1 秒
        private const val TARGET_TOKENS_PER_SECOND = 30.0 // 30 tokens/s（云端更快）
        private const val TARGET_MEMORY_MB = 100 // 100MB（云 API 几乎不占内存）
        
        // 测试参数
        private const val TEST_PROMPT = "请介绍一下你自己"
        private const val TEST_MAX_TOKENS = 100
    }
    
    @Before
    fun setup() {
        // 初始化 Timber
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        
        context = InstrumentationRegistry.getInstrumentation().targetContext
        memoryGuard = MemoryGuard(context)
        
        // 使用 BuildConfig 中的 API Key
        qwenManager = QwenCloudManager(context, BuildConfig.QWEN_API_KEY)
        
        Timber.i("════════════════════════════════════════")
        Timber.i("🧪 Qwen 云 API 性能测试开始")
        Timber.i("════════════════════════════════════════")
    }
    
    @After
    fun tearDown() {
        Timber.i("════════════════════════════════════════")
        Timber.i("🧪 测试清理...")
        Timber.i("════════════════════════════════════════")
        
        runBlocking {
            qwenManager.release()
        }
    }
    
    /**
     * Test 01: 内存检查
     */
    @Test
    fun test01_MemoryCheck() {
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Timber.i("🧪 Test 01: 内存检查")
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        memoryGuard.logMemoryReport()
        
        val stats = memoryGuard.getMemoryStats()
        Timber.i("可用内存: ${stats.availableSystemMemoryMB} MB")
        Timber.i("总内存: ${stats.totalSystemMemoryMB} MB")
        Timber.i("已用内存: ${stats.usedHeapMB} MB")
        
        // 云 API 几乎不需要加载内存
        assertTrue("内存应该充足（云 API 无需大量内存）", stats.availableSystemMemoryMB > 100)
        
        Timber.i("✅ Test 01 通过")
    }
    
    /**
     * Test 02: API 初始化时间
     */
    @Test
    fun test02_ApiInitializationTime() {
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Timber.i("🧪 Test 02: API 初始化时间")
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        var initSuccess = false
        var initTimeMs = 0L
        
        runBlocking {
            val startTime = System.currentTimeMillis()
            initSuccess = qwenManager.initialize()
            initTimeMs = System.currentTimeMillis() - startTime
        }
        
        Timber.i("初始化结果: ${if (initSuccess) "成功" else "失败"}")
        Timber.i("初始化耗时: ${initTimeMs} ms")
        Timber.i("目标时间: < $TARGET_INIT_TIME_MS ms")
        
        assertTrue("❌ API 初始化失败", initSuccess)
        assertTrue(
            "初始化时间 (${initTimeMs}ms) 超过目标 (${TARGET_INIT_TIME_MS}ms)",
            initTimeMs < TARGET_INIT_TIME_MS
        )
        
        Timber.i("✅ Test 02 通过 - 初始化时间: ${initTimeMs}ms")
    }
    
    /**
     * Test 03: 首 Token 延迟
     */
    @Test
    fun test03_FirstTokenLatency() {
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Timber.i("🧪 Test 03: 首 Token 延迟")
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // 先初始化
        runBlocking {
            val initSuccess = qwenManager.initialize()
            assertTrue("❌ API 初始化失败", initSuccess)
        }
        
        var firstTokenTime = 0L
        var receivedFirstToken = false
        
        runBlocking {
            val startTime = System.currentTimeMillis()
            
            qwenManager.generateStream(
                prompt = TEST_PROMPT,
                maxNewTokens = 10
            ).collect { token ->
                if (!receivedFirstToken) {
                    firstTokenTime = System.currentTimeMillis() - startTime
                    receivedFirstToken = true
                    Timber.i("⏱️ 首 Token 延迟: ${firstTokenTime} ms")
                }
            }
        }
        
        assertTrue("未收到首 Token", receivedFirstToken)
        assertTrue(
            "首 Token 延迟 (${firstTokenTime}ms) 超过目标 (${TARGET_FIRST_TOKEN_MS}ms)",
            firstTokenTime < TARGET_FIRST_TOKEN_MS
        )
        
        Timber.i("✅ Test 03 通过 - 首 Token 延迟: ${firstTokenTime}ms")
    }
    
    /**
     * Test 04: Token 生成速度
     */
    @Test
    fun test04_TokenGenerationSpeed() {
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Timber.i("🧪 Test 04: Token 生成速度")
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // 先初始化
        runBlocking {
            val initSuccess = qwenManager.initialize()
            assertTrue("❌ API 初始化失败", initSuccess)
        }
        
        var tokenCount = 0
        var totalTimeMs = 0L
        
        runBlocking {
            val startTime = System.currentTimeMillis()
            
            qwenManager.generateStream(
                prompt = TEST_PROMPT,
                maxNewTokens = TEST_MAX_TOKENS
            ).collect { _ ->
                tokenCount++
            }
            
            totalTimeMs = System.currentTimeMillis() - startTime
        }
        
        val tokensPerSecond = if (totalTimeMs > 0) {
            (tokenCount / (totalTimeMs / 1000.0))
        } else {
            0.0
        }
        
        Timber.i("生成 Token 数: $tokenCount")
        Timber.i("总耗时: ${totalTimeMs} ms")
        Timber.i("生成速度: ${"%.2f".format(tokensPerSecond)} tokens/s")
        Timber.i("目标速度: > $TARGET_TOKENS_PER_SECOND tokens/s")
        
        assertTrue(
            "生成速度 (${"%.2f".format(tokensPerSecond)} tokens/s) 低于目标 ($TARGET_TOKENS_PER_SECOND tokens/s)",
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND
        )
        
        Timber.i("✅ Test 04 通过 - 生成速度: ${"%.2f".format(tokensPerSecond)} tokens/s")
    }
    
    /**
     * Test 05: 内存占用
     */
    @Test
    fun test05_MemoryUsage() {
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Timber.i("🧪 Test 05: 内存占用")
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        val memoryBefore = memoryGuard.getMemoryStats()
        Timber.i("初始化前内存: Native Heap ${memoryBefore.nativeHeapMB} MB")
        
        // 初始化 API
        runBlocking {
            qwenManager.initialize()
        }
        
        val memoryAfter = memoryGuard.getMemoryStats()
        Timber.i("初始化后内存: Native Heap ${memoryAfter.nativeHeapMB} MB")
        
        val memoryIncrease = memoryAfter.nativeHeapMB - memoryBefore.nativeHeapMB
        Timber.i("内存增量: ${memoryIncrease} MB")
        Timber.i("目标: < $TARGET_MEMORY_MB MB")
        
        assertTrue(
            "内存占用 (${memoryAfter.nativeHeapMB} MB) 超过目标 ($TARGET_MEMORY_MB MB)",
            memoryAfter.nativeHeapMB < TARGET_MEMORY_MB
        )
        
        Timber.i("✅ Test 05 通过 - 内存占用: ${memoryAfter.nativeHeapMB} MB")
    }
    
    /**
     * Test 06: 完整性能报告
     */
    @Test
    fun test06_ComprehensivePerformanceReport() {
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Timber.i("🧪 Test 06: 完整性能报告")
        Timber.i("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // 1. 初始化性能
        var initTimeMs = 0L
        runBlocking {
            val startTime = System.currentTimeMillis()
            qwenManager.initialize()
            initTimeMs = System.currentTimeMillis() - startTime
        }
        
        // 2. 首 Token 延迟
        var firstTokenLatency = 0L
        var receivedFirst = false
        
        runBlocking {
            val startTime = System.currentTimeMillis()
            
            qwenManager.generateStream(TEST_PROMPT, maxNewTokens = 50).collect { _ ->
                if (!receivedFirst) {
                    firstTokenLatency = System.currentTimeMillis() - startTime
                    receivedFirst = true
                }
            }
        }
        
        // 3. 生成速度
        var tokenCount = 0
        var totalTimeMs = 0L
        
        runBlocking {
            val startTime = System.currentTimeMillis()
            
            qwenManager.generateStream(TEST_PROMPT, maxNewTokens = TEST_MAX_TOKENS).collect { _ ->
                tokenCount++
            }
            
            totalTimeMs = System.currentTimeMillis() - startTime
        }
        
        val tokensPerSecond = if (totalTimeMs > 0) {
            (tokenCount / (totalTimeMs / 1000.0))
        } else {
            0.0
        }
        
        // 4. 内存占用
        val memoryStats = memoryGuard.getMemoryStats()
        
        // 生成报告
        Timber.i("════════════════════════════════════════")
        Timber.i("📊 Qwen 云 API 性能报告")
        Timber.i("════════════════════════════════════════")
        Timber.i("")
        Timber.i("1️⃣ API 初始化性能:")
        Timber.i("   • 初始化时间: ${initTimeMs} ms")
        Timber.i("   • 目标: < ${TARGET_INIT_TIME_MS} ms")
        Timber.i("   • 状态: ${if (initTimeMs < TARGET_INIT_TIME_MS) "✅ 通过" else "❌ 超时"}")
        Timber.i("")
        Timber.i("2️⃣ 响应延迟:")
        Timber.i("   • 首 Token 延迟: ${firstTokenLatency} ms")
        Timber.i("   • 目标: < ${TARGET_FIRST_TOKEN_MS} ms")
        Timber.i("   • 状态: ${if (firstTokenLatency < TARGET_FIRST_TOKEN_MS) "✅ 通过" else "❌ 超时"}")
        Timber.i("")
        Timber.i("3️⃣ 生成性能:")
        Timber.i("   • 生成 Token 数: $tokenCount")
        Timber.i("   • 总耗时: ${totalTimeMs} ms")
        Timber.i("   • 生成速度: ${"%.2f".format(tokensPerSecond)} tokens/s")
        Timber.i("   • 目标: > ${TARGET_TOKENS_PER_SECOND} tokens/s")
        Timber.i("   • 状态: ${if (tokensPerSecond >= TARGET_TOKENS_PER_SECOND) "✅ 通过" else "❌ 偏慢"}")
        Timber.i("")
        Timber.i("4️⃣ 内存占用:")
        Timber.i("   • Native Heap: ${memoryStats.nativeHeapMB} MB")
        Timber.i("   • 系统总内存: ${memoryStats.totalSystemMemoryMB} MB")
        Timber.i("   • 系统可用: ${memoryStats.availableSystemMemoryMB} MB")
        Timber.i("   • 目标: < ${TARGET_MEMORY_MB} MB")
        Timber.i("   • 状态: ${if (memoryStats.nativeHeapMB < TARGET_MEMORY_MB) "✅ 通过" else "❌ 超标"}")
        Timber.i("")
        Timber.i("5️⃣ 综合评分:")
        
        val passedTests = listOf(
            initTimeMs < TARGET_INIT_TIME_MS,
            firstTokenLatency < TARGET_FIRST_TOKEN_MS,
            tokensPerSecond >= TARGET_TOKENS_PER_SECOND,
            memoryStats.nativeHeapMB < TARGET_MEMORY_MB
        ).count { it }
        
        val totalTests = 4
        val score = (passedTests.toFloat() / totalTests * 100).toInt()
        
        Timber.i("   • 通过测试: $passedTests / $totalTests")
        Timber.i("   • 综合得分: $score 分")
        Timber.i("   • 等级: ${when {
            score >= 90 -> "⭐⭐⭐⭐⭐ 优秀"
            score >= 75 -> "⭐⭐⭐⭐☆ 良好"
            score >= 60 -> "⭐⭐⭐☆☆ 及格"
            else -> "⭐⭐☆☆☆ 需要优化"
        }}")
        Timber.i("")
        Timber.i("════════════════════════════════════════")
        
        // 断言：至少通过 75% 的测试
        assertTrue(
            "综合评分 ($score 分) 低于预期（至少 75 分）",
            score >= 75
        )
        
        Timber.i("✅ Test 06 通过 - 综合得分: $score 分")
    }
}
