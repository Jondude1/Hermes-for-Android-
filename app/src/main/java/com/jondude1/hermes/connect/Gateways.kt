package com.jondude1.hermes.connect

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.agent.Channel
import com.jondude1.hermes.agent.await
import com.jondude1.hermes.core.ErrorLog
import com.jondude1.hermes.services.GatewayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Owns the Telegram and Discord bots and the foreground service that keeps them connected. */
class Gateways(private val c: AgentController) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val telegram = Telegram(c, scope)
    val discord = Discord(c, scope)

    fun startIfConfigured() {
        if (c.settings.telegramToken.isNotBlank()) telegram.start()
        if (c.settings.discordToken.isNotBlank()) discord.start()
        syncService()
    }

    fun restart() {
        telegram.stop(); discord.stop()
        startIfConfigured()
    }

    private fun syncService() {
        val any = c.settings.telegramToken.isNotBlank() || c.settings.discordToken.isNotBlank()
        val intent = Intent(c.app, GatewayService::class.java)
        runCatching {
            if (any) ContextCompat.startForegroundService(c.app, intent) else c.app.stopService(intent)
        }.onFailure { ErrorLog.record("Couldn't start the messaging service", it) }
    }
}

/** Splits long replies to fit a platform's message limit, preferring paragraph and line breaks. */
private fun chunks(text: String, max: Int): List<String> {
    if (text.length <= max) return listOf(text)
    val out = mutableListOf<String>()
    var rest = text
    while (rest.length > max) {
        var cut = rest.lastIndexOf("\n", max).takeIf { it > max / 2 } ?: rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        out.add(rest.substring(0, cut))
        rest = rest.substring(cut).trimStart()
        if (cut <= 0) break
    }
    if (rest.isNotBlank()) out.add(rest)
    return out
}

// ---- Telegram --------------------------------------------------------------------------------

/**
 * A Telegram bot over long polling: no server needed. Only chats listed in Settings can talk to
 * Hermes; anyone else gets their chat id back so Jon can add himself, and nothing else.
 */
class Telegram(private val c: AgentController, private val scope: CoroutineScope) {
    var status by mutableStateOf("Off")
        private set
    private var job: Job? = null
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS).build()
    private val token get() = c.settings.telegramToken
    private val repliedToStrangers = HashSet<Long>()

    fun start() {
        if (job?.isActive == true || token.isBlank()) return
        job = scope.launch { pollLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        status = "Off"
    }

    private suspend fun pollLoop() {
        var offset = 0L
        var failures = 0
        status = "Connecting"
        runCatching { api("deleteWebhook", JSONObject()) }
        while (scope.isActive && token.isNotBlank()) {
            try {
                val res = api("getUpdates", JSONObject().put("offset", offset).put("timeout", 50).put("allowed_updates", JSONArray().put("message")))
                failures = 0
                status = "Connected"
                val updates = res.optJSONArray("result") ?: JSONArray()
                for (i in 0 until updates.length()) {
                    val u = updates.optJSONObject(i) ?: continue
                    offset = maxOf(offset, u.optLong("update_id") + 1)
                    val msg = u.optJSONObject("message") ?: continue
                    handle(msg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramAuthError) {
                status = "Token rejected"
                ErrorLog.record("Telegram rejected the bot token; stopping")
                return
            } catch (e: Throwable) {
                failures++
                status = "Reconnecting"
                if (failures == 1 || failures % 10 == 0) ErrorLog.record("Telegram connection problem", e)
                delay(minOf(60_000L, 2_000L * failures))
            }
        }
    }

    private fun handle(msg: JSONObject) {
        val chatId = msg.optJSONObject("chat")?.optLong("id") ?: return
        val text = msg.optString("text").trim()
        if (text.isEmpty()) return
        val allowed = c.settings.idList(c.settings.telegramChats)
        if (chatId.toString() !in allowed) {
            if (repliedToStrangers.add(chatId)) {
                scope.launch {
                    runCatching {
                        send(chatId, "This chat isn't connected to Hermes. To allow it, add this chat id in Hermes Settings, Connections: $chatId")
                    }
                }
            }
            return
        }
        if (text == "/start") {
            scope.launch { runCatching { send(chatId, "Hermes here. Talk to me like you would on the phone.") } }
            return
        }
        scope.launch {
            val typing = launch {
                while (isActive) {
                    runCatching { api("sendChatAction", JSONObject().put("chat_id", chatId).put("action", "typing")) }
                    delay(4_500)
                }
            }
            val reply = try {
                c.runRemote(Channel.Telegram, "tg-$chatId", text)
            } catch (e: CancellationException) {
                "Stopped."
            } catch (e: Throwable) {
                ErrorLog.record("Telegram request failed", e)
                "Something went wrong on the phone: ${e.message}"
            } finally {
                typing.cancel()
            }
            runCatching { send(chatId, reply) }.onFailure { ErrorLog.record("Couldn't send a Telegram reply", it) }
        }
    }

    suspend fun send(chatId: Long, text: String) {
        for (part in chunks(text.ifBlank { "(no reply)" }, 4000)) {
            api("sendMessage", JSONObject().put("chat_id", chatId).put("text", part))
        }
    }

    /** Sends to every allowed chat. Returns false if nothing went out. */
    suspend fun broadcast(text: String): Boolean {
        if (token.isBlank()) return false
        val chats = c.settings.idList(c.settings.telegramChats).mapNotNull { it.toLongOrNull() }
        var any = false
        for (id in chats) if (runCatching { send(id, text) }.isSuccess) any = true
        return any
    }

    suspend fun test(): String = runCatching {
        val me = api("getMe", JSONObject()).optJSONObject("result")
        "Connected as @${me?.optString("username")}"
    }.getOrElse { "Failed: ${it.message}" }

    private class TelegramAuthError : Exception("unauthorized")

    private suspend fun api(method: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 404) throw TelegramAuthError()
            if (r.code == 429) {
                val wait = runCatching { JSONObject(text).optJSONObject("parameters")?.optLong("retry_after") }.getOrNull() ?: 5
                delay(wait * 1000)
                throw IllegalStateException("Telegram rate limit")
            }
            if (!r.isSuccessful) throw IllegalStateException("Telegram ${r.code}: ${text.take(160)}")
            JSONObject(text)
        }
    }
}

// ---- Discord ---------------------------------------------------------------------------------

/**
 * A Discord bot over the gateway websocket. It answers direct messages, and mentions in servers,
 * from the user ids listed in Settings. Needs the Message Content intent enabled for the bot in the
 * Discord developer portal.
 */
class Discord(private val c: AgentController, private val scope: CoroutineScope) {
    var status by mutableStateOf("Off")
        private set
    private var job: Job? = null
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    @Volatile private var seq: Long? = null
    @Volatile private var botId = ""
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS).build()
    private val token get() = c.settings.discordToken

    fun start() {
        if (job?.isActive == true || token.isBlank()) return
        job = scope.launch { connectLoop() }
    }

    fun stop() {
        job?.cancel(); job = null
        heartbeat?.cancel()
        runCatching { socket?.close(1000, "bye") }
        socket = null
        status = "Off"
    }

    private suspend fun connectLoop() {
        var failures = 0
        while (scope.isActive && token.isNotBlank()) {
            val closed = kotlinx.coroutines.CompletableDeferred<Int>()
            status = "Connecting"
            val req = Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build()
            socket = http.newWebSocket(req, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onEvent(webSocket, text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(code) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ErrorLog.record("Discord connection dropped", t)
                    closed.complete(-1)
                }
            })
            val code = closed.await()
            heartbeat?.cancel()
            if (code == 4004) {
                status = "Token rejected"
                ErrorLog.record("Discord rejected the bot token; stopping")
                return
            }
            if (code == 4014) {
                status = "Message Content intent is off"
                ErrorLog.record("Discord: enable the Message Content intent for the bot in the developer portal")
                return
            }
            failures++
            status = "Reconnecting"
            delay(minOf(60_000L, 3_000L * failures))
        }
    }

    private fun onEvent(ws: WebSocket, text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (!o.isNull("s")) seq = o.optLong("s")
        when (o.optInt("op")) {
            10 -> { // hello
                val interval = o.optJSONObject("d")?.optLong("heartbeat_interval") ?: 41_250L
                heartbeat?.cancel()
                heartbeat = scope.launch {
                    delay((interval * Math.random()).toLong())
                    while (isActive) {
                        ws.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString())
                        delay(interval)
                    }
                }
                val intents = (1 shl 9) or (1 shl 12) or (1 shl 15) // guild messages, direct messages, message content
                ws.send(
                    JSONObject().put("op", 2).put(
                        "d",
                        JSONObject().put("token", token).put("intents", intents)
                            .put("properties", JSONObject().put("os", "android").put("browser", "hermes").put("device", "hermes")),
                    ).toString()
                )
            }
            1 -> ws.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString())
            7, 9 -> ws.close(4000, "reconnect")
            0 -> when (o.optString("t")) {
                "READY" -> {
                    botId = o.optJSONObject("d")?.optJSONObject("user")?.optString("id").orEmpty()
                    status = "Connected"
                }
                "MESSAGE_CREATE" -> o.optJSONObject("d")?.let { onMessage(it) }
            }
        }
    }

    private fun onMessage(d: JSONObject) {
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot")) return
        val userId = author.optString("id")
        val channelId = d.optString("channel_id")
        val isDm = !d.has("guild_id")
        var text = d.optString("content").trim()
        val mention = "<@$botId>"
        if (!isDm) {
            if (botId.isBlank() || !text.contains(mention)) return
            text = text.replace(mention, "").trim()
        }
        if (text.isEmpty()) return
        if (userId !in c.settings.idList(c.settings.discordUsers)) {
            if (isDm) scope.launch { runCatching { send(channelId, "You're not connected to Hermes. Your Discord user id is $userId.") } }
            return
        }
        scope.launch {
            runCatching { rest("POST", "/channels/$channelId/typing", null) }
            val reply = try {
                c.runRemote(Channel.Discord, "dc-$channelId", text)
            } catch (e: CancellationException) {
                "Stopped."
            } catch (e: Throwable) {
                ErrorLog.record("Discord request failed", e)
                "Something went wrong on the phone: ${e.message}"
            }
            runCatching { send(channelId, reply) }.onFailure { ErrorLog.record("Couldn't send a Discord reply", it) }
        }
    }

    suspend fun send(channelId: String, text: String) {
        for (part in chunks(text.ifBlank { "(no reply)" }, 1900)) {
            rest("POST", "/channels/$channelId/messages", JSONObject().put("content", part))
        }
    }

    /** DMs every allowed user. Returns false if nothing went out. */
    suspend fun broadcast(text: String): Boolean {
        if (token.isBlank()) return false
        var any = false
        for (uid in c.settings.idList(c.settings.discordUsers)) {
            runCatching {
                val dm = JSONObject(rest("POST", "/users/@me/channels", JSONObject().put("recipient_id", uid)))
                send(dm.optString("id"), text)
            }.onSuccess { any = true }.onFailure { ErrorLog.record("Couldn't DM Discord user $uid", it) }
        }
        return any
    }

    private suspend fun rest(method: String, path: String, body: JSONObject?): String = withContext(Dispatchers.IO) {
        val rb = (body?.toString() ?: "").toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://discord.com/api/v10$path")
            .header("Authorization", "Bot $token")
            .header("User-Agent", "DiscordBot (https://github.com/Jondude1/Hermes-for-Android-, 1.0)")
            .method(method, if (method == "GET") null else rb)
            .build()
        http.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 429) {
                val wait = runCatching { JSONObject(text).optDouble("retry_after", 1.0) }.getOrDefault(1.0)
                delay((wait * 1000).toLong())
                throw IllegalStateException("Discord rate limit")
            }
            if (!r.isSuccessful) throw IllegalStateException("Discord ${r.code}: ${text.take(160)}")
            text
        }
    }
}
