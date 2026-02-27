package com.soulon.app.rag

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 向量 DAO (Data Access Object)
 * 
 * 管理记忆向量的数据库访问
 * 
 * Phase 3 Week 3: Task_RAG_Vector
 */
@Dao
interface VectorDao {
    
    /**
     * 插入或更新向量
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVector(vector: MemoryVector)
    
    /**
     * 批量插入向量
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVectors(vectors: List<MemoryVector>)
    
    /**
     * 获取指定记忆的向量
     */
    @Query("SELECT * FROM memory_vectors WHERE memoryId = :memoryId LIMIT 1")
    suspend fun getVector(memoryId: String): MemoryVector?
    
    /**
     * 获取所有向量
     */
    @Query("SELECT * FROM memory_vectors ORDER BY createdAt DESC")
    suspend fun getAllVectors(): List<MemoryVector>
    
    /**
     * 获取所有向量（Flow，响应式）
     */
    @Query("SELECT * FROM memory_vectors ORDER BY createdAt DESC")
    fun getAllVectorsFlow(): Flow<List<MemoryVector>>
    
    /**
     * 获取向量总数
     */
    @Query("SELECT COUNT(*) FROM memory_vectors")
    suspend fun getVectorCount(): Int
    
    /**
     * 检查向量是否存在
     */
    @Query("SELECT EXISTS(SELECT 1 FROM memory_vectors WHERE memoryId = :memoryId)")
    suspend fun vectorExists(memoryId: String): Boolean
    
    /**
     * 删除指定记忆的向量
     */
    @Query("DELETE FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun deleteVector(memoryId: String)
    
    /**
     * 删除多个向量
     */
    @Query("DELETE FROM memory_vectors WHERE memoryId IN (:memoryIds)")
    suspend fun deleteVectors(memoryIds: List<String>)
    
    /**
     * 清空所有向量
     */
    @Query("DELETE FROM memory_vectors")
    suspend fun deleteAllVectors()
    
    /**
     * 获取最近创建的 N 个向量
     */
    @Query("SELECT * FROM memory_vectors ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentVectors(limit: Int): List<MemoryVector>
    
    /**
     * 获取指定模型的向量
     */
    @Query("SELECT * FROM memory_vectors WHERE model = :model ORDER BY createdAt DESC")
    suspend fun getVectorsByModel(model: String): List<MemoryVector>
    
    /**
     * 获取向量统计信息
     */
    @Query("SELECT COUNT(*) as count, AVG(textLength) as avgLength, SUM(textLength) as totalLength FROM memory_vectors")
    suspend fun getVectorStats(): VectorStats?
}

/**
 * 向量统计信息
 */
data class VectorStats(
    val count: Int,
    val avgLength: Float,
    val totalLength: Int
)
