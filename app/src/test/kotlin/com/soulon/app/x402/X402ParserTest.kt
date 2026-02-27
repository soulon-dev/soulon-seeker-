package com.soulon.app.x402

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.json.JSONObject

class X402ParserTest {
    @Test
    fun `parses paymentRequirement shape`() {
        val raw = """
            {
              "paymentRequirement": {
                "caip2ChainId": "eip155:8453",
                "assetId": "eip155:8453/usdc",
                "amountAtomic": "1000000",
                "recipient": "0xabc",
                "expiresAt": "2026-01-01T00:00:00Z",
                "nonce": "n1"
              }
            }
        """.trimIndent()
        val mm = X402Parser.toMatchmakingRequest(
            X402Challenge(402, emptyMap(), raw, JSONObject(raw))
        )
        assertEquals("eip155:8453", mm.caip2ChainId)
        assertEquals("eip155:8453/usdc", mm.assetId)
        assertEquals("1000000", mm.amountAtomic)
        assertEquals("0xabc", mm.recipient)
        assertEquals("2026-01-01T00:00:00Z", mm.expiresAt)
        assertEquals("n1", mm.nonce)
        assertNotNull(mm.raw)
    }

    @Test
    fun `parses payment_requirements array shape`() {
        val raw = """
            {
              "payment_requirements": [
                {
                  "caip2_chain_id": "solana:mainnet",
                  "asset_id": "solana:mainnet/usdc",
                  "amount_atomic": "42",
                  "address": "Dest",
                  "expires_at": null,
                  "requestId": "rid"
                }
              ]
            }
        """.trimIndent()
        val mm = X402Parser.toMatchmakingRequest(
            X402Challenge(402, emptyMap(), raw, JSONObject(raw))
        )
        assertEquals("solana:mainnet", mm.caip2ChainId)
        assertEquals("solana:mainnet/usdc", mm.assetId)
        assertEquals("42", mm.amountAtomic)
        assertEquals("Dest", mm.recipient)
        assertEquals("rid", mm.nonce)
    }
}
