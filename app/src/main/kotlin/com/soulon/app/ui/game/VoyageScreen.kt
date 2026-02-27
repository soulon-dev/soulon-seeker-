package com.soulon.app.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.i18n.AppStrings
import com.soulon.app.config.RemoteConfigManager
import com.soulon.app.game.*
import com.soulon.app.wallet.WalletManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sqrt

@Composable
fun VoyageScreen(
    onNavigateBack: () -> Unit,
    walletAddress: String,
    activityResultSender: ActivityResultSender,
    walletManager: WalletManager,
    voyageRepository: VoyageRepository
) {
    var showExitDialog by remember { mutableStateOf(false) }
    var showMarketDialog by remember { mutableStateOf(false) }
    var showSailDialog by remember { mutableStateOf(false) }
    var showCommDialog by remember { mutableStateOf(false) }
    var showSeasonDialog by remember { mutableStateOf(false) }
    var showStarMap by remember { mutableStateOf(false) }
    var showArchivesDialog by remember { mutableStateOf(false) }
    var showShipyardDialog by remember { mutableStateOf(false) }
    var showIntro by remember { mutableStateOf(true) } // Show intro on launch
    var showEventLogDialog by remember { mutableStateOf(false) }

    var dungeonState by remember { mutableStateOf<DungeonState?>(null) }
    var seasonStatus by remember { mutableStateOf<SeasonStatusResult?>(null) }
    var leaderboard by remember { mutableStateOf<List<VoyageRepository.LeaderboardEntry>>(emptyList()) }
    
    // Game State
    var player by remember { mutableStateOf<GamePlayer?>(null) }
    var travelState by remember { mutableStateOf<TravelState?>(null) }
    var market by remember { mutableStateOf<GameMarket?>(null) }
    var inventory by remember { mutableStateOf<List<InventoryItem>>(emptyList()) }
    var ports by remember { mutableStateOf<List<GamePort>>(emptyList()) }
    var dungeons by remember { mutableStateOf<List<GameDungeon>>(emptyList()) }
    var loreEntries by remember { mutableStateOf<List<GameLore>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var authFailed by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    
    // MUD Text Output
    var displayedText by remember { mutableStateOf("") }
    val outputLog = remember { mutableStateListOf<String>() }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var newUnlock by remember { mutableStateOf<GameLore?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var actionFeedback by remember { mutableStateOf<ActionFeedback?>(null) }

    val context = LocalContext.current
    val logStore = remember(walletAddress) { VoyageEventLogStore(context, walletAddress) }
    val backendAuth = remember { BackendAuthManager.getInstance(context) }

    fun showFeedback(message: String, delta: JSONObject?, newUnlocks: List<GameLore>) {
        actionFeedback = ActionFeedback(message = message, delta = delta, newUnlocks = newUnlocks)
        if (newUnlocks.isNotEmpty()) newUnlock = newUnlocks.first()
    }

    fun appendLog(text: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newEntry = "[$timestamp] $text"
        outputLog.add(newEntry)
        logStore.append(newEntry)
        
        // Auto-scroll to bottom
        coroutineScope.launch {
            delay(100) // wait for recomposition
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Initial Load
    LaunchedEffect(retryTrigger) {
        isLoading = true
        authFailed = false
        authError = null
        outputLog.clear()
        outputLog.addAll(logStore.load())
        appendLog(AppStrings.gameLogSystemBoot)
        appendLog(AppStrings.gameLogConnectNet)

        val auth = backendAuth.ensureSession(activityResultSender, walletManager)
        if (auth.isFailure) {
            backendAuth.clear()
            authFailed = true
            authError = auth.exceptionOrNull()?.message
            player = null
            isLoading = false
            return@LaunchedEffect
        }

        appendLog("${AppStrings.gameLogAuthSuccess}: ${walletAddress.take(4)}...${walletAddress.takeLast(4)}")
        
        // Fetch data
        val p = voyageRepository.getPlayerStatus(walletAddress)
        if (p != null) {
            player = p
            appendLog("${AppStrings.gameLogPlayerSync}: ${p.name}")
            appendLog("${AppStrings.gameLogCurrentLoc}: ${p.currentPortId}")
            appendLog("${AppStrings.gameLogBalance}: ${p.money} G")
        } else {
            appendLog(AppStrings.gameLogErrorData)
        }

        val travel = voyageRepository.getTravelStatus(walletAddress)
        if (travel.success) {
            travelState = travel.travel
            if (travel.travel != null) {
                appendLog(AppStrings.tr("检测到进行中的航行。", "Detected an active voyage."))
            }
        }
        
        // Fetch Season Data
        val s = voyageRepository.getSeasonStatus()
        if (s.success && s.season != null) {
            seasonStatus = s
            appendLog("${AppStrings.gameLogSeasonSync}: ${s.season.name}")
            // Show daily news if available
            if (!s.dailyNews.isNullOrBlank()) {
                appendLog("> 📰 ${AppStrings.tr("银河日报", "Galactic news")}: ${s.dailyNews}")
            }
        }
        
        isLoading = false
    }
    
    // Refresh Market when dialog opens
    LaunchedEffect(showMarketDialog) {
        if (showMarketDialog) {
            val m = voyageRepository.getMarket(walletAddress)
            if (m != null) {
                market = m
                if (!m.event.isNullOrBlank()) {
                    appendLog("${AppStrings.gameLogMarketEvent}: ${m.event}")
                }
                // Also refresh inventory
                inventory = voyageRepository.getInventory(walletAddress)
            }
        }
    }
    
    // Refresh Ports when sail dialog opens
    LaunchedEffect(showSailDialog) {
        if (showSailDialog) {
            ports = voyageRepository.getPorts()
            dungeons = voyageRepository.getDungeons()
        }
    }

    // Refresh Lore when dialog opens
    LaunchedEffect(showArchivesDialog) {
        if (showArchivesDialog) {
            loreEntries = voyageRepository.getLoreEntries(walletAddress)
        }
    }

    // Refresh Season when dialog opens
    LaunchedEffect(showSeasonDialog) {
        if (showSeasonDialog) {
            leaderboard = voyageRepository.getLeaderboard()
        }
    }

    LaunchedEffect(travelState?.toPortId, travelState?.arriveAt, travelState?.status) {
        while (true) {
            val t = travelState ?: break
            if (t.status != "ACTIVE") break

            val nowSec = System.currentTimeMillis() / 1000
            if (nowSec >= t.arriveAt) {
                val result = voyageRepository.claimTravel(walletAddress)
                if (result.success) {
                    appendLog(result.message)
                    showFeedback(result.message, result.delta, result.newUnlocks)
                    player = voyageRepository.getPlayerStatus(walletAddress)
                    travelState = null
                } else {
                    appendLog(result.message)
                }
                break
            }

            delay(5000)
            val status = voyageRepository.getTravelStatus(walletAddress)
            if (status.success) {
                travelState = status.travel
            } else {
                break
            }
        }
    }

    // 拦截系统返回键
    BackHandler(enabled = true) {
        showExitDialog = true
    }

    // Main UI Layout
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00FF00))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = AppStrings.gameLogSystemBoot,
                        color = Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else if (player == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = if (authFailed) {
                        AppStrings.tr("需要登录", "Auth required")
                    } else {
                        AppStrings.tr("连接失败", "Connection failure")
                    },
                    color = Color.Red,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (authFailed) {
                        authError?.takeIf { it.isNotBlank() } ?: AppStrings.tr("会话已过期，请重新登录。", "Session expired. Please re-login.")
                    } else {
                        AppStrings.tr("无法建立与神经网络的连接。", "Unable to establish link with Neural Network.")
                    },
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = { retryTrigger++ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF00)),
                    shape = com.soulon.app.ui.theme.AppShapes.Button
                ) {
                    Text(
                        text = if (authFailed) {
                            AppStrings.tr("重试登录", "Retry login")
                        } else {
                            AppStrings.tr("重试连接", "Retry connection")
                        },
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextButton(onClick = onNavigateBack) {
                    Text(
                        text = AppStrings.tr("退出任务（返回）", "Abort mission (exit)"),
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            // Game Interface
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(16.dp)
                    .systemBarsPadding() // 避开状态栏
            ) {
            // Season Status Bar
            if (seasonStatus?.season != null) {
                val season = seasonStatus!!.season!!
                val progress = if (season.globalTarget > 0) season.currentProgress.toFloat() / season.globalTarget.toFloat() else 0f
                
                Surface(
                    color = Color(0xFF1A1A2E),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { showSeasonDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🌌 ${season.name}",
                            color = Color(0xFF00BFFF),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            color = Color(0xFF00BFFF),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 顶部状态栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "⚓️ ${AppStrings.gameLabelPort}: ${
                        player?.currentPortId?.removePrefix("port_")?.uppercase()
                            ?: AppStrings.tr("未知", "Unknown")
                    }",
                    color = Color(0xFF00FF00), // Terminal Green
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "💰 ${player?.money ?: 0} ${AppStrings.gameLabelMoney}",
                    color = Color(0xFFFFD700), // Gold
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showEventLogDialog = true }) {
                    Text(
                        text = AppStrings.tr("日志", "Logs"),
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (travelState != null) {
                val nowSec = System.currentTimeMillis() / 1000
                val remaining = maxOf(0, travelState!!.arriveAt - nowSec)
                val mm = remaining / 60
                val ss = remaining % 60
                val title = if (remaining == 0L) AppStrings.tr("航行到达，可结算", "Arrived, ready to claim") else AppStrings.tr("航行中", "Traveling")

                Surface(
                    shape = com.soulon.app.ui.theme.AppShapes.Card,
                    color = Color(0xFF1E1E1E),
                    border = BorderStroke(1.dp, Color(0xFF00FF00)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = Color(0xFF00FF00),
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${AppStrings.tr("目的地", "To")}: ${travelState!!.toPortId.removePrefix("port_").uppercase()}  ETA ${"%02d:%02d".format(mm, ss)}",
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (remaining == 0L) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val result = voyageRepository.claimTravel(walletAddress)
                                        if (result.success) {
                                            appendLog(result.message)
                                            showFeedback(result.message, result.delta, result.newUnlocks)
                                            player = voyageRepository.getPlayerStatus(walletAddress)
                                            travelState = null
                                        } else {
                                            appendLog(result.message)
                                        }
                                    }
                                },
                                shape = com.soulon.app.ui.theme.AppShapes.Button,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) {
                                Text(AppStrings.tr("结算", "Claim"))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            HorizontalDivider(
                color = Color.DarkGray, 
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // 游戏输出区域 (模拟终端)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                ) {
                    outputLog.forEach { line ->
                        Text(
                            text = line,
                            color = if (line.startsWith("> 错误") || line.contains("失败")) Color.Red else Color(0xFF00FF00),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 底部操作栏 - Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showMarketDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraSmall,
                    enabled = !isLoading && travelState == null
                ) {
                    Text(AppStrings.gameBtnMarket, color = Color.White, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = {
                        if (travelState != null) {
                            appendLog(AppStrings.tr("航行中，无法再次起航。", "Already traveling."))
                        } else {
                            showSailDialog = true
                            appendLog(AppStrings.gameLogNavOpen)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraSmall,
                    enabled = !isLoading && travelState == null
                ) {
                    Text(AppStrings.gameBtnSail, color = Color.White, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { showShipyardDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraSmall,
                    enabled = !isLoading && travelState == null
                ) {
                    Text(AppStrings.gameBtnShipyard, color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 底部操作栏 - Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showCommDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraSmall,
                    enabled = !isLoading
                ) {
                    Text(AppStrings.gameBtnComm, color = Color.White, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { showArchivesDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraSmall,
                    enabled = !isLoading
                ) {
                    Text(AppStrings.gameBtnArchives, color = Color.White, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { showExitDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)), // Dark Red
                    modifier = Modifier.weight(0.8f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(AppStrings.gameBtnExit, color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
        }
        }

        // Dialogs and Overlays
        if (showIntro) {
            IntroSequence(
                seasonName = seasonStatus?.season?.name ?: "INITIALIZING...",
                onComplete = { showIntro = false }
            )
        }

        if (showArchivesDialog) {
            ArchivesDialog(
                loreEntries = loreEntries,
                onDismiss = { showArchivesDialog = false }
            )
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(AppStrings.gameDialogExitTitle) },
                text = { Text(AppStrings.gameDialogExitText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            onNavigateBack()
                        }
                    ) {
                        Text(AppStrings.gameBtnExit)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(AppStrings.cancel)
                    }
                },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                textContentColor = Color.LightGray
            )
        }
        
        if (showShipyardDialog && player != null) {
            ShipyardDialog(
                player = player!!,
                onDismiss = { showShipyardDialog = false },
                onUpgrade = { type ->
                    coroutineScope.launch {
                        val result = voyageRepository.upgradeShip(walletAddress, type)
                        if (result.success) {
                            appendLog(result.message)
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            player = voyageRepository.getPlayerStatus(walletAddress)
                        } else {
                            appendLog("${AppStrings.gameLogUpgradeFail}: ${result.message}")
                        }
                    }
                },
                onRepair = {
                    coroutineScope.launch {
                        val result = voyageRepository.repairShip(walletAddress)
                        if (result.success) {
                            appendLog(result.message)
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            player = voyageRepository.getPlayerStatus(walletAddress)
                        } else {
                            appendLog(AppStrings.tr("维修失败", "Repair failed") + ": ${result.message}")
                        }
                    }
                }
            )
        }

        if (showMarketDialog && market != null && player != null) {
            MarketDialog(
                market = market!!,
                inventory = inventory,
                playerMoney = player!!.money,
                playerCapacity = player!!.cargoCapacity,
                onDismiss = { showMarketDialog = false },
                onBuy = { goodId, qty ->
                    coroutineScope.launch {
                        val result = voyageRepository.buy(walletAddress, goodId, qty)
                        if (result.success) {
                            appendLog(result.message)
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            // Refresh data
                            player = voyageRepository.getPlayerStatus(walletAddress)
                            market = voyageRepository.getMarket(walletAddress)
                            inventory = voyageRepository.getInventory(walletAddress)
                        } else {
                            appendLog("${AppStrings.gameLogBuyFail}: ${result.message}")
                        }
                    }
                },
                onSell = { goodId, qty ->
                    coroutineScope.launch {
                        val result = voyageRepository.sell(walletAddress, goodId, qty)
                        if (result.success) {
                            appendLog(result.message)
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            // Refresh data
                            player = voyageRepository.getPlayerStatus(walletAddress)
                            market = voyageRepository.getMarket(walletAddress)
                            inventory = voyageRepository.getInventory(walletAddress)
                        } else {
                            appendLog("${AppStrings.gameLogSellFail}: ${result.message}")
                        }
                    }
                },
                onMint = { goodId ->
                    coroutineScope.launch {
                        val result = voyageRepository.simulateMint(walletAddress, goodId)
                        if (result.success) {
                            appendLog("${AppStrings.gameLogMintSuccess} Asset ID: ${result.mintAddress}")
                            showFeedback(result.message, result.delta, emptyList())
                            inventory = voyageRepository.getInventory(walletAddress)
                        } else {
                            appendLog("${AppStrings.gameLogMintFail}: ${result.message}")
                        }
                    }
                }
            )
        }
        
        if (showSailDialog && ports.isNotEmpty() && player != null) {
            SailDialog(
                currentPortId = player!!.currentPortId,
                ports = ports,
                dungeons = dungeons,
                onDismiss = { showSailDialog = false },
                onOpenStarMap = {
                    showSailDialog = false
                    showStarMap = true
                    appendLog(AppStrings.gameLogNavOpen)
                },
                onSail = { targetPortId ->
                    coroutineScope.launch {
                        showSailDialog = false
                        appendLog(AppStrings.gameLogSailStart)
                        val result = voyageRepository.sail(walletAddress, targetPortId)
                        if (result.success) {
                            appendLog(result.message)
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            travelState = result.travel
                            player = voyageRepository.getPlayerStatus(walletAddress)
                        } else {
                            appendLog("${AppStrings.gameLogSailFail}: ${result.message}")
                        }
                    }
                },
                onEnterDungeon = { dungeonId ->
                    coroutineScope.launch {
                        showSailDialog = false
                        appendLog(AppStrings.gameLogDungeonEnter)
                        val result = voyageRepository.enterDungeon(walletAddress, dungeonId)
                        if (result.success) {
                            appendLog(AppStrings.gameLogDungeonWarn)
                            dungeonState = result.state
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            // Refresh player money
                            player = voyageRepository.getPlayerStatus(walletAddress)
                        } else {
                            appendLog("${AppStrings.gameLogDungeonFail}: ${result.message}")
                        }
                    }
                }
            )
        }

        if (dungeonState != null) {
            DungeonOverlay(
                state = dungeonState!!,
                onAction = { action ->
                    coroutineScope.launch {
                        val result = voyageRepository.dungeonAction(walletAddress, action)
                        if (result.success) {
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            player = voyageRepository.getPlayerStatus(walletAddress)
                            if (result.state?.status == "FAILED" || result.state?.status == "COMPLETED") {
                                appendLog("${AppStrings.gameLogDungeonEnd}: ${result.message}")
                                dungeonState = null
                            } else {
                                dungeonState = result.state
                            }
                        } else {
                            appendLog("${AppStrings.gameLogActionFail}: ${result.message}")
                        }
                    }
                }
            )
        }

        if (showCommDialog && player != null) {
            CommDialog(
                currentPortId = player!!.currentPortId,
                walletAddress = walletAddress,
                voyageRepository = voyageRepository,
                onDismiss = { showCommDialog = false },
                onLog = { text -> appendLog(text) },
                onUnlock = { lore -> newUnlock = lore }
            )
        }
        
        if (showStarMap && player != null) {
            StarMapScreen(
                currentQ = player!!.q,
                currentR = player!!.r,
                voyageRepository = voyageRepository,
                walletAddress = walletAddress,
                onClose = {
                    coroutineScope.launch {
                        player = voyageRepository.getPlayerStatus(walletAddress)
                        showStarMap = false
                    }
                },
                onEvent = { event ->
                    appendLog("> $event")
                },
                onUnlock = { lore -> newUnlock = lore }
            )
        }

        if (showSeasonDialog && seasonStatus?.season != null) {
            SeasonDialog(
                season = seasonStatus!!.season!!,
                news = seasonStatus!!.dailyNews,
                inventory = inventory,
                leaderboard = leaderboard,
                onDismiss = { showSeasonDialog = false },
                onContribute = { amount ->
                    coroutineScope.launch {
                        val result = voyageRepository.contributeToSeason(walletAddress, amount)
                        if (result.success) {
                            appendLog(result.message)
                            showFeedback(result.message, result.delta, result.newUnlocks)
                            // Refresh
                            val s = voyageRepository.getSeasonStatus()
                            if (s.success && s.season != null) {
                                seasonStatus = s
                            }
                            leaderboard = voyageRepository.getLeaderboard()
                            inventory = voyageRepository.getInventory(walletAddress)
                            player = voyageRepository.getPlayerStatus(walletAddress)
                        } else {
                            appendLog("> 提交失败: ${result.message}")
                        }
                    }
                }
            )
        }

        if (actionFeedback != null) {
            ActionFeedbackToast(
                feedback = actionFeedback!!,
                onDismiss = { actionFeedback = null }
            )
        }

        if (showEventLogDialog) {
            EventLogDialog(
                entries = outputLog,
                onDismiss = { showEventLogDialog = false },
                onClear = {
                    logStore.clear()
                    outputLog.clear()
                }
            )
        }
        
        // Notification Overlay
        if (newUnlock != null) {
            LoreUnlockToast(
                lore = newUnlock!!,
                onDismiss = { newUnlock = null }
            )
        }
    }
}

// --- Helper Composables ---

private data class ActionFeedback(
    val message: String,
    val delta: JSONObject?,
    val newUnlocks: List<GameLore>
)

@Composable
private fun ActionFeedbackToast(
    feedback: ActionFeedback,
    onDismiss: () -> Unit
) {
    LaunchedEffect(feedback) {
        delay(3500)
        onDismiss()
    }

    fun buildDeltaLines(delta: JSONObject?): List<String> {
        if (delta == null) return emptyList()
        val lines = mutableListOf<String>()
        if (delta.has("money")) {
            val amount = delta.optLong("money", 0L)
            val label = AppStrings.tr("金币变化", "Money change")
            lines.add("$label: ${if (amount >= 0) "+" else ""}$amount G")
        }
        if (delta.has("contribution")) {
            val amount = delta.optLong("contribution", 0L)
            val label = AppStrings.tr("贡献度变化", "Contribution change")
            lines.add("$label: ${if (amount >= 0) "+" else ""}$amount")
        }
        if (delta.has("cargo_capacity")) {
            val amount = delta.optLong("cargo_capacity", 0L)
            val label = AppStrings.tr("货舱容量", "Cargo capacity")
            lines.add("$label: ${if (amount >= 0) "+" else ""}$amount")
        }
        if (delta.has("ship_level")) {
            val amount = delta.optLong("ship_level", 0L)
            val label = AppStrings.tr("船体等级", "Ship level")
            lines.add("$label: ${if (amount >= 0) "+" else ""}$amount")
        }
        val inventory = delta.optJSONArray("inventory")
        if (inventory != null && inventory.length() > 0) {
            val label = AppStrings.tr("物品变化", "Inventory change")
            for (i in 0 until inventory.length()) {
                val item = inventory.optJSONObject(i) ?: continue
                val goodId = item.optString("good_id")
                val d = item.optLong("delta", 0L)
                lines.add("$label: $goodId ${if (d >= 0) "+" else ""}$d")
            }
        }
        return lines
    }

    val deltaLines = buildDeltaLines(feedback.delta)
    val unlockTitles = feedback.newUnlocks.mapNotNull { it.title.takeIf { t -> t.isNotBlank() } }.take(3)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 72.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            shape = com.soulon.app.ui.theme.AppShapes.Card,
            color = Color(0xFF1E1E1E),
            border = BorderStroke(1.dp, Color(0xFF00FF00)),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = feedback.message,
                    color = Color(0xFF00FF00),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (deltaLines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    deltaLines.forEach { line ->
                        Text(
                            text = line,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (unlockTitles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AppStrings.tr("新解锁", "New unlocks") + ": " + unlockTitles.joinToString(" · "),
                        color = Color(0xFFFFD700),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun EventLogDialog(
    entries: List<String>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = com.soulon.app.ui.theme.AppShapes.Dialog,
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AppStrings.tr("事件回放", "Event replay"),
                        color = Color(0xFF00FF00),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onClear) {
                        Text(
                            text = AppStrings.tr("清空", "Clear"),
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(12.dp))

                val list = if (entries.isEmpty()) listOf(AppStrings.tr("暂无日志。", "No logs yet.")) else entries
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list) { line ->
                        Text(
                            text = line,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.soulon.app.ui.theme.AppShapes.Button,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text(AppStrings.tr("关闭", "Close"))
                }
            }
        }
    }
}

@Composable
fun IntroSequence(
    seasonName: String,
    onComplete: () -> Unit
) {
    var textToShow by remember { mutableStateOf("") }
    val fullText = listOf(
        AppStrings.gameIntro1,
        AppStrings.gameIntro2,
        AppStrings.gameIntro3,
        "${AppStrings.gameIntro4} $seasonName",
        AppStrings.gameIntro5
    )
    
    LaunchedEffect(Unit) {
        fullText.forEach { line ->
            // Typewriter effect per line
            for (i in 1..line.length) {
                textToShow = line.take(i)
                delay(30)
            }
            delay(500)
        }
        delay(1000)
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = textToShow,
            color = Color(0xFF00FF00),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun LoreUnlockToast(
    lore: GameLore,
    onDismiss: () -> Unit
) {
    LaunchedEffect(lore) {
        delay(4000)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp), // Top padding to avoid status bar
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = Color(0xFF1E1E1E),
            border = BorderStroke(1.dp, Color(0xFFFFD700)),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clickable { onDismiss() } // Click to dismiss early
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔓", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = AppStrings.tr("新档案已解密", "New archive decrypted"),
                        color = Color(0xFFFFD700),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = lore.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun ArchivesDialog(
    loreEntries: List<GameLore>,
    onDismiss: () -> Unit
) {
    var selectedLore by remember { mutableStateOf<GameLore?>(null) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    val categories = listOf("ALL", "MAIN", "EXPLORATION", "ENTITY", "NPC")
    fun categoryLabel(category: String): String = when (category) {
        "ALL" -> AppStrings.tr("全部", "All")
        "MAIN" -> AppStrings.tr("主线", "Main")
        "EXPLORATION" -> AppStrings.tr("探索", "Exploration")
        "ENTITY" -> AppStrings.tr("实体", "Entity")
        "NPC" -> AppStrings.tr("NPC", "NPC")
        else -> category
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            if (selectedLore != null) {
                // Reading View
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = AppStrings.tr("档案", "Archive") + ": ${selectedLore!!.id}",
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = categoryLabel(selectedLore!!.category),
                            color = Color(0xFF00BFFF),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = selectedLore!!.title,
                        color = Color(0xFFFFD700),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AppStrings.tr("来源", "Source") + ": ${selectedLore!!.sourceType}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(
                            text = selectedLore!!.content,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.5
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { selectedLore = null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text(AppStrings.tr("返回列表", "Back to list"))
                    }
                }
            } else {
                // List View
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = AppStrings.tr("机密档案", "Classified archives"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Category Tabs
                    ScrollableTabRow(
                        selectedTabIndex = categories.indexOf(selectedCategory),
                        containerColor = Color.Transparent,
                        contentColor = Color(0xFF00FF00),
                        edgePadding = 0.dp
                    ) {
                        categories.forEach { category ->
                            Tab(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                text = { 
                                    Text(
                                        categoryLabel(category),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelSmall
                                    ) 
                                }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val filteredLore = if (selectedCategory == "ALL") {
                        loreEntries
                    } else {
                        loreEntries.filter { it.category == selectedCategory }
                    }
                    
                    if (filteredLore.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                if (loreEntries.isEmpty()) {
                                    AppStrings.tr("尚未解密任何档案。", "No archives decrypted yet.")
                                } else {
                                    AppStrings.tr("该分类暂无条目。", "No entries in this category.")
                                },
                                color = Color.Gray
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredLore) { lore ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                                    modifier = Modifier.clickable { selectedLore = lore }
                                ) {
                                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                                        Text(
                                            text = lore.title,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = categoryLabel(lore.category),
                                                color = Color(0xFF00BFFF),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            Text(
                                                text = AppStrings.tr("来源", "Source") + ": ${lore.sourceType}",
                                                color = Color.Gray,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) {
                        Text(AppStrings.tr("关闭", "Close"))
                    }
                }
            }
        }
    }
}

@Composable
fun SeasonDialog(
    season: GameSeason,
    news: String?,
    inventory: List<InventoryItem>,
    leaderboard: List<VoyageRepository.LeaderboardEntry>,
    onDismiss: () -> Unit,
    onContribute: (Int) -> Unit
) {
    val fragmentItem = inventory.find { it.goodId == "signal_fragment" }
    val fragmentCount = fragmentItem?.quantity ?: 0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF0F0F1A),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = AppStrings.tr("赛季情报", "Season intelligence"),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF00BFFF),
                    fontFamily = FontFamily.Monospace
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF00BFFF))
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        // Season Info
                        Text(
                            text = season.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = season.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Progress
                        Text(AppStrings.tr("全球同步进度", "Global synchronization"), color = Color(0xFF00BFFF), style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        val progress = if (season.globalTarget > 0) season.currentProgress.toFloat() / season.globalTarget.toFloat() else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF00BFFF),
                            trackColor = Color.DarkGray,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${season.currentProgress}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                            Text("${season.globalTarget}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Contribution Section
                        Surface(
                            color = Color(0xFF1A1A2E),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, Color(0xFF00BFFF))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(AppStrings.tr("贡献数据", "Contribute data"), color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(AppStrings.tr("信号碎片", "Signal fragments") + ": $fragmentCount", color = Color(0xFFFF00FF), fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { onContribute(fragmentCount) },
                                    enabled = fragmentCount > 0,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))
                                ) {
                                    Text(AppStrings.tr("全部传输", "Transmit all"))
                                }
                                if (fragmentCount == 0) {
                                    Text(AppStrings.tr("探索地牢或异常以获取碎片。", "Explore dungeons or anomalies to find fragments."), color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Daily News
                        if (!news.isNullOrBlank()) {
                            Text(AppStrings.tr("银河日报", "Galactic daily news"), color = Color(0xFFFFD700), style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = Color(0xFF1A1A1A),
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, Color(0xFF333333))
                            ) {
                                Text(
                                    text = news,
                                    modifier = Modifier.padding(12.dp),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Leaderboard
                        Text(AppStrings.tr("贡献榜", "Top contributors"), color = Color(0xFFFFD700), style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (leaderboard.isEmpty()) {
                            Text(AppStrings.tr("暂无数据。", "No data available."), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        } else {
                            leaderboard.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "#${entry.rank} ${entry.playerName}",
                                        color = if (entry.rank <= 3) Color(0xFFFFD700) else Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "${entry.totalContribution}",
                                        color = Color(0xFF00BFFF),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text(AppStrings.tr("关闭", "Close"))
                }
            }
        }
    }
}

@Composable
fun ShipyardDialog(
    player: GamePlayer,
    onDismiss: () -> Unit,
    onUpgrade: (String) -> Unit,
    onRepair: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = AppStrings.gameTitleShipyard,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFFFD700),
                    fontFamily = FontFamily.Monospace
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFFFD700))
                
                // Ship Info
                Text(AppStrings.gameLabelVesselStatus, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(AppStrings.gameLabelClass, color = Color.White)
                    Text("MK-${player.shipLevel}", color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(AppStrings.gameLabelCargo, color = Color.White)
                    Text("${player.cargoCapacity} ${AppStrings.tr("单位", "Units")}", color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Upgrades
                Text(AppStrings.gameLabelUpgrades, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(8.dp))

                val context = LocalContext.current
                val remoteConfig = remember { RemoteConfigManager.getInstance(context) }

                val cargoDelta = remoteConfig.getInt("game.econ.shipyard_cargo_delta_capacity", 10)
                val cargoCostMultiplier = remoteConfig.getInt("game.econ.shipyard_cargo_cost_per_capacity", 50)
                val cargoCost = player.cargoCapacity * cargoCostMultiplier
                val canAffordCargo = player.money >= cargoCost

                val shipLevelCostMultiplier = remoteConfig.getInt("game.econ.shipyard_ship_level_cost_multiplier", 2000)
                val levelCost = player.shipLevel * shipLevelCostMultiplier
                val canAffordLevel = player.money >= levelCost

                val repairBase = remoteConfig.getInt("game.econ.repair_base_cost", 50)
                val repairPerLevel = remoteConfig.getInt("game.econ.repair_cost_per_ship_level", 25)
                val repairCost = repairBase + maxOf(0, (player.shipLevel - 1)) * repairPerLevel
                val canAffordRepair = player.money >= repairCost
                
                // Cargo Upgrade
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                    border = BorderStroke(1.dp, if (canAffordCargo) Color(0xFF006400) else Color.Gray)
                ) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                        Text(AppStrings.tr("扩展货舱", "Expand cargo hold") + " (+$cargoDelta)", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(AppStrings.gameDescCargoUpgrade, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onUpgrade("CARGO") },
                            enabled = canAffordCargo,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
                        ) {
                            Text("${AppStrings.gameBtnInstall} (${cargoCost} G)")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Ship Level Upgrade
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                    border = BorderStroke(1.dp, if (canAffordLevel) Color(0xFF00008B) else Color.Gray)
                ) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                        Text(AppStrings.tr("升级船核心", "Upgrade ship core") + " (MK-${player.shipLevel + 1})", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(AppStrings.gameDescShipUpgrade, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onUpgrade("SHIP_LEVEL") },
                            enabled = canAffordLevel,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00008B))
                        ) {
                            Text("${AppStrings.gameBtnRetrofit} (${levelCost} G)")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                    border = BorderStroke(1.dp, if (canAffordRepair) Color(0xFF444444) else Color.Gray)
                ) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                        Text(AppStrings.tr("船坞维修", "Dock repair"), color = Color.White, fontWeight = FontWeight.Bold)
                        Text(AppStrings.tr("用于回收金币并为后续耐久系统预留入口。", "Money sink and future hull system entry point."), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRepair,
                            enabled = canAffordRepair,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text(AppStrings.tr("维修", "Repair") + " (${repairCost} G)")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text(AppStrings.gameBtnLeave)
                }
            }
        }
    }
}

@Composable
fun MarketDialog(
    market: GameMarket,
    inventory: List<InventoryItem>,
    playerMoney: Int,
    playerCapacity: Int,
    onDismiss: () -> Unit,
    onBuy: (String, Int) -> Unit,
    onSell: (String, Int) -> Unit,
    onMint: (String) -> Unit = {} 
) {
    val inventoryByGoodId = remember(inventory) { inventory.associateBy { it.goodId } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${AppStrings.gameTitleMarket} - ${market.portId.removePrefix("port_").uppercase()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFFFD700),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(market.items, key = { it.goodId }) { item ->
                        MarketItemRow(
                            item = item,
                            inventoryItem = inventoryByGoodId[item.goodId],
                            playerMoney = playerMoney,
                            onBuy = onBuy,
                            onSell = onSell,
                            onMint = onMint
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text(AppStrings.gameBtnClose)
                }
            }
        }
    }
}

@Composable
fun CommDialog(
    currentPortId: String,
    walletAddress: String,
    voyageRepository: VoyageRepository,
    onDismiss: () -> Unit,
    onLog: (String) -> Unit,
    onUnlock: (GameLore) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<Pair<String, Boolean>>() } // Text, IsUser
    var isSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(chatHistory.size) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(chatHistory.size - 1)
        }
    }
    
    // Determine NPC based on port
    val npcId = when (currentPortId) {
        "port_amsterdam" -> "archivist"
        "port_shanghai" -> "phantom"
        else -> "archivist" // Default
    }
    
    val npcName = when (npcId) {
        "archivist" -> "The Archivist"
        "phantom" -> "Neon Phantom"
        else -> AppStrings.tr("未知信号", "Unknown signal")
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header with ASCII Avatar placeholder
                Text(
                    text = "${AppStrings.gameTitleComm}: $npcName",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF00FF00),
                    fontFamily = FontFamily.Monospace
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)
                
                // Chat Area
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    state = listState
                ) {
                    itemsIndexed(chatHistory, key = { idx, _ -> idx }) { _, item ->
                        val text = item.first
                        val isUser = item.second
                        Text(
                            text = if (isUser) "> $text" else text,
                            color = if (isUser) Color.Cyan else Color(0xFF00FF00),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Input Area
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(AppStrings.tr("输入消息...", "Enter message..."), color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF00FF00),
                            focusedBorderColor = Color(0xFF00FF00),
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val msg = inputText
                            inputText = ""
                            chatHistory.add(msg to true)
                            isSending = true
                            
                            coroutineScope.launch {
                                val result = voyageRepository.interactWithNpc(walletAddress, npcId, msg)
                                isSending = false
                                if (result.success) {
                                    chatHistory.add(result.text to false)
                                    // Handle actions if any
                                    if (!result.action.isNullOrBlank()) {
                                        onLog(AppStrings.tr("NPC 动作", "NPC Action") + ": ${result.action}")
                                    }
                                    // Handle Unlocks
                                    result.newUnlocks.forEach { lore ->
                                        onUnlock(lore)
                                    }
                                } else {
                                    chatHistory.add((AppStrings.tr("连接错误", "Connection error") + ": ${result.text}") to false)
                                }
                            }
                        },
                        enabled = !isSending && inputText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400)),
                        shape = com.soulon.app.ui.theme.AppShapes.Button
                    ) {
                        Text(AppStrings.gameBtnSend)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text(AppStrings.gameBtnCloseLink)
                }
            }
        }
    }
}

@Composable
fun MarketItemRow(
    item: MarketItem,
    inventoryItem: InventoryItem?,
    playerMoney: Int,
    onBuy: (String, Int) -> Unit,
    onSell: (String, Int) -> Unit,
    onMint: (String) -> Unit
) {
    var quantity by remember { mutableStateOf(1) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${item.price} G", color = Color(0xFFFFD700))
            }
            Text("${AppStrings.gameLabelStock}: ${item.stock}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            if (inventoryItem != null) {
                Text("${AppStrings.gameLabelYouHave}: ${inventoryItem.quantity} (${AppStrings.tr("均价", "Avg")}: ${inventoryItem.avgCost} G)", color = Color(0xFF00FF00), style = MaterialTheme.typography.labelSmall)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { if (quantity > 1) quantity-- }) {
                    Icon(Icons.Default.Remove, contentDescription = AppStrings.tr("减少", "Decrease"), tint = Color.White)
                }
                Text("$quantity", color = Color.White)
                IconButton(onClick = { quantity++ }) {
                    Icon(Icons.Default.Add, contentDescription = AppStrings.tr("增加", "Increase"), tint = Color.White)
                }
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onBuy(item.goodId, quantity) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400)),
                    enabled = playerMoney >= item.price * quantity && item.stock >= quantity
                ) {
                    Text(AppStrings.gameBtnBuy)
                }
                
                Button(
                    onClick = { onSell(item.goodId, quantity) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                    enabled = (inventoryItem?.quantity ?: 0) >= quantity
                ) {
                    Text(AppStrings.gameBtnSell)
                }
            }
            
            // Mint Button (Only for specific items or rarity, simplifying for now)
            if (inventoryItem != null && inventoryItem.quantity > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onMint(item.goodId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4B0082)), // Indigo
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(AppStrings.gameBtnMint, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun DungeonOverlay(
    state: DungeonState,
    onAction: (String) -> Unit
) {
    Dialog(
        onDismissRequest = { /* Prevent dismissing by clicking outside */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.95f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Text(
                    text = AppStrings.tr("⚠️ 检测到记忆泄露 ⚠️", "⚠️ Memory breach detected ⚠️"),
                    color = Color.Red,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Status Bars
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatusIndicator(AppStrings.tr("理智", "Sanity"), state.sanity, Color(0xFF00FFFF))
                    StatusIndicator(AppStrings.tr("船体", "Hull"), state.health, Color(0xFFFF0000))
                    StatusIndicator(AppStrings.tr("深度", "Depth"), state.currentDepth * 10, Color(0xFFFFFF00)) // Fake percentage
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Room Description
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF111111))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = AppStrings.tr("扇区扫描完成：", "Sector scan complete:"),
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.currentRoomDescription,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.5
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DungeonActionButton(AppStrings.tr("移动", "Move"), Color(0xFF006400)) { onAction("MOVE") }
                    DungeonActionButton(AppStrings.tr("搜索", "Search"), Color(0xFF8B8000)) { onAction("SEARCH") }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DungeonActionButton(AppStrings.tr("攻击", "Attack"), Color(0xFF8B0000)) { onAction("ATTACK") }
                    DungeonActionButton(AppStrings.tr("撤退", "Retreat"), Color.Gray) { onAction("RETREAT") }
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = color, style = MaterialTheme.typography.labelSmall)
        Text(text = "$value%", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RowScope.DungeonActionButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = com.soulon.app.ui.theme.AppShapes.Button
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StarMapScreen(
    currentQ: Int,
    currentR: Int,
    voyageRepository: VoyageRepository,
    walletAddress: String,
    onClose: () -> Unit,
    onEvent: (String) -> Unit,
    onUnlock: (GameLore) -> Unit
) {
    var tiles by remember { mutableStateOf<List<HexTile>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    var selectedTile by remember { mutableStateOf<HexTile?>(null) }
    var q by remember { mutableStateOf(currentQ) }
    var r by remember { mutableStateOf(currentR) }
    var beaconMessage by remember { mutableStateOf("") }
    
    LaunchedEffect(currentQ, currentR) {
        q = currentQ
        r = currentR
        tiles = voyageRepository.getMapChunk(walletAddress, q, r)
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Hex Grid Canvas
                val hexSize = 60f
                var centerOffset by remember { mutableStateOf(Offset.Zero) }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { tapOffset: Offset ->
                                    val relX = tapOffset.x - centerOffset.x
                                    val relY = tapOffset.y - centerOffset.y
                                    
                                    // Pixel to Hex (Flat-topped)
                                    val qFloat = (2.0f / 3.0f * relX) / hexSize
                                    val rFloat = (-1.0f / 3.0f * relX + 1.73205f / 3.0f * relY) / hexSize
                                    
                                    // Cube rounding
                                    var rx = qFloat
                                    var ry = rFloat
                                    var rz = -rx - ry
                                    
                                    var ix = rx.roundToInt()
                                    var iy = ry.roundToInt()
                                    var iz = rz.roundToInt()
                                    
                                    val xDiff = abs(ix - rx)
                                    val yDiff = abs(iy - ry)
                                    val zDiff = abs(iz - rz)
                                    
                                    if (xDiff > yDiff && xDiff > zDiff) {
                                        ix = -iy - iz
                                    } else if (yDiff > zDiff) {
                                        iy = -ix - iz
                                    } else {
                                        iz = -ix - iy
                                    }
                                    
                                    val clickedQ = ix + q
                                    val clickedR = iy + r
                                    
                                    selectedTile = tiles.find { it.q == clickedQ && it.r == clickedR }
                                }
                            )
                        }
                ) {
                    centerOffset = Offset(size.width / 2, size.height / 2)
                    val centerX = centerOffset.x
                    val centerY = centerOffset.y

                    tiles.forEach { tile ->
                        // Convert axial to pixel
                        val relQ = (tile.q - q).toFloat()
                        val relR = (tile.r - r).toFloat()
                        
                        val x = centerX + hexSize * 1.5f * relQ
                        val y = centerY + hexSize * 1.732f * (relR + relQ / 2f)

                        // Draw Hexagon
                        val path = Path().apply {
                            for (i in 0..5) {
                                val deg = 60.0 * i
                                val rad = Math.toRadians(deg)
                                val cosVal = kotlin.math.cos(rad).toFloat()
                                val sinVal = kotlin.math.sin(rad).toFloat()
                                val px = x + hexSize * cosVal
                                val py = y + hexSize * sinVal
                                if (i == 0) moveTo(px, py) else lineTo(px, py)
                            }
                            close()
                        }
                        
                        val color = when(tile.type) {
                            "SAFE_ZONE" -> Color(0xFF00FF00)
                            "ASTEROID" -> Color(0xFF8B4513)
                            "NEBULA" -> Color(0xFF800080)
                            "ANOMALY" -> Color(0xFFFF0000)
                            else -> Color.DarkGray
                        }

                        val isSelected = selectedTile?.q == tile.q && selectedTile?.r == tile.r
                        val fillColor = if (!tile.isExplored) {
                            Color.Gray.copy(alpha = 0.3f)
                        } else if (tile.visitCount > 1) {
                            color.copy(alpha = 0.7f)
                        } else {
                            color
                        }
                        
                        drawPath(
                            path = path,
                            color = fillColor,
                            style = Fill
                        )
                        drawPath(
                            path = path,
                            color = if (isSelected) Color.Cyan else Color.White,
                            style = Stroke(width = if (isSelected) 4f else 2f)
                        )

                        if (tile.hasBeacon) {
                            drawCircle(
                                color = Color.Yellow,
                                radius = 6f,
                                center = Offset(x, y)
                            )
                        }
                    }
                }
                
                // Controls Overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.8f), com.soulon.app.ui.theme.AppShapes.Card)
                        .padding(16.dp)
                ) {
                    Text(AppStrings.gameTitleNav, color = Color.Cyan, fontFamily = FontFamily.Monospace)
                    Text("${AppStrings.gameLabelCurrentLoc}: [$q, $r]", color = Color.White)
                    
                    fun executeMove(targetQ: Int, targetR: Int) {
                        coroutineScope.launch {
                            val result = voyageRepository.move(walletAddress, targetQ, targetR)
                            if (!result.success) {
                                onEvent("${AppStrings.gameLogSailFail}: ${result.message}")
                                return@launch
                            }
                            q = targetQ
                            r = targetR
                            tiles = voyageRepository.getMapChunk(walletAddress, q, r)
                            result.event?.takeIf { it.isNotBlank() }?.let { onEvent(it) }
                            if (result.newUnlocks.isNotEmpty()) onUnlock(result.newUnlocks.first())
                            selectedTile = null
                        }
                    }

                    if (selectedTile != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(AppStrings.tr("已选择", "Selected") + ": [${selectedTile!!.q}, ${selectedTile!!.r}]", color = Color.Cyan)
                        Text(AppStrings.tr("类型", "Type") + ": ${selectedTile!!.type}", color = Color.Yellow, style = MaterialTheme.typography.labelSmall)
                        Text("${AppStrings.gameLabelRisk}: ${selectedTile!!.difficulty}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        if (selectedTile!!.isExplored) {
                            Text(AppStrings.tr("访问次数", "Visits") + ": ${selectedTile!!.visitCount}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        }
                        if (selectedTile!!.hasBeacon) {
                            Text(AppStrings.tr("信标", "Beacon") + ": " + AppStrings.tr("存在", "Present"), color = Color.Yellow, style = MaterialTheme.typography.labelSmall)
                        }

                        val isCurrent = selectedTile!!.q == q && selectedTile!!.r == r
                        if (isCurrent && selectedTile!!.isExplored) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextField(
                                value = beaconMessage,
                                onValueChange = { beaconMessage = it.take(200) },
                                label = { Text(AppStrings.tr("信标内容", "Beacon message")) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF1E1E1E),
                                    unfocusedContainerColor = Color(0xFF1E1E1E),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedLabelColor = Color.Cyan,
                                    unfocusedLabelColor = Color.Gray
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val result = voyageRepository.placeBeacon(walletAddress, beaconMessage)
                                        if (result.success) {
                                            onEvent(result.message)
                                            tiles = voyageRepository.getMapChunk(walletAddress, q, r)
                                            beaconMessage = ""
                                            selectedTile = null
                                        } else {
                                            onEvent(result.message)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                shape = com.soulon.app.ui.theme.AppShapes.Button
                            ) {
                                Text(AppStrings.tr("部署信标", "Deploy beacon"), fontFamily = FontFamily.Monospace)
                            }
                        }
                        
                        val dq = selectedTile!!.q - q
                        val dr = selectedTile!!.r - r
                        val dist = (abs(dq) + abs(dq + dr) + abs(dr)) / 2
                        
                        if (dist == 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { executeMove(selectedTile!!.q, selectedTile!!.r) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
                            ) {
                                Text(AppStrings.gameBtnJump)
                            }
                        } else if (dist == 0) {
                            Text(AppStrings.gameLabelCurrentLoc, color = Color.Green, style = MaterialTheme.typography.labelSmall)
                        } else {
                            Text(AppStrings.trf("距离过远（范围：%d）", "Too far (Range: %d)", dist), color = Color.Red, style = MaterialTheme.typography.labelSmall)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Directional Buttons (Relative Move)
                    fun launchMove(dq: Int, dr: Int) {
                        executeMove(q + dq, r + dr)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = { launchMove(0, -1) }) { Text("NW") }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = { launchMove(1, -1) }) { Text("NE") }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = { launchMove(-1, 0) }) { Text("W") }
                        Button(onClick = { launchMove(1, 0) }) { Text("E") }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = { launchMove(-1, 1) }) { Text("SW") }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = { launchMove(0, 1) }) { Text("SE") }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) { Text(AppStrings.gameBtnExit) }
                }
            }
        }
    }
}

@Composable
fun SailDialog(
    currentPortId: String,
    ports: List<GamePort>,
    dungeons: List<GameDungeon>,
    onDismiss: () -> Unit,
    onOpenStarMap: () -> Unit,
    onSail: (String) -> Unit,
    onEnterDungeon: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = AppStrings.gameTitleNav,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF00FFFF),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF00334D)),
                            modifier = Modifier.clickable { onOpenStarMap() },
                            border = BorderStroke(1.dp, Color(0xFF00BFFF))
                        ) {
                            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                Text(
                                    text = "🗺️ ${AppStrings.tr("星图", "Star map")}",
                                    color = Color(0xFF00BFFF),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = AppStrings.tr("探索周边扇区", "Explore the surrounding sectors"),
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    items(ports) { port ->
                        val isCurrent = port.id == currentPortId
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) Color(0xFF004400) else Color(0xFF2D2D2D)
                            ),
                            modifier = Modifier.clickable(enabled = !isCurrent) {
                                if (!isCurrent) onSail(port.id)
                            }
                        ) {
                            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                Text(
                                    text = port.name, 
                                    color = if (isCurrent) Color(0xFF00FF00) else Color.White, 
                                    fontWeight = FontWeight.Bold
                                )
                                Text(port.description, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                if (isCurrent) {
                                    Text(AppStrings.gameLabelCurrentLoc, color = Color(0xFF00FF00), style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text("${AppStrings.gameLabelTravelCost}: 50 G", color = Color(0xFFFFD700), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    
                    // Dynamic Dungeons
                    items(dungeons) { dungeon ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF330000)),
                            modifier = Modifier.clickable { onEnterDungeon(dungeon.id) },
                            border = BorderStroke(1.dp, Color.Red)
                        ) {
                            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                Text(
                                    text = "⚠️ ${dungeon.name.uppercase()}", 
                                    color = Color.Red, 
                                    fontWeight = FontWeight.Bold
                                )
                                Text(dungeon.description, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                Text("${AppStrings.gameLabelRisk}: LV.${dungeon.difficultyLevel} | ${AppStrings.gameLabelCost}: ${dungeon.entryCost} G", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text(AppStrings.gameBtnClose)
                }
            }
        }
    }
}
