package com.soulon.app.x402

import org.json.JSONObject

object X402Parser {
    fun tryParseJson(raw: String): JSONObject? =
        try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }

    fun toMatchmakingRequest(challenge: X402Challenge): MatchmakingRequest {
        val json = challenge.bodyJson
        val req = json?.optJSONObject("paymentRequirement")
            ?: json?.optJSONObject("payment_requirement")
            ?: json?.optJSONObject("requirement")
            ?: json?.optJSONArray("paymentRequirements")?.optJSONObject(0)
            ?: json?.optJSONArray("payment_requirements")?.optJSONObject(0)

        val caip2 = req?.optString("caip2ChainId").takeIfNotBlank()
            ?: req?.optString("caip2_chain_id").takeIfNotBlank()
            ?: req?.optString("chain").takeIfNotBlank()
            ?: req?.optString("chainId").takeIfNotBlank()

        val assetId = req?.optString("assetId").takeIfNotBlank()
            ?: req?.optString("asset_id").takeIfNotBlank()
            ?: req?.optString("asset").takeIfNotBlank()
            ?: req?.optString("token").takeIfNotBlank()

        val amountAtomic = req?.optString("amountAtomic").takeIfNotBlank()
            ?: req?.optString("amount_atomic").takeIfNotBlank()
            ?: req?.optString("amount").takeIfNotBlank()
            ?: req?.optJSONObject("amount")?.optString("atomic").takeIfNotBlank()

        val recipient = req?.optString("recipient").takeIfNotBlank()
            ?: req?.optString("address").takeIfNotBlank()
            ?: req?.optJSONObject("recipient")?.optString("address").takeIfNotBlank()

        val expiresAt = req?.optString("expiresAt").takeIfNotBlank()
            ?: req?.optString("expires_at").takeIfNotBlank()

        val nonce = req?.optString("nonce").takeIfNotBlank()
            ?: req?.optString("requestId").takeIfNotBlank()
            ?: req?.optString("id").takeIfNotBlank()

        val rawOut = json ?: run {
            val wrapper = JSONObject()
            wrapper.put("raw", challenge.bodyRaw)
            wrapper
        }

        return MatchmakingRequest(
            caip2ChainId = caip2,
            assetId = assetId,
            amountAtomic = amountAtomic,
            recipient = recipient,
            expiresAt = expiresAt,
            nonce = nonce,
            raw = rawOut,
        )
    }

    private fun String?.takeIfNotBlank(): String? = this?.takeIf { it.isNotBlank() }
}
