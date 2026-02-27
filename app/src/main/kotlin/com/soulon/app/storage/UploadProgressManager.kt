package com.soulon.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 上传进度管理器
 * 
 * 功能：
 * - 追踪多个记忆的上传进度
 * - 支持断点续传
 * - 持久化上传状态
 */
class UploadProgressManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "upload_progress",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()
    
    // 上传进度状态
    private val _uploadStates = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val uploadStates: StateFlow<Map<String, UploadState>> = _uploadStates.asStateFlow()
    
    /**
     * 上传状态
     */
    data class UploadState(
        val memoryId: String,
        val totalSize: Long,
        val uploadedSize: Long,
        val status: UploadStatus,
        val errorMessage: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val progress: Float get() = if (totalSize > 0) {
            (uploadedSize.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        
        val progressPercent: Int get() = (progress * 100).toInt()
    }
    
    /**
     * 上传状态枚举
     */
    enum class UploadStatus {
        PENDING,        // 等待上传
        ENCRYPTING,     // 正在加密
        UPLOADING,      // 正在上传
        MINTING,        // 正在铸造 cNFT
        COMPLETED,      // 完成
        FAILED,         // 失败
        RETRYING        // 重试中
    }
    
    init {
        // 恢复之前的上传状态
        loadPersistedStates()
    }
    
    /**
     * 开始上传
     */
    fun startUpload(memoryId: String, totalSize: Long) {
        updateState(
            UploadState(
                memoryId = memoryId,
                totalSize = totalSize,
                uploadedSize = 0,
                status = UploadStatus.PENDING
            )
        )
    }
    
    /**
     * 更新加密状态
     */
    fun updateEncrypting(memoryId: String) {
        val current = _uploadStates.value[memoryId] ?: return
        updateState(current.copy(status = UploadStatus.ENCRYPTING))
    }
    
    /**
     * 更新上传进度
     */
    fun updateProgress(memoryId: String, uploadedSize: Long) {
        val current = _uploadStates.value[memoryId] ?: return
        updateState(
            current.copy(
                uploadedSize = uploadedSize,
                status = UploadStatus.UPLOADING
            )
        )
    }
    
    /**
     * 更新铸造状态
     */
    fun updateMinting(memoryId: String) {
        val current = _uploadStates.value[memoryId] ?: return
        updateState(current.copy(status = UploadStatus.MINTING))
    }
    
    /**
     * 标记完成
     */
    fun markCompleted(memoryId: String, totalSize: Long = 0) {
        val current = _uploadStates.value[memoryId]
        val resolvedTotalSize = current?.totalSize ?: totalSize
        val resolvedUploadedSize = if (resolvedTotalSize > 0) resolvedTotalSize else (current?.uploadedSize ?: 0)
        updateState(
            (current ?: UploadState(
                memoryId = memoryId,
                totalSize = resolvedTotalSize,
                uploadedSize = resolvedUploadedSize,
                status = UploadStatus.COMPLETED
            )).copy(
                uploadedSize = resolvedUploadedSize,
                status = UploadStatus.COMPLETED
            )
        )
        
        // 完成后可以清除持久化状态
        prefs.edit().remove(memoryId).apply()
    }
    
    /**
     * 标记失败
     */
    fun markFailed(memoryId: String, errorMessage: String) {
        val current = _uploadStates.value[memoryId] ?: return
        updateState(
            current.copy(
                status = UploadStatus.FAILED,
                errorMessage = errorMessage
            )
        )
        Timber.e("上传失败: $memoryId, 错误: $errorMessage")
    }
    
    /**
     * 标记重试
     */
    fun markRetrying(memoryId: String) {
        val current = _uploadStates.value[memoryId] ?: return
        updateState(
            current.copy(
                status = UploadStatus.RETRYING,
                errorMessage = null
            )
        )
    }
    
    /**
     * 获取上传状态
     */
    fun getUploadState(memoryId: String): UploadState? {
        return _uploadStates.value[memoryId]
    }
    
    /**
     * 清除上传状态
     */
    fun clearUploadState(memoryId: String) {
        val newStates = _uploadStates.value.toMutableMap()
        newStates.remove(memoryId)
        _uploadStates.value = newStates
        
        prefs.edit().remove(memoryId).apply()
    }
    
    /**
     * 获取所有进行中的上传
     */
    fun getActiveUploads(): List<UploadState> {
        return _uploadStates.value.values.filter { 
            it.status != UploadStatus.COMPLETED 
        }
    }
    
    /**
     * 更新状态
     */
    private fun updateState(state: UploadState) {
        val newStates = _uploadStates.value.toMutableMap()
        newStates[state.memoryId] = state
        _uploadStates.value = newStates
        
        // 持久化（除了已完成的）
        if (state.status != UploadStatus.COMPLETED) {
            persistState(state)
        }
    }
    
    /**
     * 持久化状态
     */
    private fun persistState(state: UploadState) {
        try {
            val json = gson.toJson(state)
            prefs.edit().putString(state.memoryId, json).apply()
        } catch (e: Exception) {
            Timber.e(e, "持久化上传状态失败")
        }
    }
    
    /**
     * 加载持久化的状态
     */
    private fun loadPersistedStates() {
        try {
            val states = mutableMapOf<String, UploadState>()
            prefs.all.forEach { (key, value) ->
                if (value is String) {
                    try {
                        val state = gson.fromJson(value, UploadState::class.java)
                        states[key] = state
                    } catch (e: Exception) {
                        Timber.w(e, "解析上传状态失败: $key")
                    }
                }
            }
            _uploadStates.value = states
            
            if (states.isNotEmpty()) {
                Timber.i("恢复 ${states.size} 个未完成的上传任务")
            }
        } catch (e: Exception) {
            Timber.e(e, "加载持久化状态失败")
        }
    }
}
