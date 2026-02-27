
package com.soulon.app.game

import android.content.Context
import android.util.Log
import com.soulon.app.BuildConfig
import com.soulon.app.auth.BackendAuthManager
import com.soulon.app.i18n.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class VoyageRepository(private val context: Context) {

    companion object {
        private const val TAG = "VoyageRepository"
        private val BASE_URL = BuildConfig.BACKEND_BASE_URL
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
    }

    private fun clearBackendSession() {
        BackendAuthManager.getInstance(context).clear()
    }

    private val cachePrefs = context.getSharedPreferences("voyage_cache", Context.MODE_PRIVATE)

    private fun cacheKey(walletAddress: String, suffix: String): String {
        return "cache_${walletAddress.lowercase()}_$suffix"
    }

    private fun cacheGet(key: String): String? {
        return cachePrefs.getString(key, null)
    }

    private fun cachePut(key: String, value: String) {
        cachePrefs.edit().putString(key, value).apply()
    }

    private fun cachedDataLabel(): String {
        val code = LocaleManager.getSavedLanguageCode(context) ?: LocaleManager.getDefaultLanguageCode(context)
        return if (code == "zh") "离线缓存数据" else "Offline cached data"
    }

    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 2,
        initialDelayMs: Long = 300,
        block: suspend () -> T
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) throw e
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(2000)
            }
        }
    }

    suspend fun getPlayerStatus(walletAddress: String): GamePlayer? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/status")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parsePlayerResponse(response)
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                Log.e(TAG, "Get player status failed: 401 (token cleared, require re-login)")
                null
            } else if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.w(TAG, "Player status 404, attempting to ensure user exists...")
                if (ensureUserExists(walletAddress)) {
                    // Retry status fetch
                    val retryConn = createConnection(url, "GET")
                    if (retryConn.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = retryConn.inputStream.bufferedReader().use { it.readText() }
                        parsePlayerResponse(response)
                    } else {
                        Log.e(TAG, "Retry get player status failed: ${retryConn.responseCode}")
                        null
                    }
                } else {
                    null
                }
            } else {
                Log.e(TAG, "Get player status failed: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get player status error", e)
            null
        }
    }

    private suspend fun ensureUserExists(walletAddress: String): Boolean {
        try {
            // Force refresh token first to fix potential 401
            val authManager = BackendAuthManager.getInstance(context)
            if (authManager.getAccessToken().isNullOrBlank()) {
                // Try to restore session if token is missing
                // Note: We can't easily call ensureSession(activity) here as we are in Repository.
                // But we can try to rely on the sync call below which might trigger some backend side effects or at least verify connectivity.
                Log.w(TAG, "Token missing in ensureUserExists")
            }
            
            // First try calling sync full-profile which is robust for creation
            val urlSync = URL("$BASE_URL/api/v1/users/full-profile")
            val connSync = createConnection(urlSync, "GET")
            if (connSync.responseCode == HttpURLConnection.HTTP_OK) {
                return true
            }

            // Fallback: Call getRealTimeBalance to trigger user creation in backend
            val url = URL("$BASE_URL/api/v1/user/$walletAddress/balance")
            val connection = createConnection(url, "GET")
            return connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "Ensure user exists failed", e)
            return false
        }
    }

    private fun parsePlayerResponse(response: String): GamePlayer {
        val json = JSONObject(response)
        val p = json.getJSONObject("player")
        return GamePlayer(
            id = p.getInt("id"),
            name = p.getString("name"),
            money = p.getInt("money"),
            currentPortId = p.getString("current_port_id"),
            shipLevel = p.getInt("ship_level"),
            cargoCapacity = p.getInt("cargo_capacity"),
            q = p.optInt("q", 0),
            r = p.optInt("r", 0)
        )
    }

    private fun parseTravelState(obj: JSONObject?): TravelState? {
        if (obj == null) return null
        return TravelState(
            fromPortId = obj.optString("from_port_id"),
            toPortId = obj.optString("to_port_id"),
            departAt = obj.optLong("depart_at", 0),
            arriveAt = obj.optLong("arrive_at", 0),
            status = obj.optString("status", "ACTIVE"),
            travelCost = obj.optInt("travel_cost", 0),
            encounterEvent = obj.optString("encounter_event").takeIf { it.isNotBlank() },
            encounterMoneyDelta = obj.optInt("encounter_money_delta", 0)
        )
    }

    suspend fun getMarket(walletAddress: String): GameMarket? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/market")
            val response = retryWithBackoff {
                val connection = createConnection(url, "GET")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    clearBackendSession()
                    throw IllegalStateException("Unauthorized")
                } else {
                    null
                }
            }
            if (response != null) {
                cachePut(cacheKey(walletAddress, "market"), response)
                val json = JSONObject(response)
                val portId = json.getString("portId")
                val event = json.optString("event").takeIf { it.isNotBlank() }
                val itemsArray = json.getJSONArray("market")

                val items = (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    MarketItem(
                        id = item.getInt("id"),
                        goodId = item.getString("good_id"),
                        name = item.optString("name", item.getString("good_id")),
                        price = item.getInt("price"),
                        stock = item.getInt("stock")
                    )
                }
                return@withContext GameMarket(portId, items, event)
            }

            val cached = cacheGet(cacheKey(walletAddress, "market"))
            if (cached != null) {
                val json = JSONObject(cached)
                val portId = json.getString("portId")
                val event = cachedDataLabel()
                val itemsArray = json.getJSONArray("market")
                val items = (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    MarketItem(
                        id = item.getInt("id"),
                        goodId = item.getString("good_id"),
                        name = item.optString("name", item.getString("good_id")),
                        price = item.getInt("price"),
                        stock = item.getInt("stock")
                    )
                }
                return@withContext GameMarket(portId, items, event)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Get market error", e)
            if (e is IllegalStateException && e.message == "Unauthorized") {
                return@withContext null
            }
            val cached = cacheGet(cacheKey(walletAddress, "market"))
            if (cached != null) {
                return@withContext try {
                    val json = JSONObject(cached)
                    val portId = json.getString("portId")
                    val event = cachedDataLabel()
                    val itemsArray = json.getJSONArray("market")
                    val items = (0 until itemsArray.length()).map { i ->
                        val item = itemsArray.getJSONObject(i)
                        MarketItem(
                            id = item.getInt("id"),
                            goodId = item.getString("good_id"),
                            name = item.optString("name", item.getString("good_id")),
                            price = item.getInt("price"),
                            stock = item.getInt("stock")
                        )
                    }
                    GameMarket(portId, items, event)
                } catch (_: Exception) {
                    null
                }
            }
            null
        }
    }

    suspend fun getInventory(walletAddress: String): List<InventoryItem> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/inventory")
            val response = retryWithBackoff {
                val connection = createConnection(url, "GET")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    clearBackendSession()
                    throw IllegalStateException("Unauthorized")
                } else {
                    null
                }
            }
            if (response != null) {
                cachePut(cacheKey(walletAddress, "inventory"), response)
                val json = JSONObject(response)
                val itemsArray = json.getJSONArray("inventory")

                return@withContext (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    InventoryItem(
                        goodId = item.getString("good_id"),
                        name = item.optString("name", item.getString("good_id")),
                        quantity = item.getInt("quantity"),
                        avgCost = item.getDouble("avg_cost").toInt()
                    )
                }
            }

            val cached = cacheGet(cacheKey(walletAddress, "inventory"))
            if (cached != null) {
                val json = JSONObject(cached)
                val itemsArray = json.getJSONArray("inventory")
                return@withContext (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    InventoryItem(
                        goodId = item.getString("good_id"),
                        name = item.optString("name", item.getString("good_id")),
                        quantity = item.getInt("quantity"),
                        avgCost = item.getDouble("avg_cost").toInt()
                    )
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Get inventory error", e)
            if (e is IllegalStateException && e.message == "Unauthorized") {
                return@withContext emptyList()
            }
            val cached = cacheGet(cacheKey(walletAddress, "inventory"))
            if (cached != null) {
                return@withContext try {
                    val json = JSONObject(cached)
                    val itemsArray = json.getJSONArray("inventory")
                    (0 until itemsArray.length()).map { i ->
                        val item = itemsArray.getJSONObject(i)
                        InventoryItem(
                            goodId = item.getString("good_id"),
                            name = item.optString("name", item.getString("good_id")),
                            quantity = item.getInt("quantity"),
                            avgCost = item.getDouble("avg_cost").toInt()
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            emptyList()
        }
    }
    
    suspend fun getPorts(): List<GamePort> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/ports")
            val response = retryWithBackoff {
                val connection = createConnection(url, "GET")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    clearBackendSession()
                    throw IllegalStateException("Unauthorized")
                } else {
                    null
                }
            }
            if (response != null) {
                cachePut("cache_ports", response)
                val json = JSONObject(response)
                val itemsArray = json.getJSONArray("ports")
                return@withContext (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    GamePort(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        description = item.optString("description", "")
                    )
                }
            }

            val cached = cacheGet("cache_ports")
            if (cached != null) {
                val json = JSONObject(cached)
                val itemsArray = json.getJSONArray("ports")
                return@withContext (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    GamePort(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        description = item.optString("description", "")
                    )
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Get ports error", e)
            if (e is IllegalStateException && e.message == "Unauthorized") {
                return@withContext emptyList()
            }
            val cached = cacheGet("cache_ports")
            if (cached != null) {
                return@withContext try {
                    val json = JSONObject(cached)
                    val itemsArray = json.getJSONArray("ports")
                    (0 until itemsArray.length()).map { i ->
                        val item = itemsArray.getJSONObject(i)
                        GamePort(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            description = item.optString("description", "")
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            emptyList()
        }
    }

    suspend fun getDungeons(): List<GameDungeon> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/dungeons")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val itemsArray = json.getJSONArray("dungeons")
                
                (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    GameDungeon(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        description = item.getString("description"),
                        difficultyLevel = item.getInt("difficulty_level"),
                        entryCost = item.getInt("entry_cost")
                    )
                }
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                Log.e(TAG, "Get dungeons failed: 401 (token cleared, require re-login)")
                emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get dungeons error", e)
            emptyList()
        }
    }

    suspend fun buy(walletAddress: String, goodId: String, quantity: Int): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/buy")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("goodId", goodId)
                put("quantity", quantity)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = newUnlocks
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Purchase failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun sell(walletAddress: String, goodId: String, quantity: Int): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/sell")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("goodId", goodId)
                put("quantity", quantity)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    profit = json.optInt("profit"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = newUnlocks
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Sale failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }
    
    suspend fun sail(walletAddress: String, targetPortId: String): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/sail")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("targetPortId", targetPortId)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                val travel = parseTravelState(json.optJSONObject("travel"))
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = newUnlocks,
                    travel = travel
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Sail failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun getTravelStatus(walletAddress: String): TravelStatusResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/travel/status")
            val connection = createConnection(url, "GET")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val travel = parseTravelState(json.optJSONObject("travel"))
                TravelStatusResult(success = true, travel = travel)
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TravelStatusResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TravelStatusResult(success = false, message = json.optString("error", "Travel status failed"))
            }
        } catch (e: Exception) {
            TravelStatusResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun claimTravel(walletAddress: String): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/travel/claim")
            val connection = createConnection(url, "POST")
            connection.outputStream.bufferedWriter().use { it.write("{}") }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                val travel = parseTravelState(json.optJSONObject("travel"))
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = newUnlocks,
                    travel = travel
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Claim failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun interactWithNpc(walletAddress: String, npcId: String, message: String): NpcInteractionResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/interact")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("npcId", npcId)
                put("message", message)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                NpcInteractionResult(
                    success = true,
                    text = json.optString("text"),
                    action = json.optString("action"),
                    mood = json.optString("mood"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                NpcInteractionResult(success = false, text = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                NpcInteractionResult(success = false, text = json.optString("error", "Connection lost..."))
            }
        } catch (e: Exception) {
            NpcInteractionResult(success = false, text = e.message ?: "Signal disrupted...")
        }
    }

    suspend fun enterDungeon(walletAddress: String, dungeonId: String): DungeonResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/dungeon/enter")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("dungeonId", dungeonId)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                DungeonResult(
                    success = true,
                    message = json.optString("message"),
                    state = parseDungeonState(json.optJSONObject("state")),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                DungeonResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                DungeonResult(success = false, message = json.optString("error", "Entry failed"))
            }
        } catch (e: Exception) {
            DungeonResult(success = false, message = e.message ?: "Connection error")
        }
    }

    suspend fun dungeonAction(walletAddress: String, action: String): DungeonResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/dungeon/action")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("action", action)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                DungeonResult(
                    success = true,
                    message = json.optString("message"),
                    state = parseDungeonState(json.optJSONObject("state")),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                DungeonResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                DungeonResult(success = false, message = json.optString("error", "Action failed"))
            }
        } catch (e: Exception) {
            DungeonResult(success = false, message = e.message ?: "Connection error")
        }
    }

    data class LeaderboardEntry(
        val rank: Int,
        val playerName: String,
        val totalContribution: Int
    )

    suspend fun getLeaderboard(): List<LeaderboardEntry> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/season/leaderboard")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val itemsArray = json.getJSONArray("leaderboard")
                
                (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    LeaderboardEntry(
                        rank = item.getInt("rank"),
                        playerName = item.getString("player_name"),
                        totalContribution = item.getInt("total_contribution")
                    )
                }
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                Log.e(TAG, "Get leaderboard failed: 401 (token cleared, require re-login)")
                emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get leaderboard error", e)
            emptyList()
        }
    }

    suspend fun getSeasonStatus(): SeasonStatusResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/season/status")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                val seasonJson = json.getJSONObject("season")
                val worldStateJson = json.getJSONObject("worldState")
                
                SeasonStatusResult(
                    success = true,
                    season = GameSeason(
                        id = seasonJson.getString("id"),
                        name = seasonJson.getString("name"),
                        description = seasonJson.getString("description"),
                        globalTarget = seasonJson.getLong("global_target"),
                        currentProgress = seasonJson.getLong("current_progress"),
                        endTime = seasonJson.getLong("end_time")
                    ),
                    dailyNews = worldStateJson.optString("daily_news_summary", "No news today.")
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                SeasonStatusResult(success = false, error = "Unauthorized")
            } else {
                SeasonStatusResult(success = false, error = "Failed to fetch season data")
            }
        } catch (e: Exception) {
            SeasonStatusResult(success = false, error = e.message ?: "Network error")
        }
    }

    suspend fun getMapChunk(walletAddress: String, q: Int, r: Int): List<HexTile> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/map/chunk?q=$q&r=$r")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val tilesArray = json.getJSONArray("tiles")
                
                (0 until tilesArray.length()).map { i ->
                    val item = tilesArray.getJSONObject(i)
                    HexTile(
                        q = item.getInt("q"),
                        r = item.getInt("r"),
                        type = item.getString("type"),
                        difficulty = item.getInt("difficulty"),
                        isExplored = item.getBoolean("isExplored"),
                        hasBeacon = item.getBoolean("hasBeacon"),
                        visitedAt = item.optLong("visitedAt", 0),
                        visitCount = item.optInt("visitCount", 0)
                    )
                }
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                Log.e(TAG, "Get map chunk failed: 401 (token cleared, require re-login)")
                emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun move(walletAddress: String, q: Int, r: Int): MoveResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/move")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("q", q)
                put("r", r)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                MoveResult(
                    success = true,
                    message = json.optString("message"),
                    event = json.optString("event").takeIf { it.isNotBlank() },
                    delta = json.optJSONObject("delta"),
                    newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                MoveResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                MoveResult(success = false, message = json.optString("error", "Movement failed"))
            }
        } catch (e: Exception) {
            MoveResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun placeBeacon(walletAddress: String, message: String): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/map/beacon")
            val connection = createConnection(url, "POST")

            val payload = JSONObject().apply {
                put("message", message)
            }

            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Beacon failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }

    data class ShipEligibility(
        val hasNft: Boolean,
        val requireNft: Boolean,
        val mintEnabled: Boolean,
        val soulboundMode: String? = null,
        val mintedAt: Long? = null,
        val mintSignature: String? = null,
        val metadataUri: String? = null,
        val queueCount: Long? = null,
        val recipient: String? = null,
        val priceLamports: Long? = null,
        val startAt: Long? = null
    )

    data class ShipMintTx(
        val transactionBase64: String,
        val assetAddress: String,
        val candyMachine: String,
        val startAt: Long
    )

    data class ShipMintConfirm(
        val success: Boolean,
        val hasNft: Boolean,
        val mintedAt: Long? = null,
        val mintSignature: String? = null,
        val metadataUri: String? = null,
        val message: String? = null
    )

    suspend fun getShipEligibility(walletAddress: String): ShipEligibility? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/ship/eligibility")
            val connection = createConnection(url, "GET")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val mint = json.optJSONObject("mint")
                ShipEligibility(
                    hasNft = json.optBoolean("hasNft", false),
                    requireNft = json.optBoolean("requireNft", true),
                    mintEnabled = json.optBoolean("mintEnabled", false),
                    soulboundMode = json.optString("soulboundMode").takeIf { it.isNotBlank() },
                    mintedAt = json.optLong("mintedAt", 0L).takeIf { it > 0 },
                    mintSignature = json.optString("mintSignature").takeIf { it.isNotBlank() },
                    metadataUri = json.optString("metadataUri").takeIf { it.isNotBlank() },
                    queueCount = json.optLong("queueCount", -1L).takeIf { it >= 0 },
                    recipient = mint?.optString("recipient")?.takeIf { !it.isNullOrBlank() },
                    priceLamports = mint?.optLong("priceLamports", 0L)?.takeIf { it > 0 },
                    startAt = mint?.optLong("startAt", 0L)?.takeIf { it >= 0 }
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun requestShipMintTx(walletAddress: String): Result<ShipMintTx> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/ship/mint/tx")
            val connection = createConnection(url, "POST")
            connection.outputStream.bufferedWriter().use { it.write("{}") }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            }
            if (code in 200..299) {
                val json = JSONObject(body)
                Result.success(
                    ShipMintTx(
                        transactionBase64 = json.getString("transactionBase64"),
                        assetAddress = json.getString("assetAddress"),
                        candyMachine = json.getString("candyMachine"),
                        startAt = json.optLong("startAt", 0L)
                    )
                )
            } else {
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                Result.failure(Exception(json.optString("error", "Mint tx failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun confirmShipMint(walletAddress: String, signature: String, assetAddress: String): Result<ShipMintConfirm> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/ship/mint/confirm")
            val connection = createConnection(url, "POST")
            val payload = JSONObject().apply {
                put("signature", signature)
                put("assetAddress", assetAddress)
            }
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            }
            if (code in 200..299) {
                val json = JSONObject(body)
                Result.success(
                    ShipMintConfirm(
                        success = json.optBoolean("success", true),
                        hasNft = json.optBoolean("hasNft", false),
                        mintedAt = json.optLong("mintedAt", 0L).takeIf { it > 0 },
                        mintSignature = json.optString("mintSignature").takeIf { it.isNotBlank() },
                        metadataUri = json.optString("metadataUri").takeIf { it.isNotBlank() },
                        message = json.optString("message").takeIf { it.isNotBlank() }
                    )
                )
            } else {
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                Result.failure(Exception(json.optString("error", "Confirm failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class MyAsset(
        val kind: String,
        val name: String,
        val assetAddress: String? = null,
        val metadataUri: String? = null,
        val mintedAt: Long? = null,
        val mintSignature: String? = null
    )

    suspend fun getMyAssets(walletAddress: String): List<MyAsset> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/my-assets")
            val connection = createConnection(url, "GET")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val arr = json.optJSONArray("assets") ?: return@withContext emptyList<MyAsset>()
                val out = ArrayList<MyAsset>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        MyAsset(
                            kind = o.optString("kind"),
                            name = o.optString("name"),
                            assetAddress = o.optString("assetAddress").takeIf { it.isNotBlank() },
                            metadataUri = o.optString("metadataUri").takeIf { it.isNotBlank() },
                            mintedAt = o.optLong("mintedAt", 0L).takeIf { it > 0 },
                            mintSignature = o.optString("mintSignature").takeIf { it.isNotBlank() }
                        )
                    )
                }
                out
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun subscribeShipMintNotify(startAt: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/ship/mint/notify/subscribe")
            val connection = createConnection(url, "POST")
            val payload = JSONObject().apply {
                put("startAt", startAt)
            }
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun simulateMint(walletAddress: String, goodId: String): MintResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/mint/simulate")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("goodId", goodId)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                MintResult(
                    success = true,
                    message = json.getString("message"),
                    mintAddress = json.getString("mintAddress"),
                    delta = json.optJSONObject("delta")
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                MintResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                MintResult(success = false, message = json.optString("error", "Mint failed"))
            }
        } catch (e: Exception) {
            MintResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun contributeToSeason(walletAddress: String, amount: Int): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/season/contribute")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("amount", amount)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = newUnlocks
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Contribution failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun upgradeShip(walletAddress: String, type: String): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/shipyard/upgrade")
            val connection = createConnection(url, "POST")
            
            val payload = JSONObject().apply {
                put("type", type)
            }
            
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = newUnlocks
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Upgrade failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun repairShip(walletAddress: String): TradeResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/shipyard/repair")
            val connection = createConnection(url, "POST")
            connection.outputStream.bufferedWriter().use { it.write("{}") }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newUnlocks = parseNewUnlocks(json.optJSONArray("new_unlocks"))
                TradeResult(
                    success = true,
                    message = json.optString("event").takeIf { it.isNotBlank() } ?: json.optString("message"),
                    delta = json.optJSONObject("delta"),
                    newUnlocks = newUnlocks
                )
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                TradeResult(success = false, message = "Unauthorized")
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val json = try { JSONObject(error ?: "{}") } catch (e: Exception) { JSONObject() }
                TradeResult(success = false, message = json.optString("error", "Repair failed"))
            }
        } catch (e: Exception) {
            TradeResult(success = false, message = e.message ?: "Network error")
        }
    }

    suspend fun getLoreEntries(walletAddress: String): List<GameLore> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v1/game/lore")
            val connection = createConnection(url, "GET")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val loreArray = json.getJSONArray("lore")
                
                (0 until loreArray.length()).map { i ->
                    val item = loreArray.getJSONObject(i)
                    GameLore(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        content = item.getString("content"),
                        unlockThreshold = item.optInt("unlock_threshold", 0),
                        category = item.optString("category", "MAIN"),
                        sourceType = item.optString("source_type", "CONTRIBUTION"),
                        unlockedAt = item.optLong("unlocked_at", 0)
                    )
                }
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearBackendSession()
                emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseNewUnlocks(jsonArray: org.json.JSONArray?): List<GameLore> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<GameLore>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            list.add(GameLore(
                id = item.getString("id"),
                title = item.getString("title"),
                content = item.getString("content"),
                unlockThreshold = item.optInt("unlock_threshold", 0),
                category = item.optString("category", "MAIN"),
                sourceType = item.optString("source_type", "CONTRIBUTION"),
                unlockedAt = System.currentTimeMillis() // Approximate for new unlocks
            ))
        }
        return list
    }

    private fun parseDungeonState(json: JSONObject?): DungeonState? {
        if (json == null) return null
        return DungeonState(
            dungeonId = json.getString("dungeon_id"),
            currentDepth = json.getInt("current_depth"),
            currentRoomDescription = json.getString("current_room_description"),
            sanity = json.getInt("sanity"),
            health = json.getInt("health"),
            status = json.getString("status")
        )
    }

    private fun createConnection(url: URL, method: String): HttpURLConnection {
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept-Language", LocaleManager.getAcceptLanguage(context))
        
        val token = BackendAuthManager.getInstance(context).getAccessToken()
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        if (method in listOf("POST", "PUT", "PATCH")) {
            connection.doOutput = true
        }
        return connection
    }
}

data class GamePlayer(
    val id: Int,
    val name: String,
    val money: Int,
    val currentPortId: String,
    val shipLevel: Int,
    val cargoCapacity: Int,
    val q: Int = 0,
    val r: Int = 0
)

data class GameMarket(
    val portId: String,
    val items: List<MarketItem>,
    val event: String? = null
)

data class MarketItem(
    val id: Int,
    val goodId: String,
    val name: String,
    val price: Int,
    val stock: Int
)

data class InventoryItem(
    val goodId: String,
    val name: String,
    val quantity: Int,
    val avgCost: Int
)

data class GamePort(
    val id: String,
    val name: String,
    val description: String
)

data class TradeResult(
    val success: Boolean,
    val message: String,
    val profit: Int? = null,
    val delta: JSONObject? = null,
    val newUnlocks: List<GameLore> = emptyList(),
    val travel: TravelState? = null
)

data class TravelState(
    val fromPortId: String,
    val toPortId: String,
    val departAt: Long,
    val arriveAt: Long,
    val status: String,
    val travelCost: Int,
    val encounterEvent: String? = null,
    val encounterMoneyDelta: Int = 0
)

data class TravelStatusResult(
    val success: Boolean,
    val travel: TravelState? = null,
    val message: String? = null
)

data class NpcInteractionResult(
    val success: Boolean,
    val text: String,
    val action: String? = null,
    val mood: String? = null,
    val delta: JSONObject? = null,
    val newUnlocks: List<GameLore> = emptyList()
)

data class DungeonResult(
    val success: Boolean,
    val message: String,
    val state: DungeonState? = null,
    val delta: JSONObject? = null,
    val newUnlocks: List<GameLore> = emptyList()
)

data class DungeonState(
    val dungeonId: String,
    val currentDepth: Int,
    val currentRoomDescription: String,
    val sanity: Int,
    val health: Int,
    val status: String
)

data class GameSeason(
    val id: String,
    val name: String,
    val description: String,
    val globalTarget: Long,
    val currentProgress: Long,
    val endTime: Long
)

data class SeasonStatusResult(
    val success: Boolean,
    val season: GameSeason? = null,
    val dailyNews: String? = null,
    val error: String? = null
)

data class HexTile(
    val q: Int,
    val r: Int,
    val type: String,
    val difficulty: Int,
    val isExplored: Boolean,
    val hasBeacon: Boolean,
    val visitedAt: Long = 0,
    val visitCount: Int = 0
)

data class MoveResult(
    val success: Boolean,
    val message: String,
    val event: String? = null,
    val delta: JSONObject? = null,
    val newUnlocks: List<GameLore> = emptyList()
)

data class MintResult(
    val success: Boolean,
    val message: String,
    val mintAddress: String? = null,
    val delta: JSONObject? = null
)

data class GameLore(
    val id: String,
    val title: String,
    val content: String,
    val unlockThreshold: Int,
    val category: String,
    val sourceType: String,
    val unlockedAt: Long
)

data class GameDungeon(
    val id: String,
    val name: String,
    val description: String,
    val difficultyLevel: Int,
    val entryCost: Int
)
