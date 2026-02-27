package com.soulon.app.cache

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 记忆内容缓存
 * 
 * 用途：
 * 1. 缓存解密后的记忆明文，避免重复解密
 * 2. 为 RAG 检索提供快速访问
 * 3. 仅在内存中，应用关闭后自动清除
 * 
 * 安全性：
 * - 仅存储在内存中，不持久化
 * - 可手动清除
 * - 应用关闭自动清除
 */
object MemoryCache {
    
    // 使用线程安全的 Map
    private val cache = ConcurrentHashMap<String, CachedMemory>()
    
    /**
     * 添加记忆到缓存
     */
    fun put(memoryId: String, content: String) {
        cache[memoryId] = CachedMemory(
            content = content,
            cachedAt = System.currentTimeMillis()
        )
        Timber.d("缓存记忆: $memoryId")
    }
    
    /**
     * 获取缓存的记忆
     */
    fun get(memoryId: String): String? {
        return cache[memoryId]?.content
    }
    
    /**
     * 获取所有缓存的记忆内容（用于 RAG）
     */
    fun getAllContents(): Map<String, String> {
        return cache.mapValues { it.value.content }
    }
    
    /**
     * 检查记忆是否已缓存
     */
    fun contains(memoryId: String): Boolean {
        return cache.containsKey(memoryId)
    }
    
    /**
     * 移除记忆
     */
    fun remove(memoryId: String) {
        cache.remove(memoryId)
        Timber.d("移除缓存: $memoryId")
    }
    
    /**
     * 清除所有缓存
     */
    fun clear() {
        val size = cache.size
        cache.clear()
        Timber.i("清除所有缓存: $size 条记忆")
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getStats(): CacheStats {
        return CacheStats(
            totalMemories = cache.size,
            totalSize = cache.values.sumOf { it.content.length }
        )
    }
}

/**
 * 缓存的记忆
 */
private data class CachedMemory(
    val content: String,
    val cachedAt: Long
)

/**
 * 缓存统计信息
 */
data class CacheStats(
    val totalMemories: Int,
    val totalSize: Int
)
