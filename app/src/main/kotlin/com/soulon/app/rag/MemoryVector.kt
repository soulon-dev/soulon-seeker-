package com.soulon.app.rag

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * 记忆向量实体
 * 
 * 存储记忆的文本向量，用于语义检索
 * 
 * Phase 3 Week 3: Task_RAG_Vector
 */
@Entity(tableName = "memory_vectors")
data class MemoryVector(
    @PrimaryKey
    val memoryId: String,
    
    /** 向量数据（JSON 数组字符串） */
    val vectorJson: String,
    
    /** 向量维度 (text-embedding-v3 = 1024) */
    val vectorDimension: Int = 1024,
    
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Embedding 模型版本 */
    val model: String = "text-embedding-v3",
    
    /** 原始文本长度（用于统计） */
    val textLength: Int = 0
) {
    /**
     * 将 JSON 字符串转换为 FloatArray
     */
    fun toFloatArray(): FloatArray {
        return VectorConverter.toFloatArray(vectorJson) ?: FloatArray(0)
    }
    
    companion object {
        /**
         * 从 FloatArray 创建 MemoryVector
         */
        fun fromFloatArray(
            memoryId: String,
            vector: FloatArray,
            textLength: Int = 0,
            model: String = "text-embedding-v3"
        ): MemoryVector {
            return MemoryVector(
                memoryId = memoryId,
                vectorJson = VectorConverter.fromFloatArray(vector),
                vectorDimension = vector.size,
                createdAt = System.currentTimeMillis(),
                model = model,
                textLength = textLength
            )
        }
    }
}

/**
 * Room TypeConverter for vector storage
 */
object VectorConverter {
    
    /**
     * FloatArray 转 JSON 字符串
     */
    fun fromFloatArray(vector: FloatArray): String {
        val jsonArray = JSONArray()
        vector.forEach { jsonArray.put(it) }
        return jsonArray.toString()
    }
    
    /**
     * JSON 字符串转 FloatArray
     */
    fun toFloatArray(json: String): FloatArray? {
        return try {
            val jsonArray = JSONArray(json)
            FloatArray(jsonArray.length()) { i ->
                jsonArray.getDouble(i).toFloat()
            }
        } catch (e: Exception) {
            null
        }
    }
}
