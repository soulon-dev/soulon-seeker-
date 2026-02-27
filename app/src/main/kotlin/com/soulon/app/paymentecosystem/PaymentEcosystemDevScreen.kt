package com.soulon.app.paymentecosystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.soulon.app.negotiation.NegotiationClient
import com.soulon.app.x402.MatchmakingRequest
import com.soulon.app.x402.X402Challenge
import com.soulon.app.x402.X402Parser
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun PaymentEcosystemDevScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val negotiationClient = remember { NegotiationClient(context) }

    var rawJson by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<MatchmakingRequest?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    var sessionId by remember { mutableStateOf<String?>(null) }
    var orderId by remember { mutableStateOf<String?>(null) }
    var startRaw by remember { mutableStateOf<String?>(null) }
    var lastRaw by remember { mutableStateOf<String?>(null) }
    var apiError by remember { mutableStateOf<String?>(null) }

    var buyerContent by remember { mutableStateOf("Lower the fee.") }
    var buyerFeeBps by remember { mutableStateOf("80") }

    var sourceAsset by remember { mutableStateOf("SOL") }
    var targetAsset by remember { mutableStateOf("USDC_BASE") }
    var targetAmount by remember { mutableStateOf("1.0") }
    var recipient by remember { mutableStateOf("") }
    var personaDigest by remember { mutableStateOf("TOUGH") }

    LaunchedEffect(Unit) {
        val pending = com.soulon.app.x402.X402ChallengeStore.pop()
        if (pending != null && pending.bodyRaw.isNotBlank()) {
            rawJson = pending.bodyRaw
        } else if (rawJson.isBlank()) {
            rawJson = """
            {
              "paymentRequirement": {
                "caip2ChainId": "eip155:8453",
                "assetId": "eip155:8453/usdc",
                "amountAtomic": "1000000",
                "recipient": "0xrecipient",
                "expiresAt": null,
                "nonce": "demo"
              }
            }
            """.trimIndent()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "支付生态（Dev）", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Button(onClick = onBack) { Text("返回") }
        }

        Text(
            text = "手动付款（不依赖 402）",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.titleMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { targetAsset = "USDC_BASE" }) { Text("USDC (Base)") }
            Button(onClick = { targetAsset = "USDC_POLYGON" }) { Text("USDC (Polygon)") }
            Button(onClick = { targetAsset = "SOL" }) { Text("SOL (Solana)") }
        }

        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it.trim() },
            label = { Text("目标地址（收款地址）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = targetAmount,
                onValueChange = { targetAmount = it },
                label = { Text("目标金额") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Number
                ),
                singleLine = true
            )
            OutlinedTextField(
                value = targetAsset,
                onValueChange = { targetAsset = it.trim().uppercase() },
                label = { Text("目标币种") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = sourceAsset,
                onValueChange = { sourceAsset = it.trim().uppercase() },
                label = { Text("支付币种（来源）") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = personaDigest,
                onValueChange = { personaDigest = it },
                label = { Text("persona_digest") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    parseError = null
                    apiError = null
                    val amt = targetAmount.toBigDecimalOrNull()
                    if (recipient.isBlank()) {
                        parsed = null
                        parseError = "missing_recipient"
                        return@Button
                    }
                    if (amt == null || amt <= BigDecimal.ZERO) {
                        parsed = null
                        parseError = "invalid_amount"
                        return@Button
                    }
                    val decimals = when {
                        targetAsset.contains("USDC") -> 6
                        targetAsset == "SOL" || targetAsset.contains("SOLANA") -> 9
                        else -> 6
                    }
                    val atomic = try {
                        amt.movePointRight(decimals).setScale(0, RoundingMode.DOWN).toPlainString()
                    } catch (_: Exception) {
                        null
                    }
                    if (atomic.isNullOrBlank()) {
                        parsed = null
                        parseError = "invalid_amount_atomic"
                        return@Button
                    }

                    val pr = JSONObject().apply {
                        put("recipient", recipient)
                        put("amountAtomic", atomic)
                        put("decimals", decimals)
                        put("nonce", "manual")
                    }
                    val wrapper = JSONObject().apply { put("paymentRequirement", pr) }
                    rawJson = wrapper.toString(2)
                    parsed = MatchmakingRequest(
                        caip2ChainId = null,
                        assetId = null,
                        amountAtomic = atomic,
                        recipient = recipient,
                        expiresAt = null,
                        nonce = "manual",
                        raw = wrapper,
                    )
                }
            ) { Text("生成请求") }

            Button(
                onClick = {
                    val mm = parsed
                    if (mm == null) {
                        apiError = "先生成请求或解析 x402"
                        return@Button
                    }
                    val amt = targetAmount.toDoubleOrNull()
                    if (amt == null || amt <= 0.0) {
                        apiError = "invalid_amount"
                        return@Button
                    }
                    apiError = null
                    scope.launch {
                        val res = negotiationClient.start(
                            sourceAsset = sourceAsset.ifBlank { "SOL" },
                            targetAsset = targetAsset.ifBlank { "USDC_BASE" },
                            amount = amt,
                            personaDigest = personaDigest.ifBlank { "TOUGH" },
                            x402 = mm,
                        )
                        res.onSuccess {
                            sessionId = it.sessionId
                            orderId = null
                            startRaw = it.raw.toString()
                            lastRaw = it.raw.toString()
                        }.onFailure {
                            apiError = it.message ?: "start_failed"
                        }
                    }
                }
            ) { Text("Start") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = rawJson,
            onValueChange = { rawJson = it },
            label = { Text("x402 JSON（或第三方 402 body）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    parseError = null
                    apiError = null
                    try {
                        val json = JSONObject(rawJson)
                        val challenge = X402Challenge(
                            statusCode = 402,
                            headers = emptyMap(),
                            bodyRaw = rawJson,
                            bodyJson = json,
                        )
                        parsed = X402Parser.toMatchmakingRequest(challenge)
                    } catch (e: Exception) {
                        parsed = null
                        parseError = e.message ?: "parse_failed"
                    }
                }
            ) { Text("解析 x402") }
        }

        parseError?.let { Text(text = "解析错误：$it", color = Color(0xFFFF6B6B)) }
        apiError?.let { Text(text = "接口错误：$it", color = Color(0xFFFF6B6B)) }

        Text(text = "sessionId: ${sessionId ?: "-"}", color = Color.White.copy(alpha = 0.8f))
        Text(text = "orderId: ${orderId ?: "-"}", color = Color.White.copy(alpha = 0.8f))

        parsed?.let {
            Text(
                text = "MatchmakingRequest: caip2=${it.caip2ChainId} asset=${it.assetId} amount=${it.amountAtomic} recipient=${it.recipient}",
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = buyerContent,
                onValueChange = { buyerContent = it },
                label = { Text("Buyer 内容") },
                modifier = Modifier.weight(1f),
                minLines = 1
            )
            OutlinedTextField(
                value = buyerFeeBps,
                onValueChange = { buyerFeeBps = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("Buyer fee bps") },
                modifier = Modifier.weight(0.5f),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Number
                ),
                singleLine = true
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val sid = sessionId
                    if (sid.isNullOrBlank()) {
                        apiError = "先 start"
                        return@Button
                    }
                    apiError = null
                    scope.launch {
                        val res = negotiationClient.next(
                            sessionId = sid,
                            buyerContent = buyerContent,
                            buyerFeeBps = buyerFeeBps.toIntOrNull(),
                        )
                        res.onSuccess { lastRaw = it.raw.toString() }
                            .onFailure { apiError = it.message ?: "next_failed" }
                    }
                }
            ) { Text("Next") }

            Button(
                onClick = {
                    val sid = sessionId
                    if (sid.isNullOrBlank()) {
                        apiError = "先 start"
                        return@Button
                    }
                    apiError = null
                    scope.launch {
                        val res = negotiationClient.previewExecution(sid)
                        res.onSuccess { lastRaw = it.raw.toString() }
                            .onFailure { apiError = it.message ?: "preview_failed" }
                    }
                }
            ) { Text("Preview") }

            Button(
                onClick = {
                    val sid = sessionId
                    if (sid.isNullOrBlank()) {
                        apiError = "先 start"
                        return@Button
                    }
                    apiError = null
                    scope.launch {
                        val res = negotiationClient.commit(sid)
                        res.onSuccess {
                            lastRaw = it.raw.toString()
                            val oid = it.raw.optString("order_id").ifBlank { null }
                            orderId = oid
                        }
                            .onFailure { apiError = it.message ?: "commit_failed" }
                    }
                }
            ) { Text("Commit") }

            Button(
                onClick = {
                    val oid = orderId
                    if (oid.isNullOrBlank()) {
                        apiError = "先 commit"
                        return@Button
                    }
                    apiError = null
                    scope.launch {
                        val res = negotiationClient.execute(oid)
                        res.onSuccess { lastRaw = it.raw.toString() }
                            .onFailure { apiError = it.message ?: "execute_failed" }
                    }
                }
            ) { Text("Execute") }
        }

        startRaw?.let {
            Text(text = "start 响应：", color = Color.White.copy(alpha = 0.7f))
            Text(text = it.take(4000), color = Color.White.copy(alpha = 0.7f))
        }

        lastRaw?.let {
            Text(text = "last 响应：", color = Color.White.copy(alpha = 0.7f))
            Text(text = it.take(4000), color = Color.White.copy(alpha = 0.7f))
        }
    }
}
