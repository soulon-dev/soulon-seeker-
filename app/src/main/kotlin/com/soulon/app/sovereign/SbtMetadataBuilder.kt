package com.soulon.app.sovereign

import com.soulon.app.rewards.UserProfile
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Soulbound Token (SBT) 元数据构建器
 * 
 * 构建符合 Metaplex 标准的 NFT 元数据
 * 
 * Phase 3 Week 4: Sovereign Certification
 */
object SbtMetadataBuilder {
    
    private const val COLLECTION_NAME = "Soulon Digital Twin"
    private const val COLLECTION_SYMBOL = "MAIC"
    private const val COLLECTION_DESCRIPTION = "Sovereign Digital Twin Certification"
    
    /**
     * 构建 SBT 元数据 JSON
     */
    fun buildMetadata(
        userProfile: UserProfile,
        imageUri: String? = null,
        creatorAddress: String
    ): String {
        val metadata = JSONObject().apply {
            // 基本信息
            put("name", buildTokenName(userProfile))
            put("symbol", COLLECTION_SYMBOL)
            put("description", buildDescription(userProfile))
            
            // 图片（如果有）
            if (imageUri != null) {
                put("image", imageUri)
            }
            
            // 属性
            put("attributes", buildAttributes(userProfile))
            
            // 额外属性
            put("properties", buildProperties(creatorAddress))
            
            // Soulbound 标记
            put("soulbound", true)
        }
        
        return metadata.toString(2) // 格式化输出
    }
    
    /**
     * 构建 Token 名称
     */
    private fun buildTokenName(userProfile: UserProfile): String {
        val tierName = userProfile.getTierName()
        return "Digital Twin Certificate - $tierName"
    }
    
    /**
     * 构建描述
     */
    private fun buildDescription(userProfile: UserProfile): String {
        val tierName = userProfile.getTierName()
        val syncRate = ((userProfile.personaSyncRate ?: 0f) * 100).toInt()
        
        return """
            |This Soulbound Token certifies the holder's Digital Twin on Soulon.
            |
            |Tier Level: ${userProfile.currentTier} ($tierName)
            |Persona Sync Rate: $syncRate%
            |Total ${'$'}MEMO Earned: ${userProfile.totalMemoEarned}
            |
            |This certificate is non-transferable and represents the holder's unique digital identity.
        """.trimMargin()
    }
    
    /**
     * 构建属性列表
     */
    private fun buildAttributes(userProfile: UserProfile): JSONArray {
        val attributes = JSONArray()
        
        // Tier 等级
        attributes.put(buildAttribute("Tier Level", userProfile.currentTier.toString()))
        attributes.put(buildAttribute("Tier Name", userProfile.getTierName()))
        
        // $MEMO 积分
        attributes.put(buildAttribute("MEMO Balance", userProfile.memoBalance.toString()))
        attributes.put(buildAttribute("Total MEMO Earned", userProfile.totalMemoEarned.toString()))
        
        // 人格数据
        val syncRate = ((userProfile.personaSyncRate ?: 0f) * 100).toInt()
        attributes.put(buildAttribute("Persona Sync Rate", "$syncRate%"))
        
        if (userProfile.personaData != null) {
            val (dominantTrait, score) = userProfile.personaData!!.getDominantTrait()
            attributes.put(buildAttribute("Dominant Trait", dominantTrait))
            attributes.put(buildAttribute("Dominant Score", "${(score * 100).toInt()}%"))
        }
        
        // AI 统计
        attributes.put(buildAttribute("Total Inferences", userProfile.totalInferences.toString()))
        attributes.put(buildAttribute("Total Tokens", userProfile.totalTokensGenerated.toString()))
        
        // Sovereign 比例
        val sovereignPercent = (userProfile.sovereignRatio * 100).toInt()
        attributes.put(buildAttribute("Sovereign Ratio", "$sovereignPercent%"))
        
        // 认证日期
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val certificationDate = dateFormat.format(Date())
        attributes.put(buildAttribute("Certification Date", certificationDate))
        
        // Soulbound 标记
        attributes.put(buildAttribute("Soulbound", "true", "boolean"))
        
        return attributes
    }
    
    /**
     * 构建单个属性
     */
    private fun buildAttribute(
        traitType: String,
        value: String,
        displayType: String? = null
    ): JSONObject {
        return JSONObject().apply {
            put("trait_type", traitType)
            put("value", value)
            if (displayType != null) {
                put("display_type", displayType)
            }
        }
    }
    
    /**
     * 构建属性对象
     */
    private fun buildProperties(creatorAddress: String): JSONObject {
        return JSONObject().apply {
            put("category", "certificate")
            
            // 创建者信息
            put("creators", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", creatorAddress)
                    put("share", 100)
                })
            })
            
            // 文件信息
            put("files", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image/png")
                    put("uri", "placeholder") // 实际使用时替换
                })
            })
        }
    }
    
    /**
     * 生成 SBT 图片占位符 URI
     * 
     * TODO: 未来可以实现动态图片生成
     */
    fun getPlaceholderImageUri(tierLevel: Int): String {
        return when (tierLevel) {
            1 -> "https://arweave.net/placeholder-bronze"
            2 -> "https://arweave.net/placeholder-silver"
            3 -> "https://arweave.net/placeholder-gold"
            4 -> "https://arweave.net/placeholder-platinum"
            5 -> "https://arweave.net/placeholder-diamond"
            else -> "https://arweave.net/placeholder-default"
        }
    }
    
    /**
     * 验证元数据格式
     */
    fun validateMetadata(metadataJson: String): Boolean {
        return try {
            val json = JSONObject(metadataJson)
            
            // 检查必需字段
            json.has("name") &&
            json.has("symbol") &&
            json.has("description") &&
            json.has("attributes") &&
            json.has("soulbound") &&
            json.getBoolean("soulbound")
        } catch (e: Exception) {
            false
        }
    }
}
