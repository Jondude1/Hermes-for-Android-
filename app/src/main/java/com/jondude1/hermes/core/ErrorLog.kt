package com.jondude1.hermes.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything that goes wrong lands here: API failures, tool crashes, gateway drops, uncaught
 * exceptions. The last 200 lines are kept in a file so they survive a crash, and Settings shows
 * them so problems can be diagnosed without a computer.
 */
object ErrorLog {
    private const val MAX = 200
    private var file: File? = null
    val lines = mutableStateListOf<String>()

    fun init(ctx: Context) {
        val f = File(File(ctx.filesDir, "hermes").apply { mkdirs() }, "errors.log")
        file = f
        runCatching { if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.add(it) } }

        // Keep the default handler so Android still reports the crash, but write it down first.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record("Crash on ${t.name}", e, sync = true)
            previous?.uncaughtException(t, e)
        }
    }

    fun record(what: String, e: Throwable? = null, sync: Boolean = false) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        val detail = e?.let { ": ${it.javaClass.simpleName} ${it.message.orEmpty()}".take(300) }.orEmpty()
        val line = "$stamp  $what$detail"
        Log.w("Hermes", line, e)
        val write = {
            runCatching {
                if (lines.size >= MAX) lines.removeAt(0)
                lines.add(line)
            }
            runCatching { file?.let { f -> f.writeText(lines.takeLast(MAX).joinToString("\n")) } }
            Unit
        }
        if (sync || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            write()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { write() }
        }
    }

    fun clear() {
        lines.clear()
        runCatching { file?.delete() }
    }
}
