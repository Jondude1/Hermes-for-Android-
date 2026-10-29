package com.jondude1.hermes.connect

import com.jondude1.hermes.Settings
import com.jondude1.hermes.agent.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HaError(message: String) : Exception(message)

/**
 * Home Assistant over its REST API, reached through Tailscale (or any URL the phone can see).
 * Needs a long-lived access token from the HA profile page.
 */
class HomeAssistant(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val configured: Boolean get() = settings.haUrl.isNotBlank() && settings.haToken.isNotBlank()

    private var cache: JSONArray? = null
    private var cachedAt = 0L

    suspend fun states(fresh: Boolean = false): JSONArray {
        val now = System.currentTimeMillis()
        if (!fresh && cache != null && now - cachedAt < 15_000) return cache!!
        val arr = JSONArray(get("/api/states"))
        cache = arr
        cachedAt = now
        return arr
    }

    suspend fun state(entityId: String): JSONObject = JSONObject(get("/api/states/$entityId"))

    suspend fun callService(domain: String, service: String, data: JSONObject): String {
        cache = null
        return post("/api/services/$domain/$service", data)
    }

    /** Hands a sentence to HA's own Assist pipeline, which knows the house's areas and aliases. */
    suspend fun assist(text: String): String {
        val body = JSONObject().put("text", text).put("language", "en")
        val resp = JSONObject(post("/api/conversation/process", body))
        return resp.optJSONObject("response")?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
            ?: resp.toString().take(800)
    }

    suspend fun ping(): String {
        val r = JSONObject(get("/api/"))
        return r.optString("message", "connected")
    }

    private suspend fun get(path: String): String = send(Request.Builder().url(url(path)).get())

    private suspend fun post(path: String, body: JSONObject): String =
        send(Request.Builder().url(url(path)).post(body.toString().toRequestBody("application/json".toMediaType())))

    private fun url(path: String): String {
        if (!configured) throw HaError("Home Assistant isn't set up. Add its URL and token in Settings, Connections.")
        val base = settings.haUrl.let { if (it.startsWith("http")) it else "http://$it" }
        return base.trimEnd('/') + path
    }

    private suspend fun send(b: Request.Builder): String = withContext(Dispatchers.IO) {
        val req = b.header("Authorization", "Bearer ${settings.haToken}").header("Content-Type", "application/json").build()
        val resp = try {
            http.newCall(req).await()
        } catch (e: java.io.IOException) {
            throw HaError("Can't reach Home Assistant at ${settings.haUrl}. Is Tailscale on? (${e.message})")
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 -> throw HaError("Home Assistant rejected the token. Make a new long-lived token.")
                r.code == 404 -> throw HaError("Not found in Home Assistant: ${req.url.encodedPath}")
                !r.isSuccessful -> throw HaError("Home Assistant answered ${r.code}: ${text.take(200)}")
                else -> text
            }
        }
    }
}
