package com.jondude1.hermes.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

class MemoryEntry(
    val id: String,
    val text: String,
    val kind: String,          // fact | preference | person | project | event
    val created: Long,
    val updated: Long,
    val hits: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("kind", kind)
        .put("created", created).put("updated", updated).put("hits", hits)

    companion object {
        fun from(o: JSONObject) = MemoryEntry(
            o.optString("id").ifBlank { newId() },
            o.optString("text"),
            o.optString("kind", "fact"),
            o.optLong("created", System.currentTimeMillis()),
            o.optLong("updated", System.currentTimeMillis()),
            o.optInt("hits"),
        )
    }
}

/**
 * Two layers, like Hermes Agent:
 *  - the core profile: a short text about the person that rides along in every prompt, and
 *  - long-term memories: individual facts the agent saves and recalls by relevance.
 *
 * Relevant memories are pulled into each run automatically, so the agent "just knows" without
 * having to search first. It can still search for anything else.
 */
class Memory(ctx: Context) {
    private val profileFile = JsonFile(ctx, "profile.json")
    private val entriesFile = JsonFile(ctx, "memories.json")

    var profile by mutableStateOf(profileFile.readObject().optString("text"))
        private set
    val entries = mutableStateListOf<MemoryEntry>().apply {
        addAll(entriesFile.readArray().objects().map(MemoryEntry::from).filter { it.text.isNotBlank() })
    }

    val isNew: Boolean get() = profile.isBlank() && entries.isEmpty()

    /** First launch only: start from what's already known about Jon instead of a blank slate. */
    fun seedIfNew() {
        if (!isNew) return
        setProfile(Seed.PROFILE)
        Seed.MEMORIES.asReversed().forEach { (text, kind) -> save(text, kind) }
    }

    fun setProfile(text: String) {
        profile = text.trim().take(MAX_PROFILE)
        profileFile.write(JSONObject().put("text", profile).toString())
    }

    /** Saves a memory. A near-duplicate of an existing one replaces it instead of piling up. */
    fun save(text: String, kind: String): MemoryEntry {
        val clean = text.trim().take(600)
        val w = words(clean)
        val now = System.currentTimeMillis()
        val dup = entries.firstOrNull { overlap(w, words(it.text)) >= 0.8 }
        val entry = if (dup != null) {
            MemoryEntry(dup.id, clean, kind, dup.created, now, dup.hits)
                .also { entries[entries.indexOf(dup)] = it }
        } else {
            MemoryEntry(newId(), clean, kind, now, now, 0).also { entries.add(0, it) }
        }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.minByOrNull { it.hits * 10 + it.updated / 86_400_000 })
        persist()
        return entry
    }

    fun update(id: String, text: String): Boolean {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return false
        val old = entries[i]
        entries[i] = MemoryEntry(old.id, text.trim().take(600), old.kind, old.created, System.currentTimeMillis(), old.hits)
        persist()
        return true
    }

    fun forget(id: String): Boolean {
        val removed = entries.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    /** Ranked search: word overlap first, then how often a memory has been useful, then recency. */
    fun search(query: String, limit: Int = 8): List<MemoryEntry> {
        val q = words(query)
        if (q.isEmpty()) return entries.take(limit)
        return entries
            .map { it to score(q, it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Memories pulled into a run's prompt. Counts as a "hit" so useful memories rise. */
    fun recall(forText: String, limit: Int = 8): List<MemoryEntry> {
        val found = search(forText, limit)
        if (found.isNotEmpty()) {
            val ids = found.map { it.id }.toSet()
            for (i in entries.indices) {
                val e = entries[i]
                if (e.id in ids) entries[i] = MemoryEntry(e.id, e.text, e.kind, e.created, e.updated, e.hits + 1)
            }
            persist()
        }
        return found
    }

    fun exportText(): String = buildString {
        append("PROFILE\n").append(profile.ifBlank { "(empty)" }).append("\n\nMEMORIES\n")
        entries.forEach { append("- [${it.kind}] ${it.text}\n") }
    }

    private fun score(q: Set<String>, e: MemoryEntry): Double {
        val w = words(e.text)
        val shared = q.count { it in w }
        if (shared == 0) return 0.0
        val ageDays = (System.currentTimeMillis() - e.updated) / 86_400_000.0
        return shared * 2.0 + minOf(e.hits, 20) * 0.05 + 1.0 / (1.0 + ageDays / 30.0)
    }

    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b }.toDouble() / maxOf(a.size, b.size)
    }

    private fun persist() {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        entriesFile.write(arr.toString())
    }

    companion object {
        const val MAX_PROFILE = 4000
        const val MAX_ENTRIES = 1500
    }
}
