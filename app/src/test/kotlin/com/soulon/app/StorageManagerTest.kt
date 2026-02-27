package com.soulon.app

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*
import timber.log.Timber
import kotlin.system.measureTimeMillis

import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.soulon.app.wallet.WalletManager

/**
 * StorageManager 单元测试
 * 
 * 测试覆盖：
 * - 存储成本验证 (<0.00001 SOL)
 * - 数据完整性检查
 * - Irys 上传/下载模拟
 * - cNFT Mint 成本验证
 */
@Ignore("Requires Android filesystem/network and integration harness; not runnable as local JVM unit test.")
class StorageManagerTest {
    
    private lateinit var mockContext: Context
    private lateinit var mockKeyManager: SeedVaultKeyManager
    private lateinit var mockWalletManager: WalletManager
    private lateinit var mockActivityResultSender: ActivityResultSender
    private lateinit var storageManager: StorageManager
    
    @Before
    fun setup() {
        // Mock Android Context
        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns mockk(relaxed = true) {
            every { absolutePath } returns "/tmp/test"
        }
        
        // Mock SeedVaultKeyManager
        mockKeyManager = mockk(relaxed = true)
        
        // Mock WalletManager
        mockWalletManager = mockk(relaxed = true)
        every { mockWalletManager.getWalletAddress() } returns "TestWalletAddress"
        
        // Mock ActivityResultSender
        mockActivityResultSender = mockk(relaxed = true)
        
        // Mock 加密
        coEvery { mockKeyManager.encryptWithWalletKey(any(), any()) } answers {
            val plaintext = firstArg<ByteArray>()
            EncryptedData(
                ciphertext = ByteArray(plaintext.size + 16) { it.toByte() },
                iv = ByteArray(12) { it.toByte() },
                timestamp = System.currentTimeMillis()
            )
        }
        
        // Mock 哈希
        every { mockKeyManager.generateHash(any()) } returns ByteArray(32) { it.toByte() }
        
        Timber.uprootAll()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            }
        })
        
        storageManager = StorageManager(mockContext, mockKeyManager, mockWalletManager)
        
        // Mock 私有方法/依赖（通过 Spy 或 Mockk 高级特性，或者修改 StorageManager 可测试性）
        // 由于 StorageManager 内部创建了 IrysClient 等，这里只能做部分集成测试
        // 实际上 StorageManager 应该依赖注入 IrysClient，这里简化处理，假设 IrysClient 也能被 Mock 或者不影响测试
        
        println("✓ 测试环境初始化完成")
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    /**
     * 测试 1: 存储成本验证 (<0.00001 SOL = 10000 lamports)
     */
    @Test
    fun `test storage cost under limit`() = runBlocking {
        println("\n=== 测试 1: 存储成本验证 ===")
        
        val maxCostLamports = 10000L // 0.00001 SOL
        val testIterations = 10
        val costs = mutableListOf<Long>()
        
        repeat(testIterations) { i ->
            val testContent = "测试记忆内容 #${i + 1}"
            
            val result = storageManager.storeMemory(
                content = testContent,
                metadata = mapOf("test" to "true"),
                activityResultSender = mockActivityResultSender
            )
            
            assertTrue("存储必须成功", result.success)
            assertNotNull("必须返回 memoryId", result.memoryId)
            assertNotNull("必须返回 cnftId", result.cnftId)
            
            costs.add(result.costLamports)
            
            println("  第 ${i + 1} 次存储:")
            println("    成本: ${result.costLamports} lamports")
            println("    cNFT ID: ${result.cnftId}")
            
            // 断言：每次成本都必须 < 10000 lamports
            assertTrue(
                "cNFT 成本 (${result.costLamports}) 必须 < $maxCostLamports lamports",
                result.costLamports < maxCostLamports
            )
        }
        
        val avgCost = costs.average()
        val maxCost = costs.maxOrNull() ?: 0L
        val minCost = costs.minOrNull() ?: 0L
        
        println("\n成本统计:")
        println("  平均成本: ${"%.2f".format(avgCost)} lamports")
        println("  最大成本: $maxCost lamports")
        println("  最小成本: $minCost lamports")
        println("  限制: $maxCostLamports lamports")
        
        assertTrue(
            "最大成本必须 < $maxCostLamports lamports",
            maxCost < maxCostLamports
        )
        
        println("✓ 测试通过: 所有存储成本都在限制内")
    }
    
    /**
     * 测试 2: 数据完整性验证
     */
    @Test
    fun `test data integrity with hash verification`() = runBlocking {
        println("\n=== 测试 2: 数据完整性验证 ===")
        
        val testContent = "这是需要验证完整性的测试数据"
        val expectedHash = mockKeyManager.generateHash(testContent.toByteArray())
        
        // 存储
        val storeResult = storageManager.storeMemory(testContent, activityResultSender = mockActivityResultSender)
        assertTrue("存储必须成功", storeResult.success)
        
        println("  ✓ 数据已存储")
        println("  - Memory ID: ${storeResult.memoryId}")
        println("  - Hash: ${expectedHash.toHexString()}")
        
        // 获取索引
        val memories = storageManager.getAllMemories()
        val storedMemory = memories.find { it.id == storeResult.memoryId }
        assertNotNull("必须能找到存储的记忆", storedMemory)
        
        // 验证哈希
        assertEquals(
            "存储的哈希必须与原始数据哈希匹配",
            expectedHash.toHexString(),
            storedMemory!!.contentHash
        )
        
        println("  ✓ 哈希验证通过")
        println("✓ 测试通过: 数据完整性验证成功")
    }
    
    /**
     * 测试 3: 多个记忆存储和索引
     */
    @Test
    fun `test multiple memories storage and indexing`() = runBlocking {
        println("\n=== 测试 3: 多记忆存储和索引 ===")
        
        val memoryCount = 5
        val storedIds = mutableListOf<String>()
        
        // 存储多个记忆
        repeat(memoryCount) { i ->
            val content = "记忆 #${i + 1}: ${System.currentTimeMillis()}"
            val result = storageManager.storeMemory(
                content = content,
                metadata = mapOf(
                    "index" to i.toString(),
                    "type" to "test"
                ),
                activityResultSender = mockActivityResultSender
            )
            
            assertTrue("第 ${i + 1} 次存储必须成功", result.success)
            storedIds.add(result.memoryId!!)
            
            println("  ✓ 记忆 ${i + 1} 已存储: ${result.memoryId}")
        }
        
        // 验证索引
        val allMemories = storageManager.getAllMemories()
        
        assertTrue(
            "索引中必须至少包含 $memoryCount 条记忆",
            allMemories.size >= memoryCount
        )
        
        // 验证所有存储的 ID 都在索引中
        storedIds.forEach { id ->
            assertTrue(
                "索引必须包含 ID: $id",
                allMemories.any { it.id == id }
            )
        }
        
        println("\n索引验证:")
        println("  总记忆数: ${allMemories.size}")
        println("  新增记忆: $memoryCount")
        println("  ✓ 所有记忆都已索引")
        
        println("✓ 测试通过: 多记忆存储和索引成功")
    }
    
    /**
     * 测试 4: 存储性能测试
     */
    @Test
    fun `test storage performance`() = runBlocking {
        println("\n=== 测试 4: 存储性能测试 ===")
        
        val testCases = listOf(
            "小数据" to "测试".repeat(10),
            "中等数据" to "测试".repeat(100),
            "大数据" to "测试".repeat(1000)
        )
        
        testCases.forEach { (name, content) ->
            println("\n  测试: $name (${content.length} 字符)")
            
            val executionTime = measureTimeMillis {
                val result = storageManager.storeMemory(content, activityResultSender = mockActivityResultSender)
                assertTrue("存储必须成功", result.success)
            }
            
            println("    执行时间: ${executionTime}ms")
            
            // 性能要求：所有操作应在合理时间内完成
            assertTrue(
                "存储时间不应超过 5 秒",
                executionTime < 5000
            )
        }
        
        println("\n✓ 测试通过: 所有性能测试达标")
    }
    
    /**
     * 测试 5: 元数据存储和检索
     */
    @Test
    fun `test metadata storage and retrieval`() = runBlocking {
        println("\n=== 测试 5: 元数据存储测试 ===")
        
        val testMetadata = mapOf(
            "category" to "personal",
            "importance" to "high",
            "tags" to "test,memory,ai",
            "custom_field" to "自定义值"
        )
        
        val result = storageManager.storeMemory(
            content = "带元数据的测试记忆",
            metadata = testMetadata,
            activityResultSender = mockActivityResultSender
        )
        
        assertTrue("存储必须成功", result.success)
        
        // 验证元数据
        val memories = storageManager.getAllMemories()
        val storedMemory = memories.find { it.id == result.memoryId }
        assertNotNull("必须能找到存储的记忆", storedMemory)
        
        testMetadata.forEach { (key, value) ->
            assertEquals(
                "元数据 $key 必须匹配",
                value,
                storedMemory!!.metadata[key]
            )
            println("  ✓ 元数据验证: $key = $value")
        }
        
        println("✓ 测试通过: 元数据存储和检索成功")
    }
    
    /**
     * 测试 6: 错误处理
     */
    @Test
    fun `test error handling for empty content`() = runBlocking {
        println("\n=== 测试 6: 错误处理测试 ===")
        
        // 测试空内容
        val emptyResult = storageManager.storeMemory("", activityResultSender = mockActivityResultSender)
        // 注意：空内容应该也能存储（作为有效的空记忆）
        assertTrue("空内容也应该能存储", emptyResult.success)
        
        println("  ✓ 空内容处理正常")
        
        // 测试超大内容
        val largeContent = "A".repeat(1024 * 1024) // 1MB
        val largeResult = storageManager.storeMemory(largeContent, activityResultSender = mockActivityResultSender)
        assertTrue("大内容应该能存储", largeResult.success)
        
        println("  ✓ 大内容处理正常 (1MB)")
        
        println("✓ 测试通过: 错误处理正常")
    }
    
    /**
     * 测试 7: cNFT 成本估算
     */
    @Test
    fun `test cNFT cost estimation accuracy`() {
        println("\n=== 测试 7: cNFT 成本估算 ===")
        
        // 通过反射访问私有方法（仅用于测试）
        val estimateMethod = StorageManager::class.java
            .getDeclaredMethod("estimateCNFTCost")
        estimateMethod.isAccessible = true
        
        repeat(10) { i ->
            val cost = estimateMethod.invoke(storageManager) as Long
            
            println("  估算 ${i + 1}: $cost lamports")
            
            assertTrue(
                "估算成本必须 > 0",
                cost > 0
            )
            
            assertTrue(
                "估算成本必须 < 10000 lamports",
                cost < 10000
            )
        }
        
        println("✓ 测试通过: 成本估算准确")
    }
    
    /**
     * 测试 8: 并发存储测试
     */
    @Test
    fun `test concurrent storage operations`() = runBlocking {
        println("\n=== 测试 8: 并发存储测试 ===")
        
        val concurrentCount = 5
        val results = mutableListOf<StorageResult>()
        
        // 并发存储
        val jobs = List(concurrentCount) { i ->
            async {
                storageManager.storeMemory("并发记忆 #$i", activityResultSender = mockActivityResultSender)
            }
        }
        
        // 等待所有完成
        jobs.forEach { results.add(it.await()) }
        
        // 验证所有都成功
        val successCount = results.count { it.success }
        println("  成功: $successCount/$concurrentCount")
        
        assertEquals(
            "所有并发操作都必须成功",
            concurrentCount,
            successCount
        )
        
        // 验证所有 ID 唯一
        val uniqueIds = results.mapNotNull { it.memoryId }.toSet()
        assertEquals(
            "所有 Memory ID 必须唯一",
            concurrentCount,
            uniqueIds.size
        )
        
        println("✓ 测试通过: 并发操作正常")
    }
}
