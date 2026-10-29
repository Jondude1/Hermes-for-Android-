package com.jondude1.hermes.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Small JSON-file persistence. Every write goes to a temp file first and is then renamed over the
 * old one, so a crash or a killed process can never leave a half-written file behind.
 */
class JsonFile(ctx: Context, name: String) {
    private val file = File(File(ctx.filesDir, "hermes").apply { mkdirs() }, name)

    @Synchronized
    fun readArray(): JSONArray = runCatching {
        if (file.exists()) JSONArray(file.readText()) else JSONArray()
    }.getOrElse {
        ErrorLog.record("Couldn't read ${file.name}; starting it fresh", it)
        backupCorrupt()
        JSONArray()
    }

    @Synchronized
    fun readObject(): JSONObject = runCatching {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }.getOrElse {
        ErrorLog.record("Couldn't read ${file.name}; starting it fresh", it)
        backupCorrupt()
        JSONObject()
    }

    @Synchronized
    fun write(text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { ErrorLog.record("Couldn't save ${file.name}", it) }
    }

    private fun backupCorrupt() {
        runCatching { if (file.exists()) file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
    }
}

fun newId(): String = UUID.randomUUID().toString().take(8)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

/** Lower-cased word set used for cheap relevance scoring. */
fun words(text: String): Set<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }.toSet()

private val STOP = setOf(
    "the", "and", "for", "with", "that", "this", "you", "your", "are", "was", "but", "not", "have", "has",
    "from", "what", "when", "how", "can", "about", "into", "they", "them", "then", "there", "will", "would",
)
