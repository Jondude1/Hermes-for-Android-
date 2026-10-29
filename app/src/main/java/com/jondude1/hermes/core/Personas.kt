package com.jondude1.hermes.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Tool groups a persona can be given. The engine maps each group to concrete tools. */
object ToolGroups {
    const val PHONE = "phone"           // alarms, texts, calendar, notifications, apps, links
    const val SCREEN = "screen"         // read and drive any app through accessibility
    const val HOME = "home"             // Home Assistant
    const val MEMORY = "memory"         // remember, recall, forget, skills
    const val WEB = "web"               // web search and page fetch
    const val BOTS = "bots"             // subagents, personas, scheduled bots
    const val CONNECTIONS = "connections" // MCP servers
    val ALL = listOf(PHONE, SCREEN, HOME, MEMORY, WEB, BOTS, CONNECTIONS)
}

class Persona(
    val id: String,
    val name: String,
    val role: String,          // added to the system prompt: who this bot is and how it behaves
    val groups: List<String>,  // which tool groups it may use
    val voicePitch: Float,
    val voiceRate: Float,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("role", role)
        .put("groups", JSONArray(groups)).put("pitch", voicePitch.toDouble()).put("rate", voiceRate.toDouble())

    companion object {
        fun from(o: JSONObject) = Persona(
            o.optString("id").ifBlank { newId() },
            o.optString("name"),
            o.optString("role"),
            o.optJSONArray("groups").strings().ifEmpty { ToolGroups.ALL },
            o.optDouble("pitch", 1.0).toFloat(),
            o.optDouble("rate", 1.0).toFloat(),
        )
    }
}

/**
 * Named bots. Hermes is the default and always exists. Others have their own role, their own tool
 * set and their own voice, and can be used from the home screen, by scheduled bots, or as
 * subagents.
 */
class Personas(ctx: Context) {
    private val file = JsonFile(ctx, "personas.json")
    private val stateFile = JsonFile(ctx, "persona_state.json")

    val all = mutableStateListOf<Persona>().apply {
        val loaded = file.readArray().objects().map(Persona::from).filter { it.name.isNotBlank() }
        addAll(loaded)
        if (none { it.id == DEFAULT_ID }) add(0, defaultPersona())
    }

    var activeId by mutableStateOf(stateFile.readObject().optString("active", DEFAULT_ID))
        private set

    val active: Persona get() = all.firstOrNull { it.id == activeId } ?: all.first()

    fun byName(name: String): Persona? {
        val n = name.trim().lowercase()
        return all.firstOrNull { it.name.lowercase() == n || it.id == name }
    }

    fun select(id: String) {
        activeId = if (all.any { it.id == id }) id else DEFAULT_ID
        stateFile.write(JSONObject().put("active", activeId).toString())
    }

    fun upsert(name: String, role: String, groups: List<String>, pitch: Float = 1f, rate: Float = 1f): Persona {
        val old = byName(name)
        val cleanGroups = groups.filter { it in ToolGroups.ALL }.ifEmpty { ToolGroups.ALL }
        val p = Persona(
            old?.id ?: newId(), name.trim().take(32), role.trim().take(2000), cleanGroups,
            pitch.coerceIn(0.5f, 2f), rate.coerceIn(0.5f, 2f),
        )
        if (old != null) all[all.indexOf(old)] = p else all.add(p)
        persist()
        return p
    }

    fun delete(id: String): Boolean {
        if (id == DEFAULT_ID) return false
        val removed = all.removeAll { it.id == id }
        if (removed) {
            if (activeId == id) select(DEFAULT_ID)
            persist()
        }
        return removed
    }

    private fun persist() {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        file.write(arr.toString())
    }

    companion object {
        const val DEFAULT_ID = "hermes"
        fun defaultPersona() = Persona(
            DEFAULT_ID, "Hermes",
            "You are the core mind. Your own character (see Character) is the whole persona; nothing extra to play.",
            ToolGroups.ALL, 1.0f, 1.0f,
        )
    }
}
