package com.jondude1.hermes.agent

import com.jondude1.hermes.core.Personas
import com.jondude1.hermes.core.Schedule
import com.jondude1.hermes.core.ToolGroups
import com.jondude1.hermes.core.newId
import com.jondude1.hermes.core.objects
import com.jondude1.hermes.core.strings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/** Subagents, named personas, scheduled bots and outbound messages. */
object BotTools {
    fun all(): List<HermesTool> = listOf(
        Delegate, PersonaSave, PersonaDelete, ScheduleCreate, ScheduleList, ScheduleDelete, NotifyJon,
    )

    private object Delegate : HermesTool {
        override val name = "delegate"
        override val group = ToolGroups.BOTS
        override val description =
            "Hand work to subagents that run in parallel, each with a fresh context, and get their reports back. " +
                "Use for research or comparisons with separate parts, or anything that would flood your own context. " +
                "Give each one a complete, self-contained brief; they can't see this conversation or ask Jon questions. " +
                "Subagents can't control the screen. Up to 4 at once."
        override val inputSchema = jobj(
            listOf("tasks"),
            "tasks" to jarr(
                "One entry per subagent",
                jobj(
                    listOf("brief"),
                    "brief" to jstr("Everything the subagent needs to know and what to report back"),
                    "persona" to jstr("Optional persona name to run as"),
                ),
            ),
        )
        override val timeoutMs = 600_000L
        override fun stepLabel(input: JSONObject) = "Briefing ${input.optJSONArray("tasks")?.length() ?: 0} subagents"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            if (ctx.depth > 0) return ToolResult("Subagents can't start their own subagents. Do it yourself.", isError = true)
            val tasks = (input.optJSONArray("tasks") ?: JSONArray()).objects().take(4)
            if (tasks.isEmpty()) return ToolResult("No tasks given.", isError = true)
            val reports = coroutineScope {
                tasks.mapIndexed { i, t ->
                    async {
                        val persona = t.optString("persona").takeIf { it.isNotBlank() }?.let { ctx.controller.personas.byName(it) }
                        val r = runCatching { ctx.controller.runSubagent(ctx.task, t.optString("brief"), persona) }
                            .getOrElse { "Failed: ${it.message}" }
                        "Subagent ${i + 1}${persona?.let { " (${it.name})" }.orEmpty()}:\n$r"
                    }
                }.map { it.await() }
            }
            return ToolResult(reports.joinToString("\n\n---\n\n").take(16000))
        }
    }

    private object PersonaSave : HermesTool {
        override val name = "persona_save"
        override val group = ToolGroups.BOTS
        override val description =
            "Create or update a named bot persona: its role and behavior, which tool groups it may use, and its voice. " +
                "Groups: phone, screen, home, memory, web, bots, connections."
        override val inputSchema = jobj(
            listOf("name", "role"),
            "name" to jstr("Short name, e.g. 'Quartermaster'"),
            "role" to jstr("Who it is and how it works, in second person"),
            "groups" to jarr("Tool groups it may use; omit for all", jstr("group")),
            "voice_pitch" to jstr("0.5 to 2.0, default 1.0"),
            "voice_rate" to jstr("0.5 to 2.0, default 1.0"),
        )
        override fun stepLabel(input: JSONObject) = "Setting up ${input.optString("name")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val p = ctx.controller.personas.upsert(
                input.optString("name"), input.optString("role"),
                input.optJSONArray("groups").strings().ifEmpty { ToolGroups.ALL },
                input.optString("voice_pitch").toFloatOrNull() ?: 1f,
                input.optString("voice_rate").toFloatOrNull() ?: 1f,
            )
            return ToolResult("Persona '${p.name}' saved with ${p.groups.joinToString(", ")}.")
        }
    }

    private object PersonaDelete : HermesTool {
        override val name = "persona_delete"
        override val group = ToolGroups.BOTS
        override val description = "Delete a persona. The default Hermes persona can't be deleted."
        override val inputSchema = jobj(listOf("name"), "name" to jstr("Persona name"))
        override fun stepLabel(input: JSONObject) = "Removing ${input.optString("name")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val p = ctx.controller.personas.byName(input.optString("name")) ?: return ToolResult("No such persona.", isError = true)
            return if (ctx.controller.personas.delete(p.id)) ToolResult("Deleted.") else ToolResult("Can't delete ${p.name}.", isError = true)
        }
    }

    private object ScheduleCreate : HermesTool {
        override val name = "schedule_create"
        override val group = ToolGroups.BOTS
        override val description =
            "Create a scheduled bot: a prompt that runs on its own and delivers the result. Examples: a weekday morning brief, " +
                "an hourly check that the garage is closed after 10pm, a one-off reminder. The prompt runs with no one watching, " +
                "so write it as a complete instruction. Delivery: notify (phone notification), speak, telegram, discord."
        override val inputSchema = jobj(
            listOf("name", "prompt", "kind"),
            "name" to jstr("Short name"),
            "prompt" to jstr("What to do each time, as a complete instruction"),
            "kind" to jenum("When", "once", "daily", "weekly", "interval"),
            "time" to jstr("HH:mm (24h) for daily or weekly"),
            "days" to jarr("For weekly: 1=Mon .. 7=Sun", jint("day")),
            "every_minutes" to jint("For interval, at least 15"),
            "at" to jstr("For once: local date-time like 2026-11-02T07:30"),
            "persona" to jstr("Optional persona name to run as"),
            "deliver" to jarr("How to deliver: notify, speak, telegram, discord", jstr("channel")),
        )
        override fun stepLabel(input: JSONObject) = "Scheduling ${input.optString("name")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val kind = input.optString("kind").takeIf { it in setOf("once", "daily", "weekly", "interval") } ?: "daily"
            val at = if (kind == "once") {
                runCatching { LocalDateTime.parse(input.optString("at")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                    .getOrNull() ?: return ToolResult("For a one-off schedule, 'at' must look like 2026-11-02T07:30.", isError = true)
            } else 0L
            val days = (input.optJSONArray("days") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optInt(it) } }
            val persona = input.optString("persona").takeIf { it.isNotBlank() }?.let { ctx.controller.personas.byName(it)?.id }
            val s = Schedule(
                newId(), input.optString("name").take(40), input.optString("prompt").take(3000),
                persona ?: Personas.DEFAULT_ID, kind, input.optString("time").ifBlank { "08:00" }, days,
                input.optInt("every_minutes", 60), at,
                input.optJSONArray("deliver").strings().ifEmpty { listOf("notify") },
                true, 0L, "",
            )
            val next = s.nextRun() ?: return ToolResult("That time is already in the past.", isError = true)
            ctx.controller.schedules.add(s)
            val nextText = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), ZoneId.systemDefault())
            return ToolResult("Scheduled '${s.name}' (${s.describe()}). Next run: $nextText.")
        }
    }

    private object ScheduleList : HermesTool {
        override val name = "schedule_list"
        override val group = ToolGroups.BOTS
        override val description = "List scheduled bots with their timing, status and last result."
        override val inputSchema = jobj(emptyList())
        override fun stepLabel(input: JSONObject) = "Checking schedules"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val all = ctx.controller.schedules.all
            if (all.isEmpty()) return ToolResult("No scheduled bots.")
            return ToolResult(all.joinToString("\n") {
                "${it.name} [${if (it.enabled) "on" else "off"}] ${it.describe()} | last: ${it.lastResult.take(80).ifBlank { "never run" }}"
            })
        }
    }

    private object ScheduleDelete : HermesTool {
        override val name = "schedule_delete"
        override val group = ToolGroups.BOTS
        override val description = "Delete a scheduled bot by name."
        override val inputSchema = jobj(listOf("name"), "name" to jstr("Schedule name"))
        override fun stepLabel(input: JSONObject) = "Removing a schedule"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = ctx.controller.schedules.all.firstOrNull { it.name.equals(input.optString("name"), ignoreCase = true) }
                ?: return ToolResult("No schedule by that name.", isError = true)
            ctx.controller.schedules.delete(s.id)
            return ToolResult("Deleted '${s.name}'.")
        }
    }

    private object NotifyJon : HermesTool {
        override val name = "notify_jon"
        override val group = ToolGroups.BOTS
        override val description =
            "Reach Jon right now even if he isn't looking at the app: a phone notification, and optionally a Telegram " +
                "or Discord message if those are connected. Use for scheduled bots and anything time-sensitive."
        override val inputSchema = jobj(
            listOf("message"),
            "message" to jstr("Short message"),
            "via" to jarr("notify, telegram, discord (default notify)", jstr("channel")),
        )
        override fun stepLabel(input: JSONObject) = "Messaging Jon"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val via = input.optJSONArray("via").strings().ifEmpty { listOf("notify") }
            val sent = ctx.controller.deliver(input.optString("message"), via, title = ctx.persona?.name ?: "Hermes")
            return ToolResult("Sent via ${sent.joinToString(", ").ifBlank { "nothing (check connections)" }}.")
        }
    }
}
