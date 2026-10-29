package com.jondude1.hermes.agent

import com.jondude1.hermes.Settings
import com.jondude1.hermes.core.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

class ApiError(val code: Int, message: String, val retryable: Boolean = false) : Exception(message)

/**
 * Messages API client with the error handling a phone needs: retries with backoff and jitter on
 * rate limits, overloads, server errors and dropped connections; honors retry-after; cancels the
 * HTTP call the moment the run is stopped; and caches the system prompt and tool list so long runs
 * stay cheap.
 */
class AnthropicClient(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun send(
        system: String,
        messages: List<JSONObject>,
        tools: JSONArray,
        model: String = settings.model,
        maxTokens: Int = 4096,
    ): JSONObject {
        if (settings.apiKey.isBlank()) throw ApiError(401, "Add your Anthropic API key in Settings.")

        // Cache breakpoints: the system prompt and the (stable) tool list.
        val sys = JSONArray().put(
            JSONObject().put("type", "text").put("text", system)
                .put("cache_control", JSONObject().put("type", "ephemeral"))
        )
        val cachedTools = JSONArray(tools.toString())
        for (i in cachedTools.length() - 1 downTo 0) {
            val t = cachedTools.getJSONObject(i)
            if (!t.has("type")) { t.put("cache_control", JSONObject().put("type", "ephemeral")); break }
        }
        // Third breakpoint on the newest message, so every step of a tool loop re-reads the whole
        // conversation from cache (about a tenth of the price) instead of paying for it again.
        val msgs = JSONArray()
        messages.forEachIndexed { i, m ->
            if (i == messages.lastIndex) {
                val copy = JSONObject(m.toString())
                val content = copy.opt("content")
                val blocks = when (content) {
                    is String -> JSONArray().put(JSONObject().put("type", "text").put("text", content))
                    is JSONArray -> content
                    else -> null
                }
                if (blocks != null && blocks.length() > 0) {
                    blocks.getJSONObject(blocks.length() - 1).put("cache_control", JSONObject().put("type", "ephemeral"))
                    copy.put("content", blocks)
                }
                msgs.put(copy)
            } else msgs.put(m)
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", sys)
            .put("messages", msgs)
        if (cachedTools.length() > 0) body.put("tools", cachedTools)
        val payload = body.toString()

        var attempt = 0
        while (true) {
            attempt++
            try {
                return once(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (!e.retryable || attempt >= MAX_ATTEMPTS) throw e
                ErrorLog.record("API ${e.code}, retrying (attempt $attempt)")
                delay(backoff(attempt, retryAfterMs))
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ApiError(0, "Couldn't reach Claude. Check your connection and try again.")
                }
                ErrorLog.record("Network error, retrying (attempt $attempt)", e)
                delay(backoff(attempt, 0))
            }
        }
    }

    @Volatile private var retryAfterMs = 0L

    private suspend fun once(payload: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req)
        val resp = call.await()
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                retryAfterMs = (r.header("retry-after")?.toDoubleOrNull() ?: 0.0).times(1000).toLong()
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault(text.take(200))
                throw ApiError(r.code, friendly(r.code, msg), retryable = r.code in RETRYABLE)
            }
            runCatching { JSONObject(text) }.getOrElse {
                throw ApiError(r.code, "Claude sent back something unreadable.", retryable = true)
            }
        }
    }

    private fun backoff(attempt: Int, retryAfter: Long): Long {
        val base = minOf(1000L * (1L shl (attempt - 1)), 16_000L)
        return maxOf(retryAfter, base + Random.nextLong(0, 600))
    }

    private fun friendly(code: Int, msg: String): String = when (code) {
        400 -> "Claude rejected the request: $msg"
        401 -> "The API key was rejected. Check it in Settings."
        403 -> "This API key isn't allowed to do that: $msg"
        404 -> "The model '${settings.model}' wasn't found. Check the model name in Settings."
        413 -> "That conversation got too large. Start a new session."
        429 -> "Rate limited. Waiting and trying again."
        529 -> "Claude is overloaded right now."
        else -> "API error $code: $msg"
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private val RETRYABLE = setOf(408, 409, 429, 500, 502, 503, 504, 529)
    }
}

/** Runs an OkHttp call without blocking a thread, and cancels it if the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
