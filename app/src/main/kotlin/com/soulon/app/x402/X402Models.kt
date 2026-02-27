package com.soulon.app.x402

import org.json.JSONObject

data class X402Challenge(
    val statusCode: Int,
    val headers: Map<String, String>,
    val bodyRaw: String,
    val bodyJson: JSONObject?,
)

data class MatchmakingRequest(
    val caip2ChainId: String?,
    val assetId: String?,
    val amountAtomic: String?,
    val recipient: String?,
    val expiresAt: String?,
    val nonce: String?,
    val raw: JSONObject?,
)

