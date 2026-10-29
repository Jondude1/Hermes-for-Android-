package com.jondude1.hermes.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One line per finished request, across every channel. Each conversation keeps its own thread,
 * but this shared log is what lets Hermes know on the phone that a scheduled bot ran this morning or
 * that Jon asked something over Telegram an hour ago. It costs a few hundred tokens, not a transcript.
 */
class Activity(ctx: Context) {
    private val file = JsonFile(ctx, "activity.json")
    private val items = file.readArray().objects().toMutableList()

    @Synchronized
    fun add(sessionKey: String, source: String, request: String, reply: String) {
        items.add(
            JSONObject().put("key", sessionKey).put("src", source).put("at", System.currentTimeMillis())
                .put("req", request.take(160)).put("rep", reply.take(200))
        )
        while (items.size > MAX) items.removeAt(0)
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        file.write(arr.toString())
    }

    /** The latest few entries from other conversations in the last two days. */
    @Synchronized
    fun recent(excludeKey: String, limit: Int = 6): String {
        val cutoff = System.currentTimeMillis() - 48 * 3_600_000L
        val fmt = DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault())
        return items.asReversed()
            .filter { it.optString("key") != excludeKey && it.optLong("at") > cutoff }
            .take(limit)
            .asReversed()
            .joinToString("\n") {
                "- ${fmt.format(Instant.ofEpochMilli(it.optLong("at")))} via ${it.optString("src")}: " +
                    "\"${it.optString("req")}\" -> ${it.optString("rep")}"
            }
    }

    companion object { private const val MAX = 60 }
}
