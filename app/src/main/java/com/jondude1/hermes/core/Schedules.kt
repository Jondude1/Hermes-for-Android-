package com.jondude1.hermes.core

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jondude1.hermes.HermesApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class Schedule(
    val id: String,
    val name: String,
    val prompt: String,
    val persona: String,
    val kind: String,          // once | daily | weekly | interval
    val time: String,          // HH:mm for daily and weekly
    val days: List<Int>,       // 1 = Monday .. 7 = Sunday, for weekly
    val everyMinutes: Int,     // for interval (15 minimum)
    val at: Long,              // epoch millis, for once
    val deliver: List<String>, // notify | speak | telegram | discord
    val enabled: Boolean,
    val lastRun: Long,
    val lastResult: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("prompt", prompt).put("persona", persona)
        .put("kind", kind).put("time", time).put("days", JSONArray(days)).put("every", everyMinutes)
        .put("at", at).put("deliver", JSONArray(deliver)).put("enabled", enabled)
        .put("lastRun", lastRun).put("lastResult", lastResult)

    fun copy(enabled: Boolean = this.enabled, lastRun: Long = this.lastRun, lastResult: String = this.lastResult) =
        Schedule(id, name, prompt, persona, kind, time, days, everyMinutes, at, deliver, enabled, lastRun, lastResult)

    /** Next time this should run, or null if it never will again. */
    fun nextRun(now: Long = System.currentTimeMillis()): Long? {
        if (!enabled) return null
        val zone = ZoneId.systemDefault()
        val clock = runCatching { LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm")) }.getOrDefault(LocalTime.of(8, 0))
        val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return when (kind) {
            "once" -> at.takeIf { it > now }
            "interval" -> {
                val every = maxOf(everyMinutes, 15) * 60_000L
                if (lastRun == 0L) now + every else maxOf(lastRun + every, now + 60_000L)
            }
            "weekly" -> {
                val wanted = days.filter { it in 1..7 }.ifEmpty { listOf(1, 2, 3, 4, 5) }
                (0..7).asSequence()
                    .map { LocalDate.from(nowDt).plusDays(it.toLong()) }
                    .filter { it.dayOfWeek.value in wanted }
                    .map { it.atTime(clock) }
                    .firstOrNull { it.isAfter(nowDt) }
                    ?.atZone(zone)?.toInstant()?.toEpochMilli()
            }
            else -> { // daily
                var t = LocalDate.from(nowDt).atTime(clock)
                if (!t.isAfter(nowDt)) t = t.plusDays(1)
                t.atZone(zone).toInstant().toEpochMilli()
            }
        }
    }

    fun describe(): String {
        val dayNames = days.mapNotNull { d -> runCatching { DayOfWeek.of(d).name.take(3).lowercase().replaceFirstChar { it.uppercase() } }.getOrNull() }
        return when (kind) {
            "once" -> "Once, " + DateTimeFormatter.ofPattern("MMM d h:mm a").format(LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()))
            "interval" -> "Every ${maxOf(everyMinutes, 15)} min"
            "weekly" -> "${dayNames.joinToString(", ")} at $time"
            else -> "Daily at $time"
        }
    }

    companion object {
        fun from(o: JSONObject) = Schedule(
            o.optString("id").ifBlank { newId() },
            o.optString("name"),
            o.optString("prompt"),
            o.optString("persona", Personas.DEFAULT_ID),
            o.optString("kind", "daily"),
            o.optString("time", "08:00"),
            (o.optJSONArray("days") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optInt(it) } },
            o.optInt("every", 60),
            o.optLong("at"),
            o.optJSONArray("deliver").strings().ifEmpty { listOf("notify") },
            o.optBoolean("enabled", true),
            o.optLong("lastRun"),
            o.optString("lastResult"),
        )
    }
}

/**
 * Scheduled bots: prompts that run on their own (a morning brief, a check on the house, a weekly
 * review of ideas). Each one is a WorkManager job, which survives reboots and Doze; after it runs it
 * books its own next run.
 */
class Schedules(private val ctx: Context) {
    private val file = JsonFile(ctx, "schedules.json")
    val all = mutableStateListOf<Schedule>().apply {
        addAll(file.readArray().objects().map(Schedule::from).filter { it.prompt.isNotBlank() })
    }

    fun get(id: String): Schedule? = all.firstOrNull { it.id == id }

    fun add(s: Schedule): Schedule {
        all.removeAll { it.id == s.id }
        all.add(s)
        persist()
        book(s)
        return s
    }

    fun setEnabled(id: String, on: Boolean) {
        val s = get(id) ?: return
        replace(s.copy(enabled = on))
        if (on) book(get(id)!!) else cancel(id)
    }

    fun delete(id: String): Boolean {
        cancel(id)
        val removed = all.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    fun recordRun(id: String, result: String) {
        val s = get(id) ?: return
        val next = s.copy(lastRun = System.currentTimeMillis(), lastResult = result.take(500))
        replace(if (s.kind == "once") next.copy(enabled = false) else next)
    }

    /** Books every enabled schedule. Safe to call on every launch; existing jobs are replaced. */
    fun bookAll() = all.filter { it.enabled }.forEach { book(it) }

    fun book(s: Schedule) {
        val next = s.nextRun() ?: run { cancel(s.id); return }
        val delay = maxOf(0L, next - System.currentTimeMillis())
        val req = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to s.id))
            .addTag("hermes-schedule")
            .build()
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniqueWork("schedule-${s.id}", ExistingWorkPolicy.REPLACE, req)
        }.onFailure { ErrorLog.record("Couldn't book '${s.name}'", it) }
    }

    private fun cancel(id: String) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork("schedule-$id") }
    }

    private fun replace(s: Schedule) {
        val i = all.indexOfFirst { it.id == s.id }
        if (i >= 0) all[i] = s else all.add(s)
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        file.write(arr.toString())
    }
}

class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val app = applicationContext as HermesApp
        val c = app.controller
        val schedule = withContext(Dispatchers.Main) { c.schedules.get(id) } ?: return Result.success()
        if (!schedule.enabled) return Result.success()

        val result = runCatching { c.runScheduled(schedule) }
            .getOrElse {
                ErrorLog.record("Scheduled bot '${schedule.name}' failed", it)
                "Didn't finish: ${it.message ?: it.javaClass.simpleName}"
            }

        withContext(Dispatchers.Main) {
            c.schedules.recordRun(id, result)
            c.schedules.get(id)?.let { c.schedules.book(it) }
        }
        return Result.success()
    }
}
