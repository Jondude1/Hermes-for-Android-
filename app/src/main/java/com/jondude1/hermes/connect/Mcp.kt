package com.jondude1.hermes.connect

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.jondude1.hermes.agent.await
import com.jondude1.hermes.core.ErrorLog
import com.jondude1.hermes.core.JsonFile
import com.jondude1.hermes.core.newId
import com.jondude1.hermes.core.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpServer(val id: String, val name: String, val url: String, val token: String, val enabled: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("token", token).put("enabled", enabled)

    companion object {
        fun from(o: JSONObject) = McpServer(
            o.optString("id").ifBlank { newId() }, o.optString("name"), o.optString("url"),
            o.optString("token"), o.optBoolean("enabled", true),
        )
    }
}

class McpTool(val server: String, val name: String, val description: String, val schema: JSONObject)

class McpError(message: String) : Exception(message)

/**
 * A client for MCP servers over Streamable HTTP. Any service that speaks MCP (an Obsidian vault
 * bridge, Nextcloud, GitHub, something running on Obelisk) becomes a set of tools Hermes can use.
 *
 * Tools are reached through two stable meta-tools (mcp_tools, mcp_call) rather than being added to
 * the tool list one by one, so the prompt cache survives servers being added or going offline.
 */
class Mcp(ctx: Context) {
    private val file = JsonFile(ctx, "mcp_servers.json")
    val servers = mutableStateListOf<McpServer>().apply {
        addAll(file.readArray().objects().map(McpServer::from).filter { it.url.isNotBlank() })
    }
    /** Last known tools per server name, and a status line per server for Settings. */
    val catalog = mutableStateMapOf<String, List<McpTool>>()
    val status = mutableStateMapOf<String, String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val ids = AtomicInteger(1)
    private val sessions = HashMap<String, String>()
    private val lock = Mutex()

    fun add(name: String, url: String, token: String): McpServer {
        val clean = name.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").ifBlank { "server" }
        servers.removeAll { it.name == clean }
        val s = McpServer(newId(), clean, url.trim(), token.trim(), true)
        servers.add(s)
        persist()
        return s
    }

    fun remove(id: String) {
        servers.firstOrNull { it.id == id }?.let { catalog.remove(it.name); status.remove(it.name); sessions.remove(it.name) }
        servers.removeAll { it.id == id }
        persist()
    }

    fun byName(name: String): McpServer? = servers.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && it.enabled }

    /** One line per server for the system prompt. */
    fun summary(): String {
        val live = servers.filter { it.enabled }
        if (live.isEmpty()) return "(none connected)"
        return live.joinToString("\n") { s ->
            val tools = catalog[s.name]
            if (tools == null) "- ${s.name}: tools not loaded yet (call mcp_tools)"
            else "- ${s.name}: ${tools.joinToString(", ") { it.name }.take(400)}"
        }
    }

    suspend fun refreshAll() {
        servers.filter { it.enabled }.forEach { s ->
            runCatching { listTools(s) }.onFailure { status[s.name] = "Offline: ${it.message}" }
        }
    }

    suspend fun listTools(s: McpServer): List<McpTool> {
        val result = rpc(s, "tools/list", JSONObject())
        val arr = result.optJSONArray("tools") ?: JSONArray()
        val tools = arr.objects().map {
            McpTool(s.name, it.optString("name"), it.optString("description").take(600), it.optJSONObject("inputSchema") ?: JSONObject())
        }
        catalog[s.name] = tools
        status[s.name] = "Connected, ${tools.size} tools"
        return tools
    }

    suspend fun call(s: McpServer, tool: String, args: JSONObject): Pair<String, Boolean> {
        val result = rpc(s, "tools/call", JSONObject().put("name", tool).put("arguments", args))
        val parts = result.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            parts.objects().forEach { p ->
                when (p.optString("type")) {
                    "text" -> append(p.optString("text")).append('\n')
                    "resource" -> append(p.optJSONObject("resource")?.optString("text").orEmpty()).append('\n')
                    else -> append("[${p.optString("type")} content]\n")
                }
            }
            result.optJSONObject("structuredContent")?.let { if (isBlank()) append(it.toString()) }
        }.trim()
        return text.ifBlank { "(no output)" } to result.optBoolean("isError")
    }

    // ---- JSON-RPC over Streamable HTTP ------------------------------------------------------

    private suspend fun rpc(s: McpServer, method: String, params: JSONObject, retried: Boolean = false): JSONObject {
        lock.withLock { if (sessions[s.name] == null && method != "initialize") initialize(s) }
        return try {
            request(s, method, params)
        } catch (e: McpError) {
            // An expired session comes back as 404; start a new one once.
            if (!retried && e.message?.contains("session", ignoreCase = true) == true) {
                lock.withLock { sessions.remove(s.name); initialize(s) }
                rpc(s, method, params, retried = true)
            } else throw e
        }
    }

    private suspend fun initialize(s: McpServer) {
        val params = JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "hermes-android").put("version", "1.0"))
        request(s, "initialize", params)
        runCatching { notify(s, "notifications/initialized") }
    }

    private suspend fun notify(s: McpServer, method: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        http.newCall(build(s, body)).await().close()
    }

    private suspend fun request(s: McpServer, method: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val resp = try {
            http.newCall(build(s, body)).await()
        } catch (e: java.io.IOException) {
            throw McpError("Can't reach ${s.name} at ${s.url} (${e.message})")
        }
        resp.use { r ->
            r.header("mcp-session-id")?.let { sessions[s.name] = it }
            val text = r.body?.string().orEmpty()
            if (r.code == 404 && sessions.containsKey(s.name)) throw McpError("session expired")
            if (r.code == 401 || r.code == 403) throw McpError("${s.name} refused the token (HTTP ${r.code})")
            if (!r.isSuccessful) throw McpError("${s.name} answered HTTP ${r.code}: ${text.take(200)}")
            val msg = parse(text, r.header("content-type").orEmpty(), id)
                ?: throw McpError("${s.name} sent no reply to $method")
            msg.optJSONObject("error")?.let { throw McpError("${s.name}: ${it.optString("message")}") }
            msg.optJSONObject("result") ?: JSONObject()
        }
    }

    private fun build(s: McpServer, body: JSONObject): Request {
        val b = Request.Builder()
            .url(s.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (s.token.isNotBlank()) b.header("Authorization", "Bearer ${s.token}")
        sessions[s.name]?.let { b.header("mcp-session-id", it) }
        return b.build()
    }

    /** Replies come back either as plain JSON or as a server-sent event stream. */
    private fun parse(text: String, contentType: String, id: Int): JSONObject? {
        if (contentType.contains("event-stream") || text.trimStart().startsWith("event:") || text.trimStart().startsWith("data:")) {
            var found: JSONObject? = null
            val data = StringBuilder()
            fun flush() {
                if (data.isEmpty()) return
                runCatching { JSONObject(data.toString()) }.getOrNull()?.let { if (it.optInt("id", -1) == id) found = it }
                data.clear()
            }
            text.lineSequence().forEach { line ->
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isBlank() -> flush()
                }
            }
            flush()
            return found
        }
        return runCatching { JSONObject(text) }.getOrElse {
            ErrorLog.record("MCP reply wasn't JSON", it)
            null
        }
    }

    private fun persist() {
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        file.write(arr.toString())
    }
}
