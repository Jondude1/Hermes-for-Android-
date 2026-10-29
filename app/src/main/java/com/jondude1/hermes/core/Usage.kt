package com.jondude1.hermes.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/**
 * Keeps a running estimate of what the API costs, from the usage block on every response.
 *
 * Prices are per million tokens as published when this was written (Oct 2026). They're estimates;
 * Anthropic's console is the real bill. Haiku's prices are its under-100K-token tier, which is where
 * Hermes keeps every request.
 */
object Pricing {
    class Rate(val input: Double, val output: Double, val cacheWrite: Double, val cacheRead: Double)

    private val HAIKU = Rate(0.10, 0.50, 0.125, 0.01)
    private val SONNET = Rate(2.00, 10.00, 2.50, 0.10)
    private val OPUS = Rate(10.00, 50.00, 12.50, 1.00) // assumption: roughly 5x Sonnet
    const val WEB_SEARCH_EACH = 0.01

    fun rate(model: String): Rate = when {
        model.contains("haiku") -> HAIKU
        model.contains("opus") -> OPUS
        else -> SONNET
    }
}

class Usage(ctx: Context) {
    private val file = JsonFile(ctx, "usage.json")

    var month by mutableStateOf(""); private set
    var monthCost by mutableDoubleStateOf(0.0); private set
    var dayCost by mutableDoubleStateOf(0.0); private set
    var monthRequests by mutableIntStateOf(0); private set
    var cheapRequests by mutableIntStateOf(0); private set
    private var day = ""

    init {
        val o = file.readObject()
        month = o.optString("month")
        monthCost = o.optDouble("monthCost", 0.0)
        monthRequests = o.optInt("monthRequests")
        cheapRequests = o.optInt("cheapRequests")
        day = o.optString("day")
        dayCost = o.optDouble("dayCost", 0.0)
        roll()
    }

    /** Adds one response's usage. Returns its estimated cost in dollars. */
    @Synchronized
    fun add(model: String, usage: JSONObject?): Double {
        if (usage == null) return 0.0
        roll()
        val r = Pricing.rate(model)
        val cost = (usage.optLong("input_tokens") * r.input +
            usage.optLong("output_tokens") * r.output +
            usage.optLong("cache_creation_input_tokens") * r.cacheWrite +
            usage.optLong("cache_read_input_tokens") * r.cacheRead) / 1_000_000.0 +
            (usage.optJSONObject("server_tool_use")?.optInt("web_search_requests") ?: 0) * Pricing.WEB_SEARCH_EACH
        monthCost += cost
        dayCost += cost
        monthRequests++
        if (model.contains("haiku")) cheapRequests++
        save()
        return cost
    }

    fun reset() {
        monthCost = 0.0; dayCost = 0.0; monthRequests = 0; cheapRequests = 0
        save()
    }

    private fun roll() {
        val m = YearMonth.now().toString()
        val d = LocalDate.now().toString()
        if (m != month) { month = m; monthCost = 0.0; monthRequests = 0; cheapRequests = 0 }
        if (d != day) { day = d; dayCost = 0.0 }
    }

    private fun save() {
        file.write(
            JSONObject().put("month", month).put("monthCost", monthCost).put("monthRequests", monthRequests)
                .put("cheapRequests", cheapRequests).put("day", day).put("dayCost", dayCost).toString()
        )
    }
}
