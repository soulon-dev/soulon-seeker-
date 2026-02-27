package com.soulon.app.irys

/**
 * Irys 配置
 * 
 * 官方节点：
 * - node1.irys.xyz (推荐)
 * - node2.irys.xyz
 * - devnet.irys.xyz (测试网)
 */
data class IrysConfig(
    // 使用主网节点
    val nodeUrl: String = "https://node1.irys.xyz",
    
    // Gateway URL（用于检索数据）
    val gatewayUrl: String = "https://gateway.irys.xyz",
    
    // 使用 Solana 作为支付代币
    val currency: String = "solana",
    
    // 上传超时时间（毫秒）
    val uploadTimeout: Long = 60_000,
    
    // 最大重试次数
    val maxRetries: Int = 3
)
