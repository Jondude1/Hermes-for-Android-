package com.jondude1.hermes.core

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val updated: Long,
    val uses: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("description", description)
        .put("instructions", instructions).put("updated", updated).put("uses", uses)

    companion object {
        fun from(o: JSONObject) = Skill(
            o.optString("id").ifBlank { newId() },
            o.optString("name"),
            o.optString("description"),
            o.optString("instructions"),
            o.optLong("updated", System.currentTimeMillis()),
            o.optInt("uses"),
        )
    }
}

/**
 * Hermes Agent's learning loop: when the agent works out how to do something that will come up
 * again, it writes the procedure down as a skill. Every run sees the list of skill names and
 * descriptions, and loads the full instructions only when one applies.
 */
class Skills(ctx: Context) {
    private val file = JsonFile(ctx, "skills.json")
    val all = mutableStateListOf<Skill>().apply {
        addAll(file.readArray().objects().map(Skill::from).filter { it.name.isNotBlank() })
    }

    fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "skill" }

    fun find(name: String): Skill? {
        val s = slug(name)
        return all.firstOrNull { it.name == s }
    }

    /** Creates or replaces a skill of the same name. */
    fun upsert(name: String, description: String, instructions: String): Skill {
        val s = slug(name)
        val old = all.firstOrNull { it.name == s }
        val skill = Skill(
            old?.id ?: newId(), s, description.trim().take(200), instructions.trim().take(6000),
            System.currentTimeMillis(), old?.uses ?: 0,
        )
        if (old != null) all[all.indexOf(old)] = skill else all.add(skill)
        persist()
        return skill
    }

    fun markUsed(skill: Skill) {
        val i = all.indexOfFirst { it.id == skill.id }
        if (i < 0) return
        val s = all[i]
        all[i] = Skill(s.id, s.name, s.description, s.instructions, s.updated, s.uses + 1)
        persist()
    }

    fun delete(id: String): Boolean {
        val removed = all.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    fun index(): String =
        if (all.isEmpty()) "(none yet)"
        else all.sortedByDescending { it.uses }.joinToString("\n") { "- ${it.name}: ${it.description}" }

    private fun persist() {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        file.write(arr.toString())
    }
}
