package com.jondude1.hermes.agent

import com.jondude1.hermes.core.ToolGroups
import com.jondude1.hermes.core.objects
import org.json.JSONObject

/** Home Assistant and MCP servers: how Hermes reaches the house and the rest of Jon's systems. */
object ConnectTools {
    fun all(): List<HermesTool> = listOf(HomeFind, HomeState, HomeControl, HomeAsk, McpTools, McpCall)

    /** Domains where a wrong call has physical consequences. */
    private val SENSITIVE = setOf("lock", "alarm_control_panel", "cover", "garage_door", "valve", "siren")

    private object HomeFind : HermesTool {
        override val name = "home_find"
        override val group = ToolGroups.HOME
        override val description =
            "Find Home Assistant entities by name, room or domain (light, switch, climate, sensor, lock, media_player...). " +
                "Returns entity_id, friendly name and current state. Use before home_control when you don't know the id."
        override val inputSchema = jobj(
            emptyList(),
            "query" to jstr("Words to match, e.g. 'kitchen' or 'office lamp'"),
            "domain" to jstr("Optional domain filter, e.g. 'light'"),
        )
        override fun stepLabel(input: JSONObject) = "Looking around the house"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val q = input.optString("query").lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val domain = input.optString("domain").trim().lowercase()
            val hits = ctx.controller.home.states().objects().filter { s ->
                val id = s.optString("entity_id")
                val friendly = s.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
                val hay = "$id $friendly".lowercase()
                (domain.isEmpty() || id.startsWith("$domain.")) && q.all { hay.contains(it) }
            }
            if (hits.isEmpty()) return ToolResult("No matching entities.")
            return ToolResult(hits.take(40).joinToString("\n") { s ->
                val a = s.optJSONObject("attributes")
                "${s.optString("entity_id")} | ${a?.optString("friendly_name").orEmpty()} | ${s.optString("state")}"
            } + if (hits.size > 40) "\n(${hits.size - 40} more; narrow the search)" else "")
        }
    }

    private object HomeState : HermesTool {
        override val name = "home_state"
        override val group = ToolGroups.HOME
        override val description = "Full state and attributes of one Home Assistant entity."
        override val inputSchema = jobj(listOf("entity_id"), "entity_id" to jstr("e.g. climate.living_room"))
        override fun stepLabel(input: JSONObject) = "Checking ${input.optString("entity_id")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = ctx.controller.home.state(input.optString("entity_id"))
            return ToolResult(s.toString(1).take(4000))
        }
    }

    private object HomeControl : HermesTool {
        override val name = "home_control"
        override val group = ToolGroups.HOME
        override val description =
            "Call a Home Assistant service, e.g. light.turn_on with entity_id and brightness_pct, climate.set_temperature, " +
                "media_player.media_pause, scene.turn_on, script.turn_on. Locks, alarms, garage doors and covers ask Jon first."
        override val inputSchema = jobj(
            listOf("domain", "service"),
            "domain" to jstr("Service domain, e.g. light"),
            "service" to jstr("Service, e.g. turn_on"),
            "entity_id" to jstr("Target entity, or comma-separated list"),
            "data" to jany("Extra service data, e.g. {\"brightness_pct\": 40}"),
        )
        override fun stepLabel(input: JSONObject) = "${input.optString("domain")}.${input.optString("service")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val domain = input.optString("domain").trim()
            val service = input.optString("service").trim()
            val data = input.optJSONObject("data")?.let { JSONObject(it.toString()) } ?: JSONObject()
            val entity = input.optString("entity_id").trim()
            if (entity.isNotEmpty()) data.put("entity_id", entity)
            if (domain in SENSITIVE || entity.substringBefore('.') in SENSITIVE) {
                val ok = ctx.controller.requestApproval(
                    ctx.task, "$domain.$service?", "Home Assistant: $domain.$service on ${entity.ifBlank { "(no entity)" }}", irreversible = false,
                )
                if (!ok) return ToolResult("Jon declined. Not done.")
            }
            ctx.controller.home.callService(domain, service, data)
            return ToolResult("Done: $domain.$service ${entity}".trim())
        }
    }

    private object HomeAsk : HermesTool {
        override val name = "home_ask"
        override val group = ToolGroups.HOME
        override val description =
            "Send a plain sentence to Home Assistant's own Assist, which knows the house's rooms and aliases. " +
                "Good for simple commands and questions ('turn off the upstairs lights', 'is the back door locked')."
        override val inputSchema = jobj(listOf("text"), "text" to jstr("The sentence"))
        override fun stepLabel(input: JSONObject) = "Asking the house"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult =
            ToolResult(ctx.controller.home.assist(input.optString("text")))
    }

    private object McpTools : HermesTool {
        override val name = "mcp_tools"
        override val group = ToolGroups.CONNECTIONS
        override val description = "List the tools a connected MCP server offers, with their input schemas."
        override val inputSchema = jobj(listOf("server"), "server" to jstr("Server name from the Connections list"))
        override fun stepLabel(input: JSONObject) = "Checking ${input.optString("server")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = ctx.controller.mcp.byName(input.optString("server"))
                ?: return ToolResult("No connected server named '${input.optString("server")}'.", isError = true)
            val tools = ctx.controller.mcp.listTools(s)
            if (tools.isEmpty()) return ToolResult("${s.name} offers no tools.")
            return ToolResult(tools.joinToString("\n\n") { "${it.name}: ${it.description}\ninput: ${it.schema.toString().take(800)}" }.take(12000))
        }
    }

    private object McpCall : HermesTool {
        override val name = "mcp_call"
        override val group = ToolGroups.CONNECTIONS
        override val description =
            "Call a tool on a connected MCP server. Check its schema with mcp_tools first if you haven't this session. " +
                "Anything that sends, deletes or publishes needs request_approval first."
        override val inputSchema = jobj(
            listOf("server", "tool"),
            "server" to jstr("Server name"),
            "tool" to jstr("Tool name"),
            "arguments" to jany("Arguments matching the tool's input schema"),
        )
        override val timeoutMs = 120_000L
        override fun stepLabel(input: JSONObject) = "${input.optString("server")}: ${input.optString("tool")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = ctx.controller.mcp.byName(input.optString("server"))
                ?: return ToolResult("No connected server named '${input.optString("server")}'.", isError = true)
            val (text, isError) = ctx.controller.mcp.call(s, input.optString("tool"), input.optJSONObject("arguments") ?: JSONObject())
            return ToolResult(text.take(12000), isError = isError)
        }
    }
}
