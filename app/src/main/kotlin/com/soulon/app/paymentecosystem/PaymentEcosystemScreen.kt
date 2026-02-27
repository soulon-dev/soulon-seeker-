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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.util.Base64
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.soulon.app.negotiation.NegotiationClient
import com.soulon.app.negotiation.NegotiationRealtimeClient
import com.soulon.app.payment.JupiterUltraService
import com.soulon.app.payment.PaymentResult
import com.soulon.app.payment.SolanaPayManager
import com.soulon.app.wallet.MobileWalletAdapterClient
import com.soulon.app.wallet.SolanaRpcClient
import com.soulon.app.x402.X402ChallengeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun PaymentEcosystemScreen(
    onBack: () -> Unit,
    activityResultSender: ActivityResultSender,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val client = remember { NegotiationClient(context) }
    val realtime = remember { NegotiationRealtimeClient() }
    val mwaClient = remember { MobileWalletAdapterClient(context) }
    val rpcClient = remember {
        SolanaRpcClient().apply { initBackendProxy(context) }
    }
    val solanaPay = remember { SolanaPayManager(context, mwaClient, rpcClient) }
    val jupiter = remember { JupiterUltraService.getInstance(context) }

    var recipient by remember { mutableStateOf("") }
    var targetAsset by remember { mutableStateOf("USDC_BASE") }
    var payAsset by remember { mutableStateOf("SOL") }

    var sessionId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var rounds by remember { mutableStateOf<List<RoundItem>>(emptyList()) }
    var apiError by remember { mutableStateOf<String?>(null) }

    var buyerContent by remember { mutableStateOf("") }
    var buyerFeeBps by remember { mutableStateOf("") }

    var orderId by remember { mutableStateOf<String?>(null) }
    var lastPlan by remember { mutableStateOf<String?>(null) }
    var executionPlanRaw by remember { mutableStateOf<JSONObject?>(null) }
    var signingChain by remember { mutableStateOf<String?>(null) }
    var signingMessage by remember { mutableStateOf<String?>(null) }
    var isAuthorized by remember { mutableStateOf(false) }
    var lastReceipt by remember { mutableStateOf<String?>(null) }
    var walletAddress by remember { mutableStateOf<String?>(mwaClient.getCachedPublicKey()) }
    var stepStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var x402FromChallenge by remember { mutableStateOf<JSONObject?>(null) }

    fun unwrapX402Requirement(x402: JSONObject): JSONObject {
        val keys = listOf("paymentRequirement", "payment_requirement", "requirement")
        for (k in keys) {
            val v = x402.opt(k)
            if (v is JSONObject) return v
            if (v is JSONArray && v.length() > 0) {
                val first = v.optJSONObject(0)
                if (first != null) return first
            }
        }
        val arrKeys = listOf("payment_requirements", "paymentRequirements", "requirements")
        for (k in arrKeys) {
            val v = x402.opt(k)
            if (v is JSONArray && v.length() > 0) {
                val first = v.optJSONObject(0)
                if (first != null) return first
            }
        }
        return x402
    }

    fun inferTargetAsset(x402: JSONObject): String? {
        val req = unwrapX402Requirement(x402)
        val caip2 = req.optString("caip2").ifBlank { req.optString("chainId") }.ifBlank { req.optString("chain_caip2") }
        val assetId = req.optString("assetId").ifBlank { req.optString("asset_id") }.ifBlank { req.optString("asset") }
        val lower = (assetId.ifBlank { caip2 }).lowercase()
        return when {
            lower.contains("8453") || lower.contains("base") -> "USDC_BASE"
            lower.contains("137") || lower.contains("polygon") -> "USDC_POLYGON"
            lower.contains("solana") && lower.contains("sol") && !lower.contains("usdc") -> "SOL"
            lower.contains("solana") -> "USDC_SOLANA"
            else -> null
        }
    }

    fun resetFlow() {
        sessionId = null
        status = null
        rounds = emptyList()
        apiError = null
        buyerContent = ""
        buyerFeeBps = ""
        orderId = null
        lastPlan = null
        executionPlanRaw = null
        signingChain = null
        signingMessage = null
        isAuthorized = false
        lastReceipt = null
        stepStates = emptyMap()
    }

    LaunchedEffect(Unit) {
        val challenge = X402ChallengeStore.pop() ?: return@LaunchedEffect
        val json = challenge.bodyJson ?: return@LaunchedEffect
        x402FromChallenge = json
        val req = unwrapX402Requirement(json)
        val rec = req.optString("recipient").ifBlank { req.optString("address") }.ifBlank { "" }
        if (rec.isNotBlank()) recipient = rec
        inferTargetAsset(json)?.let { targetAsset = it }
    }

    fun buildMatchmakingRequest(): JSONObject? {
        val toPayAtomic = fun(amount: BigDecimal, decimals: Int): String =
            amount.movePointRight(decimals).setScale(0, RoundingMode.DOWN).toPlainString()

        val trimmed = recipient.trim()
        if (trimmed.isBlank()) return null

        val amount = BigDecimal("1.0")
        val decimals = when {
            targetAsset.contains("USDC", ignoreCase = true) -> 6
            targetAsset.contains("SOL", ignoreCase = true) -> 9
            else -> 6
        }
        val pr = JSONObject().apply {
            put("recipient", trimmed)
            put("amountAtomic", toPayAtomic(amount, decimals))
            put("decimals", decimals)
            put("nonce", "manual")
        }
        return JSONObject().apply { put("paymentRequirement", pr) }
    }

    fun parseRounds(raw: JSONObject): List<RoundItem> {
        val arr = raw.optJSONArray("rounds") ?: JSONArray()
        val out = mutableListOf<RoundItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) continue
            val role = o.optString("role").ifBlank { "system" }
            val content = o.optString("content").ifBlank { "" }
            val offer = o.optJSONObject("offer")
            val offerLine = offer?.let {
                val fee = it.optInt("fee_bps")
                val duration = it.optInt("duration_seconds")
                val route = it.optString("route_type")
                "Offer: fee ${fee}bps · ${duration}s · $route"
            }
            out.add(RoundItem(role = role, content = content, offerLine = offerLine))
        }
        return out
    }

    fun parseRoundItem(raw: JSONObject): RoundItem? {
        val role = raw.optString("role").ifBlank { "system" }
        val content = raw.optString("content").ifBlank { "" }
        val offer = raw.optJSONObject("offer")
        val offerLine = offer?.let {
            val fee = it.optInt("fee_bps")
            val duration = it.optInt("duration_seconds")
            val route = it.optString("route_type")
            "Offer: fee ${fee}bps · ${duration}s · $route"
        }
        val roundNo = raw.optInt("round", -1)
        if (roundNo < 0) return null
        return RoundItem(role = role, content = content, offerLine = offerLine)
    }

    LaunchedEffect(sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        val seen = mutableSetOf<String>()
        supervisorScope {
            launch {
                val url = client.negotiationWebSocketUrl(sid)
                realtime.observe(url).collect { text ->
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return@collect
                    val roundNo = obj.optInt("round", -1)
                    val role = obj.optString("role")
                    if (roundNo >= 0 && role.isNotBlank()) {
                        val key = "$roundNo:$role"
                        if (!seen.add(key)) return@collect
                    }
                    val r = parseRoundItem(obj) ?: return@collect
                    rounds = rounds.toMutableList().apply { add(r) }
                }
            }
            launch {
                while (true) {
                    client.session(sid).onSuccess { res ->
                        status = res.raw.optString("status").ifBlank { null }
                        rounds = parseRounds(res.raw)
                        val arr = res.raw.optJSONArray("rounds") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val r = o.optInt("round", -1)
                            val role = o.optString("role")
                            if (r >= 0 && role.isNotBlank()) seen.add("$r:$role")
                        }
                    }.onFailure {
                        apiError = it.message ?: "session_failed"
                    }
                    val s = status
                    if (s == "COMPLETED" || s == "CANCELLED" || s == "EXPIRED") break
                    delay(2000)
                }
            }
        }
    }

    fun refreshSession(sid: String) {
        scope.launch {
            client.session(sid).onSuccess { res ->
                status = res.raw.optString("status").ifBlank { null }
                rounds = parseRounds(res.raw)
            }.onFailure {
                apiError = it.message ?: "session_failed"
            }
        }
    }

    fun parsePlanSteps(plan: JSONObject): List<PlanStepItem> {
        val arr = plan.optJSONArray("steps") ?: JSONArray()
        val out = mutableListOf<PlanStepItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) continue
            val id = o.optString("step_id")
            if (id.isBlank()) continue
            out.add(
                PlanStepItem(
                    stepId = id,
                    kind = o.optString("kind").ifBlank { "UNKNOWN" },
                    chainCaip2 = o.optString("chain_caip2").ifBlank { "-" },
                    txTemplate = o.optJSONObject("tx_template"),
                )
            )
        }
        return out
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
            Text(text = "支付生态", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Button(onClick = onBack) { Text("返回") }
        }

        Text(
            text = "钱包: ${walletAddress?.take(10) ?: "-"}",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    apiError = null
                    scope.launch {
                        val res = mwaClient.signIn(activityResultSender)
                        when (res) {
                            is com.soulon.app.wallet.SignInResult.Success -> {
                                walletAddress = res.publicKey
                            }
                            is com.soulon.app.wallet.SignInResult.NoWalletFound -> {
                                apiError = "no_wallet_found"
                            }
                            is com.soulon.app.wallet.SignInResult.Error -> {
                                apiError = res.message
                            }
                        }
                    }
                }
            ) { Text("连接钱包") }
        }

        Text(
            text = "只需输入：收款地址、目标资产、支付资产",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )

        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it.trim() },
            label = { Text("收款地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { targetAsset = "USDC_BASE" }) { Text("目标：USDC(Base)") }
            Button(onClick = { targetAsset = "USDC_POLYGON" }) { Text("目标：USDC(Polygon)") }
            Button(onClick = { targetAsset = "SOL" }) { Text("目标：SOL") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { payAsset = "SOL" }) { Text("支付：SOL") }
            Button(onClick = { payAsset = "USDC_SOLANA" }) { Text("支付：USDC") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    apiError = null
                    val x402 = buildMatchmakingRequest()
                    if (x402 == null) {
                        apiError = "missing_recipient"
                        return@Button
                    }
                    resetFlow()
                    scope.launch {
                        val res = client.start(
                            sourceAsset = payAsset,
                            targetAsset = targetAsset,
                            amount = 1.0,
                            personaDigest = "TOUGH",
                            x402 = com.soulon.app.x402.MatchmakingRequest(
                                caip2ChainId = null,
                                assetId = null,
                                amountAtomic = null,
                                recipient = recipient.trim(),
                                expiresAt = null,
                                nonce = "manual",
                                raw = x402FromChallenge ?: x402,
                            ),
                        )
                        res.onSuccess {
                            sessionId = it.sessionId
                            refreshSession(it.sessionId)
                        }.onFailure {
                            apiError = it.message ?: "start_failed"
                        }
                    }
                }
            ) { Text("开始谈判") }

            Button(
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF20202A)),
                onClick = { resetFlow() }
            ) { Text("清空") }
        }

        apiError?.let { Text(text = "错误：$it", color = Color(0xFFFF6B6B)) }

        Text(
            text = "sessionId: ${sessionId ?: "-"}  status: ${status ?: "-"}",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )

        if (sessionId != null) {
            Text(text = "谈判记录", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)

            rounds.forEachIndexed { idx, r ->
                val tag = when (r.role) {
                    "buyer" -> "你"
                    "market_maker" -> "做市商"
                    else -> r.role
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "$tag · #${idx + 1}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    if (r.content.isNotBlank()) {
                        Text(text = r.content, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                    }
                    r.offerLine?.let {
                        Text(text = it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = buyerContent,
                onValueChange = { buyerContent = it },
                label = { Text("你的回应（可选）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            OutlinedTextField(
                value = buyerFeeBps,
                onValueChange = { buyerFeeBps = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("你的出价（bps，可选）") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Number
                ),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val sid = sessionId ?: return@Button
                        apiError = null
                        scope.launch {
                            client.next(
                                sessionId = sid,
                                buyerContent = buyerContent.takeIf { it.isNotBlank() },
                                buyerFeeBps = buyerFeeBps.toIntOrNull(),
                            ).onSuccess {
                                refreshSession(sid)
                            }.onFailure {
                                apiError = it.message ?: "next_failed"
                            }
                        }
                    }
                ) { Text("下一轮") }

                Button(
                    onClick = {
                        val sid = sessionId ?: return@Button
                        apiError = null
                        scope.launch {
                            client.commit(sid).onSuccess {
                                val raw = it.raw
                                orderId = raw.optString("order_id").ifBlank { null }
                                val payload = raw.optJSONObject("user_signing_payload")
                                signingChain = payload?.optString("chain")?.ifBlank { null }
                                signingMessage = payload?.optString("message")?.ifBlank { null }
                                isAuthorized = false
                                executionPlanRaw = raw.optJSONObject("execution_plan")
                                lastPlan = executionPlanRaw?.toString(2) ?: raw.toString(2)
                            }.onFailure {
                                apiError = it.message ?: "commit_failed"
                            }
                        }
                    },
                    enabled = status == "COMPLETED" || status == "NEGOTIATING"
                ) { Text("成交") }
            }
        }

        orderId?.let { oid ->
            Text(text = "orderId: $oid", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)

            Text(
                text = "授权：${if (isAuthorized) "已完成" else "未完成"}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
            if (!isAuthorized) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val chain = signingChain
                            val message = signingMessage
                            val addr = walletAddress
                            if (chain.isNullOrBlank() || message.isNullOrBlank()) {
                                apiError = "missing_signing_payload"
                                return@Button
                            }
                            if (addr.isNullOrBlank()) {
                                apiError = "wallet_not_connected"
                                return@Button
                            }
                            apiError = null
                            scope.launch {
                                when (val sig = mwaClient.signMessages(activityResultSender, arrayOf(message.toByteArray(Charsets.UTF_8)))) {
                                    is com.soulon.app.wallet.SignMessagesResult.Success -> {
                                        val first = sig.signatures.firstOrNull()
                                        if (first == null) {
                                            apiError = "empty_signature"
                                            return@launch
                                        }
                                        val sigB64 = Base64.encodeToString(first, Base64.NO_WRAP)
                                        client.authorizeOrder(
                                            orderId = oid,
                                            chain = chain,
                                            message = message,
                                            publicKey = addr,
                                            signatureBase64 = sigB64,
                                        ).onSuccess {
                                            isAuthorized = true
                                        }.onFailure {
                                            apiError = it.message ?: "authorize_failed"
                                        }
                                    }
                                    is com.soulon.app.wallet.SignMessagesResult.NoWalletFound -> {
                                        apiError = "no_wallet_found"
                                    }
                                    is com.soulon.app.wallet.SignMessagesResult.Error -> {
                                        apiError = sig.message
                                    }
                                }
                            }
                        }
                    ) { Text("授权签名") }
                }
            }
            lastPlan?.let {
                Text(text = "执行计划：", color = Color.White.copy(alpha = 0.75f))
                Text(text = it.take(4000), color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall)
            }

            executionPlanRaw?.let { plan ->
                val steps = parsePlanSteps(plan)
                if (steps.isNotEmpty()) {
                    Text(text = "步骤状态：", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
                    steps.forEach { s ->
                        val state = stepStates[s.stepId] ?: "PENDING"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${s.kind} · ${s.chainCaip2}",
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "stepId: ${s.stepId}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "status: $state",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (!isAuthorized) {
                            apiError = "missing_authorization"
                            return@Button
                        }
                        apiError = null
                        val plan = executionPlanRaw
                        if (plan == null) {
                            apiError = "missing_execution_plan"
                            return@Button
                        }
                        val steps = parsePlanSteps(plan)
                        if (steps.isEmpty()) {
                            apiError = "empty_steps"
                            return@Button
                        }
                        val addr = walletAddress
                        if (addr.isNullOrBlank()) {
                            apiError = "wallet_not_connected"
                            return@Button
                        }

                        scope.launch {
                            var states = stepStates.toMutableMap()
                            fun setState(stepId: String, state: String) {
                                states[stepId] = state
                                stepStates = states.toMap()
                            }

                            for (s in steps) {
                                setState(s.stepId, "EXECUTING")

                                val signature = when (s.kind) {
                                    "SOLANA_SWAP" -> {
                                        if (s.chainCaip2.startsWith("solana:").not()) {
                                            setState(s.stepId, "FAILED_UNSUPPORTED_CHAIN")
                                            apiError = "unsupported_chain"
                                            return@launch
                                        }
                                        val template = s.txTemplate
                                        if (template == null) {
                                            setState(s.stepId, "FAILED_MISSING_TEMPLATE")
                                            apiError = "missing_swap_template"
                                            return@launch
                                        }
                                        val inputMint = template.optString("inputMint").ifBlank { null }
                                        val outputMint = template.optString("outputMint").ifBlank { null }
                                        val amountAtomic = template.optString("amountAtomic").ifBlank { null }?.toLongOrNull()
                                        val slippageBps = template.optInt("slippageBps", 50)
                                        if (inputMint == null || outputMint == null || amountAtomic == null) {
                                            setState(s.stepId, "FAILED_INVALID_TEMPLATE")
                                            apiError = "invalid_swap_template"
                                            return@launch
                                        }
                                        val order = jupiter.getOrder(
                                            inputMint = inputMint,
                                            outputMint = outputMint,
                                            amount = amountAtomic,
                                            taker = addr,
                                            slippageBps = slippageBps,
                                        )
                                        val txB64 = order?.transaction
                                        if (txB64.isNullOrBlank()) {
                                            setState(s.stepId, "FAILED_NO_TX")
                                            apiError = jupiter.lastError.value ?: "swap_no_tx"
                                            return@launch
                                        }
                                        val txBytes = Base64.decode(txB64, Base64.DEFAULT)
                                        val send = mwaClient.signAndSendTransactions(activityResultSender, arrayOf(txBytes))
                                        when (send) {
                                            is com.soulon.app.wallet.SignAndSendResult.Success -> send.signatures.firstOrNull()
                                            is com.soulon.app.wallet.SignAndSendResult.NoWalletFound -> null
                                            is com.soulon.app.wallet.SignAndSendResult.Error -> null
                                        } ?: run {
                                            setState(s.stepId, "FAILED_SEND")
                                            apiError = "swap_send_failed"
                                            return@launch
                                        }
                                    }
                                    "SOLANA_PAY" -> {
                                        if (s.chainCaip2.startsWith("solana:").not()) {
                                            setState(s.stepId, "FAILED_UNSUPPORTED_CHAIN")
                                            apiError = "unsupported_chain"
                                            return@launch
                                        }
                                        val template = s.txTemplate
                                        if (template == null) {
                                            setState(s.stepId, "FAILED_MISSING_TEMPLATE")
                                            apiError = "missing_pay_template"
                                            return@launch
                                        }
                                        val recipientAddress = template.optString("recipient").ifBlank { null }
                                        val assetId = template.optString("assetId").ifBlank { null }
                                        val amountAtomicStr = template.optString("amountAtomic").ifBlank { null }
                                        val amountAtomic = amountAtomicStr?.toLongOrNull()
                                        if (recipientAddress == null || assetId == null || amountAtomic == null || amountAtomic <= 0L) {
                                            setState(s.stepId, "FAILED_INVALID_TEMPLATE")
                                            apiError = "invalid_pay_template"
                                            return@launch
                                        }
                                        val memo = "order:$oid step:${s.stepId}"
                                        val result = if (assetId.contains("USDC", ignoreCase = true)) {
                                            solanaPay.payToken(
                                                sender = activityResultSender,
                                                token = SolanaPayManager.PaymentToken.USDC,
                                                amount = amountAtomic,
                                                memo = memo,
                                                senderAddress = addr,
                                                recipientAddress = recipientAddress,
                                            )
                                        } else {
                                            val sol = amountAtomic.toDouble() / SolanaPayManager.LAMPORTS_PER_SOL.toDouble()
                                            solanaPay.paySol(
                                                sender = activityResultSender,
                                                amountSol = sol,
                                                memo = memo,
                                                senderAddress = addr,
                                                recipientAddress = recipientAddress,
                                            )
                                        }
                                        when (result) {
                                            is PaymentResult.Success -> result.signature
                                            is PaymentResult.NoWalletFound -> null
                                            is PaymentResult.Error -> null
                                        } ?: run {
                                            setState(s.stepId, "FAILED_PAY")
                                            apiError = "pay_failed"
                                            return@launch
                                        }
                                    }
                                    "CCTP_BURN_MINT", "BRIDGE_INTENT", "EVM_PAY" -> {
                                        client.executeStep(oid, s.stepId).onSuccess { exec ->
                                            val txHash = exec.raw.optString("tx_hash").ifBlank { null }
                                        val receipt = exec.raw.optJSONObject("receipt_v2") ?: exec.raw.optJSONObject("receipt")
                                            if (receipt != null) lastReceipt = receipt.toString(2)
                                            setState(s.stepId, "CONFIRMED")
                                            txHash ?: run { return@onSuccess }
                                        }.onFailure {
                                            setState(s.stepId, "FAILED_SERVER_EXECUTE")
                                            apiError = it.message ?: "server_execute_failed"
                                            return@launch
                                        }
                                        "server"
                                    }
                                    else -> {
                                        setState(s.stepId, "SKIPPED_UNSUPPORTED_KIND")
                                        continue
                                    }
                                }

                                if (s.kind == "SOLANA_SWAP" || s.kind == "SOLANA_PAY") {
                                    client.confirmStep(oid, s.stepId, signature).onSuccess { confirm ->
                                        val receipt = confirm.raw.optJSONObject("receipt_v2") ?: confirm.raw.optJSONObject("receipt")
                                        if (receipt != null) {
                                            lastReceipt = receipt.toString(2)
                                        }
                                        setState(s.stepId, "CONFIRMED")
                                    }.onFailure {
                                        setState(s.stepId, "FAILED_CONFIRM")
                                        apiError = it.message ?: "confirm_failed"
                                        return@launch
                                    }
                                }
                            }
                        }
                    }
                    ,
                    enabled = isAuthorized
                ) { Text("执行") }
            }
        }

        lastReceipt?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "回执：", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
            Text(text = it.take(5000), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class RoundItem(
    val role: String,
    val content: String,
    val offerLine: String?,
)

private data class PlanStepItem(
    val stepId: String,
    val kind: String,
    val chainCaip2: String,
    val txTemplate: JSONObject?,
)
