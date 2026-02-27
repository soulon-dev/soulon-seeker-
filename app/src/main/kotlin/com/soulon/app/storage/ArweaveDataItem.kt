package com.soulon.app.storage

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Arweave DataItem 构建器
 * 
 * 实现 ANS-104 Bundled Data 规范
 * https://github.com/ArweaveTeam/arweave-standards/blob/master/ans/ANS-104.md
 * 
 * DataItem 格式:
 * - signature_type (2 bytes): Solana = 2
 * - signature (64 bytes): Ed25519 签名
 * - owner (32 bytes): Solana 公钥
 * - target (0 或 32 bytes): 可选的目标地址
 * - anchor (0 或 32 bytes): 可选的锚点
 * - tags_count (8 bytes): tag 数量
 * - tags_bytes (8 bytes): tags 的字节总长度
 * - tags: 编码的 tags (Avro 格式)
 * - data: 实际数据
 * 
 * 签名使用 Arweave deep-hash 算法 (SHA-384)
 */
class ArweaveDataItem {
    
    companion object {
        // Signature Types (ANS-104)
        const val SIG_TYPE_ARWEAVE = 1
        const val SIG_TYPE_SOLANA = 2
        const val SIG_TYPE_ETHEREUM = 3
        
        // Signature Lengths
        const val SOLANA_SIG_LENGTH = 64
        const val SOLANA_PUBKEY_LENGTH = 32
        
        /**
         * 创建 Solana 签名的 DataItem
         * 
         * @param data 要上传的数据
         * @param publicKey Solana 公钥 (32 bytes)
         * @param tags 元数据标签
         * @param signFunction 签名函数：接收待签名的 deep-hash，返回 Ed25519 签名
         * @return 完整的 DataItem 字节数组
         */
        suspend fun createSolanaDataItem(
            data: ByteArray,
            publicKey: ByteArray,
            tags: List<Tag> = emptyList(),
            signFunction: suspend (ByteArray) -> ByteArray
        ): ByteArray {
            require(publicKey.size == SOLANA_PUBKEY_LENGTH) {
                "Solana 公钥必须是 $SOLANA_PUBKEY_LENGTH 字节"
            }
            
            Timber.d("创建 DataItem: 数据大小=${data.size}, tags=${tags.size}")
            
            // 1. 编码 tags (ANS-104 Avro 格式)
            val encodedTags = encodeTagsAvro(tags)
            val tagsCount = tags.size.toLong()
            val tagsBytes = encodedTags.size.toLong()
            
            Timber.d("Tags 编码完成 (Avro): count=$tagsCount, bytes=$tagsBytes")
            
            // 2. 使用 Arweave deep-hash 算法构建待签名消息
            val targetBytes = byteArrayOf() // 无 target
            val anchorBytes = byteArrayOf() // 无 anchor
            
            val sigTypeStr = SIG_TYPE_SOLANA.toString().toByteArray(Charsets.UTF_8)
            
            val deepHashInput = listOf(
                "dataitem".toByteArray(Charsets.UTF_8),
                "1".toByteArray(Charsets.UTF_8),
                sigTypeStr,
                publicKey,
                targetBytes,
                anchorBytes,
                encodedTags,
                data
            )
            
            // 计算 deep-hash
            val messageHash = deepHash(deepHashInput)
            Timber.d("🔐 Deep-hash 结果 (${messageHash.size} bytes): ${messageHash.toHexString()}")
            
            // 3. 使用 Solana 钱包签名
            val signature = signFunction(messageHash)
            require(signature.size == SOLANA_SIG_LENGTH) {
                "Solana 签名必须是 $SOLANA_SIG_LENGTH 字节，实际: ${signature.size}"
            }
            
            Timber.d("签名完成: ${signature.toHexString().take(32)}...")
            
            // 4. 构建完整的 DataItem
            return buildDataItem(
                signatureType = SIG_TYPE_SOLANA,
                signature = signature,
                owner = publicKey,
                target = null,
                anchor = null,
                tagsCount = tagsCount,
                tagsBytes = tagsBytes,
                tags = encodedTags,
                data = data
            )
        }
        
        /**
         * Arweave deep-hash 算法
         * 
         * 参考: https://github.com/ArweaveTeam/arweave-js/blob/master/src/common/lib/deepHash.ts
         * 
         * 算法：
         * - 对于数组: hash("list" + length) -> 递归处理每个元素
         * - 对于字节: hash("blob" + length) + hash(data) -> hash(concat)
         * 
         * 使用 SHA-384 算法
         */
        private fun deepHash(data: Any): ByteArray {
            return when (data) {
                is ByteArray -> {
                    // 叶子节点: blob
                    val tag = "blob${data.size}".toByteArray(Charsets.UTF_8)
                    val tagHash = sha384(tag)
                    val dataHash = sha384(data)
                    sha384(tagHash + dataHash)
                }
                is List<*> -> {
                    // 数组节点: list
                    val tag = "list${data.size}".toByteArray(Charsets.UTF_8)
                    var acc = sha384(tag)
                    
                    for (chunk in data) {
                        val chunkHash = deepHash(chunk!!)
                        acc = sha384(acc + chunkHash)
                    }
                    
                    acc
                }
                else -> {
                    throw IllegalArgumentException("deep-hash 不支持类型: ${data::class.java}")
                }
            }
        }
        
        /**
         * SHA-384 哈希
         */
        private fun sha384(data: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-384").digest(data)
        }
        
        /**
         * 编码 tags 为 ANS-104 Avro 格式
         * 
         * 参考: https://github.com/Irys-xyz/arbundles/blob/master/src/tags.ts
         * 
         * Avro 数组格式:
         * - 块计数 (ZigZag VInt)
         * - 块内容 (每个 tag: name_size + name + value_size + value)
         * - 结束标记 (0)
         * 
         * 注意: 空 tags 返回空数组，不是 [0]
         */
        private fun encodeTagsAvro(tags: List<Tag>): ByteArray {
            if (tags.isEmpty()) {
                // 与 arbundles 一致：空 tags 返回空数组
                return byteArrayOf()
            }
            
            val output = ByteArrayOutputStream()
            
            // 写入块计数 (正数，所有 tags 在一个块中)
            output.write(encodeAvroLong(tags.size.toLong()))
            
            // 写入每个 tag
            for (tag in tags) {
                val nameBytes = tag.name.toByteArray(Charsets.UTF_8)
                val valueBytes = tag.value.toByteArray(Charsets.UTF_8)
                
                // name_size (Avro long) + name
                output.write(encodeAvroLong(nameBytes.size.toLong()))
                output.write(nameBytes)
                
                // value_size (Avro long) + value
                output.write(encodeAvroLong(valueBytes.size.toLong()))
                output.write(valueBytes)
            }
            
            // 写入结束标记 0
            output.write(0)
            
            return output.toByteArray()
        }
        
        /**
         * Avro long 编码 (ZigZag + VInt)
         */
        private fun encodeAvroLong(value: Long): ByteArray {
            // ZigZag 编码
            val zigzag = (value shl 1) xor (value shr 63)
            
            // VInt 编码
            val output = ByteArrayOutputStream()
            var v = zigzag
            while ((v and 0x7FL.inv()) != 0L) {
                output.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            output.write((v and 0x7F).toInt())
            
            return output.toByteArray()
        }
        
        /**
         * 构建完整的 DataItem（包含签名）
         */
        private fun buildDataItem(
            signatureType: Int,
            signature: ByteArray,
            owner: ByteArray,
            target: ByteArray?,
            anchor: ByteArray?,
            tagsCount: Long,
            tagsBytes: Long,
            tags: ByteArray,
            data: ByteArray
        ): ByteArray {
            val output = ByteArrayOutputStream()
            
            // Signature Type (2 bytes, little-endian)
            output.write(signatureType.toShort().toLeBytes())
            
            // Signature (64 bytes for Solana)
            output.write(signature)
            
            // Owner (32 bytes for Solana)
            output.write(owner)
            
            // Target presence flag (1 byte)
            output.write(if (target != null) 1 else 0)
            if (target != null) {
                output.write(target)
            }
            
            // Anchor presence flag (1 byte)
            output.write(if (anchor != null) 1 else 0)
            if (anchor != null) {
                output.write(anchor)
            }
            
            // Tags count (8 bytes, little-endian)
            output.write(tagsCount.toLeBytes())
            
            // Tags bytes (8 bytes, little-endian)
            output.write(tagsBytes.toLeBytes())
            
            // Tags
            if (tags.isNotEmpty()) {
                output.write(tags)
            }
            
            // Data
            output.write(data)
            
            val result = output.toByteArray()
            
            // 调试日志: 打印 DataItem 结构
            Timber.i("✅ DataItem 构建完成: 总大小=${result.size} 字节")
            Timber.d("📦 DataItem 结构:")
            Timber.d("  sigType (2 bytes): ${result.sliceArray(0..1).toHexString()}")
            Timber.d("  signature (64 bytes): ${result.sliceArray(2..65).toHexString().take(32)}...")
            Timber.d("  owner (32 bytes): ${result.sliceArray(66..97).toHexString().take(32)}...")
            Timber.d("  targetPresent: ${result[98]}")
            val anchorStart = if (result[98].toInt() == 1) 131 else 99
            Timber.d("  anchorPresent: ${result[anchorStart]}")
            
            return result
        }
        
        // 辅助函数：Short 转 Little-Endian 字节数组
        private fun Short.toLeBytes(): ByteArray {
            return byteArrayOf(
                (this.toInt() and 0xFF).toByte(),
                ((this.toInt() shr 8) and 0xFF).toByte()
            )
        }
        
        // 辅助函数：Long 转 Little-Endian 字节数组
        private fun Long.toLeBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(8)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putLong(this)
            return buffer.array()
        }
        
        // 辅助函数：字节数组转十六进制字符串
        private fun ByteArray.toHexString(): String {
            return this.joinToString("") { "%02x".format(it) }
        }
        
        /**
         * 创建无签名的 DataItem（用于迁移包等不需要验证的数据）
         * 
         * @param data 要上传的数据
         * @param tags 元数据标签
         * @return DataItem 字节数组
         */
        fun createUnsignedDataItem(
            data: ByteArray,
            tags: List<Tag> = emptyList()
        ): ByteArray {
            Timber.d("创建无签名 DataItem: 数据大小=${data.size}, tags=${tags.size}")
            
            // 使用简化格式：直接返回数据（Irys 会自动处理）
            // 对于迁移包，我们不需要签名验证，只需要数据存储
            val output = ByteArrayOutputStream()
            
            // 1. 编码 tags
            val encodedTags = encodeTagsAvro(tags)
            val tagsCount = tags.size.toLong()
            val tagsBytes = encodedTags.size.toLong()
            
            // 2. 使用空签名和空 owner (表示这是一个公开数据)
            val emptySignature = ByteArray(SOLANA_SIG_LENGTH)
            val emptyOwner = ByteArray(SOLANA_PUBKEY_LENGTH)
            
            // 3. 构建 DataItem
            // Signature type (2 bytes, little-endian): Solana = 2
            output.write(SIG_TYPE_SOLANA.toShort().toLeBytes())
            
            // Signature (64 bytes)
            output.write(emptySignature)
            
            // Owner (32 bytes)
            output.write(emptyOwner)
            
            // Target (1 byte: 0 = no target)
            output.write(0)
            
            // Anchor (1 byte: 0 = no anchor)
            output.write(0)
            
            // Tags count (8 bytes, little-endian)
            output.write(tagsCount.toLeBytes())
            
            // Tags bytes (8 bytes, little-endian)
            output.write(tagsBytes.toLeBytes())
            
            // Tags
            if (tags.isNotEmpty()) {
                output.write(encodedTags)
            }
            
            // Data
            output.write(data)
            
            val result = output.toByteArray()
            Timber.i("✅ 无签名 DataItem 构建完成: 总大小=${result.size} 字节")
            
            return result
        }
    }
    
    /**
     * Tag 数据类
     */
    data class Tag(
        val name: String,
        val value: String
    )
}
