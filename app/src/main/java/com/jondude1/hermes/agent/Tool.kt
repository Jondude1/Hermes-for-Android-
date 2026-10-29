package com.jondude1.hermes.agent

import android.content.Context
import com.jondude1.hermes.core.Persona
import org.json.JSONArray
import org.json.JSONObject

// ---- JSON schema helpers -------------------------------------------------------------------

fun jstr(desc: String): JSONObject = JSONObject().put("type", "string").put("description", desc)
fun jint(desc: String): JSONObject = JSONObject().put("type", "integer").put("description", desc)
fun jbool(desc: String): JSONObject = JSONObject().put("type", "boolean").put("description", desc)
fun jenum(desc: String, vararg values: String): JSONObject =
    JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))

fun jarr(desc: String, item: JSONObject): JSONObject =
    JSONObject().put("type", "array").put("description", desc).put("items", item)

fun jobj(required: List<String>, vararg props: Pair<String, JSONObject>): JSONObject {
    val p = JSONObject()
    props.forEach { p.put(it.first, it.second) }
    return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
}

/** Free-form object, for passing arguments through to MCP and Home Assistant. */
fun jany(desc: String): JSONObject = JSONObject().put("type", "object").put("description", desc)

// ---- Where a run came from -----------------------------------------------------------------

enum class Channel(val label: String) {
    App("the app"),
    Voice("voice"),
    Telegram("Telegram"),
    Discord("Discord"),
    Schedule("a scheduled bot"),
    Subagent("a subagent"),
    OtherApp("another app");

    /** Requests that didn't come from someone holding the phone. */
    val remote: Boolean get() = this == Telegram || this == Discord || this == OtherApp
    val headless: Boolean get() = remote || this == Schedule || this == Subagent
}

// ---- Tool contract -------------------------------------------------------------------------

class ToolContext(
    val context: Context,
    val controller: AgentController,
    val task: AgentTask,
    val channel: Channel = Channel.App,
    val persona: Persona? = null,
    val depth: Int = 0,
)

class ToolResult(
    val text: String,
    val imageBase64: String? = null,
    val isError: Boolean = false,
)

/**
 * Three tiers, cheapest first. The model is told to prefer them in this order:
 *  1. direct tools (intents, content providers, Home Assistant, MCP) - fast and reliable
 *  2. screen tools (accessibility) - works in any app, slower and more fragile
 *  3. asking the person
 */
interface HermesTool {
    val name: String
    val description: String
    val inputSchema: JSONObject

    /** Tool group (see ToolGroups), used to give personas and subagents only what they need. */
    val group: String get() = "core"

    /** False for tools that need someone at the phone (questions, cards). */
    val headlessOk: Boolean get() = true

    /** Max time one call may take before it's abandoned. */
    val timeoutMs: Long get() = 60_000L

    /** Short present-tense label for the task tray and capsule, e.g. "Opening Chrome". */
    fun stepLabel(input: JSONObject): String = name.replace('_', ' ')

    suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult
}

class ToolRegistry(private val tools: List<HermesTool>) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): HermesTool? = byName[name]

    val names: List<String> get() = tools.map { it.name }

    fun toJson(withWebSearch: Boolean): JSONArray {
        val arr = JSONArray()
        tools.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("description", it.description)
                    .put("input_schema", it.inputSchema)
            )
        }
        if (withWebSearch) {
            arr.put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", 5)
            )
        }
        return arr
    }
}
