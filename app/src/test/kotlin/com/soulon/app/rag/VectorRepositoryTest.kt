package com.soulon.app.rag

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import timber.log.Timber

/**
 * VectorRepository 单元测试
 * 
 * 测试覆盖：
 * - 向量存储和检索
 * - 批量向量化
 * - 向量维度验证
 * - 统计功能
 */
class VectorRepositoryTest {
    
    private lateinit var mockContext: Context
    private lateinit var mockVectorDao: VectorDao
    private lateinit var mockEmbeddingService: EmbeddingService
    
    @Before
    fun setup() {
        // Mock Android Context
        mockContext = mockk(relaxed = true)
        
        Timber.uprootAll()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            }
        })
        
        println("✓ 测试环境初始化完成")
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    /**
     * 测试 1: 向量维度验证
     */
    @Test
    fun `test vector dimension is 1024`() {
        println("\n=== 测试 1: 向量维度验证 ===")
        
        val embeddingService = EmbeddingService(mockContext)
        val dimension = embeddingService.getVectorDimension()
        
        assertEquals(
            "向量维度应该是 1024",
            1024,
            dimension
        )
        
        println("  ✓ 向量维度: $dimension")
        println("✓ 测试通过: 向量维度正确")
    }
    
    /**
     * 测试 2: MemoryVector 创建和转换
     */
    @Test
    fun `test MemoryVector creation and conversion`() {
        println("\n=== 测试 2: MemoryVector 创建和转换 ===")
        
        val testVector = FloatArray(1024) { it.toFloat() }
        val memoryId = "test-memory-001"
        
        // 创建 MemoryVector
        val memoryVector = MemoryVector.fromFloatArray(
            memoryId = memoryId,
            vector = testVector,
            textLength = 100
        )
        
        assertEquals("memoryId 应匹配", memoryId, memoryVector.memoryId)
        assertEquals("维度应为 1024", 1024, memoryVector.vectorDimension)
        assertEquals("文本长度应为 100", 100, memoryVector.textLength)
        
        println("  ✓ MemoryVector 创建成功")
        
        // 转换回 FloatArray
        val convertedVector = memoryVector.toFloatArray()
        
        assertEquals("转换后维度应相同", testVector.size, convertedVector.size)
        
        // 验证值（抽样检查）
        assertTrue("首元素应匹配", testVector[0] == convertedVector[0])
        assertTrue("末元素应匹配", testVector[1023] == convertedVector[1023])
        assertTrue("中间元素应匹配", testVector[512] == convertedVector[512])
        
        println("  ✓ FloatArray 转换成功")
        println("✓ 测试通过: MemoryVector 创建和转换正确")
    }
    
    /**
     * 测试 3: VectorConverter JSON 序列化
     */
    @Test
    fun `test VectorConverter JSON serialization`() {
        println("\n=== 测试 3: VectorConverter JSON 序列化 ===")
        
        val testVector = floatArrayOf(1.0f, 2.5f, -3.7f, 0.0f, 999.999f)
        
        // 序列化
        val json = VectorConverter.fromFloatArray(testVector)
        assertNotNull("JSON 不应为空", json)
        assertTrue("JSON 应该是数组格式", json.startsWith("[") && json.endsWith("]"))
        
        println("  ✓ JSON 序列化成功: $json")
        
        // 反序列化
        val restored = VectorConverter.toFloatArray(json)
        assertNotNull("反序列化结果不应为空", restored)
        assertEquals("维度应相同", testVector.size, restored!!.size)
        
        // 验证精度
        for (i in testVector.indices) {
            assertEquals(
                "元素 $i 应匹配",
                testVector[i],
                restored[i],
                0.0001f
            )
        }
        
        println("  ✓ JSON 反序列化成功")
        println("✓ 测试通过: JSON 序列化正确")
    }
    
    /**
     * 测试 4: 向量相似度计算（余弦相似度）
     */
    @Test
    fun `test cosine similarity calculation`() {
        println("\n=== 测试 4: 余弦相似度计算 ===")
        
        // 相同向量 -> 相似度 = 1.0
        val vector1 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val similarity1 = cosineSimilarity(vector1, vector1)
        assertEquals("相同向量相似度应为 1.0", 1.0f, similarity1, 0.0001f)
        println("  ✓ 相同向量: 相似度 = $similarity1")
        
        // 相反向量 -> 相似度 = -1.0
        val vector2 = floatArrayOf(-1.0f, -2.0f, -3.0f)
        val similarity2 = cosineSimilarity(vector1, vector2)
        assertEquals("相反向量相似度应为 -1.0", -1.0f, similarity2, 0.0001f)
        println("  ✓ 相反向量: 相似度 = $similarity2")
        
        // 正交向量 -> 相似度 = 0.0
        val vector3 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val vector4 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val similarity3 = cosineSimilarity(vector3, vector4)
        assertEquals("正交向量相似度应为 0.0", 0.0f, similarity3, 0.0001f)
        println("  ✓ 正交向量: 相似度 = $similarity3")
        
        // 一般情况
        val vector5 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val vector6 = floatArrayOf(4.0f, 5.0f, 6.0f)
        val similarity4 = cosineSimilarity(vector5, vector6)
        assertTrue("一般情况相似度应在 0-1 之间", similarity4 > 0 && similarity4 < 1)
        println("  ✓ 一般情况: 相似度 = $similarity4")
        
        println("✓ 测试通过: 余弦相似度计算正确")
    }
    
    /**
     * 辅助方法：计算余弦相似度
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "向量维度不匹配" }
        
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0.0f) 0.0f else dotProduct / denominator
    }
    
    /**
     * 测试 5: 大向量序列化性能
     */
    @Test
    fun `test large vector serialization performance`() {
        println("\n=== 测试 5: 大向量序列化性能 ===")
        
        val dimensions = listOf(512, 1024, 2048)
        
        for (dim in dimensions) {
            val vector = FloatArray(dim) { kotlin.random.Random.nextFloat() }
            
            val startTime = System.currentTimeMillis()
            val json = VectorConverter.fromFloatArray(vector)
            val serializeTime = System.currentTimeMillis() - startTime
            
            val startTime2 = System.currentTimeMillis()
            val restored = VectorConverter.toFloatArray(json)
            val deserializeTime = System.currentTimeMillis() - startTime2
            
            println("  $dim 维向量:")
            println("    序列化: ${serializeTime}ms, JSON 大小: ${json.length} 字符")
            println("    反序列化: ${deserializeTime}ms")
            
            assertTrue("序列化应在 100ms 内完成", serializeTime < 100)
            assertTrue("反序列化应在 100ms 内完成", deserializeTime < 100)
            assertNotNull("反序列化结果不应为空", restored)
            assertEquals("维度应相同", dim, restored!!.size)
        }
        
        println("✓ 测试通过: 大向量序列化性能良好")
    }
    
    /**
     * 测试 6: 空向量和边界情况
     */
    @Test
    fun `test edge cases`() {
        println("\n=== 测试 6: 边界情况测试 ===")
        
        // 空向量
        val emptyVector = floatArrayOf()
        val emptyJson = VectorConverter.fromFloatArray(emptyVector)
        assertEquals("空向量 JSON 应为 []", "[]", emptyJson)
        println("  ✓ 空向量序列化正确")
        
        val emptyRestored = VectorConverter.toFloatArray(emptyJson)
        assertNotNull("空向量反序列化不应为 null", emptyRestored)
        assertEquals("空向量维度应为 0", 0, emptyRestored!!.size)
        println("  ✓ 空向量反序列化正确")
        
        // 单元素向量
        val singleVector = floatArrayOf(42.0f)
        val singleJson = VectorConverter.fromFloatArray(singleVector)
        val singleRestored = VectorConverter.toFloatArray(singleJson)
        assertEquals("单元素向量应匹配", singleVector[0], singleRestored!![0], 0.0001f)
        println("  ✓ 单元素向量处理正确")
        
        // 无效 JSON
        val invalidResult = VectorConverter.toFloatArray("invalid json")
        assertNull("无效 JSON 应返回 null", invalidResult)
        println("  ✓ 无效 JSON 处理正确")
        
        println("✓ 测试通过: 边界情况处理正确")
    }
}
