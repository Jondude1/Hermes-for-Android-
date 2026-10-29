package com.jondude1.hermes.agent

import com.jondude1.hermes.services.HermesAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val NOT_ENABLED =
    "Screen control is off. Ask the person to turn on 'Hermes screen control' in Settings, then try again."

private fun service(ctx: ToolContext): HermesAccessibilityService? =
    HermesAccessibilityService.instance?.also { ctx.task.usedScreen = true }

/** Tier 2: works in any app by reading the accessibility tree and performing gestures. */
object ScreenTools {
    fun all(): List<HermesTool> = listOf(ReadScreen, Tap, TypeText, Scroll, PressKey, Screenshot, Wait)

    private object ReadScreen : HermesTool {
        override val name = "read_screen"
        override val group = "screen"
        override val description =
            "List what is on screen as numbered elements like [12] Button \"Search\". Use the numbers with tap and type_text. " +
                "Indexes are only valid until the screen changes."
        override val inputSchema = jobj(emptyList())
        override fun stepLabel(input: JSONObject) = "Reading the screen"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = service(ctx) ?: return ToolResult(NOT_ENABLED, isError = true)
            return ToolResult(s.snapshot())
        }
    }

    private object Tap : HermesTool {
        override val name = "tap"
        override val group = "screen"
        override val description = "Tap an element by its index from read_screen, or by x and y screen coordinates."
        override val inputSchema = jobj(
            emptyList(),
            "index" to jint("Element index from read_screen"),
            "x" to jint("X coordinate in pixels"),
            "y" to jint("Y coordinate in pixels"),
        )
        override fun stepLabel(input: JSONObject) = "Tapping"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = service(ctx) ?: return ToolResult(NOT_ENABLED, isError = true)
            val msg = when {
                input.has("index") -> s.tapIndex(input.getInt("index"))
                input.has("x") && input.has("y") -> s.tapAt(input.getInt("x"), input.getInt("y"))
                else -> return ToolResult("Give an index, or x and y.", isError = true)
            }
            delay(450) // let the UI settle before the next read
            return ToolResult(msg, isError = msg.startsWith("No element") || msg.contains("failed"))
        }
    }

    private object TypeText : HermesTool {
        override val name = "type_text"
        override val group = "screen"
        override val description =
            "Type into a text field. Pass the field's index, or omit it to use the focused field. Set submit to press the keyboard's enter/search key afterwards."
        override val inputSchema = jobj(
            listOf("text"),
            "text" to jstr("Text to enter (replaces the field's content)"),
            "index" to jint("Field index from read_screen"),
            "submit" to jbool("Press enter/search after typing"),
        )
        override fun stepLabel(input: JSONObject) = "Typing"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = service(ctx) ?: return ToolResult(NOT_ENABLED, isError = true)
            val idx = if (input.has("index")) input.getInt("index") else null
            val msg = s.typeText(idx, input.optString("text"), input.optBoolean("submit", false))
            delay(350)
            return ToolResult(msg, isError = msg.startsWith("No ") || msg.contains("failed"))
        }
    }

    private object Scroll : HermesTool {
        override val name = "scroll"
        override val group = "screen"
        override val description = "Scroll the screen to reveal more content."
        override val inputSchema = jobj(listOf("direction"), "direction" to jenum("Which way the content moves into view", "down", "up", "left", "right"))
        override fun stepLabel(input: JSONObject) = "Scrolling ${input.optString("direction")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = service(ctx) ?: return ToolResult(NOT_ENABLED, isError = true)
            val ok = s.scroll(input.optString("direction", "down"))
            delay(400)
            return if (ok) ToolResult("Scrolled.") else ToolResult("Scroll failed.", isError = true)
        }
    }

    private object PressKey : HermesTool {
        override val name = "press_key"
        override val group = "screen"
        override val description = "Press a system key: back, home, recents, or notifications (pulls down the shade)."
        override val inputSchema = jobj(listOf("key"), "key" to jenum("Which key", "back", "home", "recents", "notifications"))
        override fun stepLabel(input: JSONObject) = "Pressing ${input.optString("key")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = service(ctx) ?: return ToolResult(NOT_ENABLED, isError = true)
            val ok = s.pressKey(input.optString("key"))
            delay(400)
            return if (ok) ToolResult("Pressed ${input.optString("key")}.") else ToolResult("Key press failed.", isError = true)
        }
    }

    private object Screenshot : HermesTool {
        override val name = "screenshot"
        override val group = "screen"
        override val description =
            "Take a screenshot to see the screen visually. Use when read_screen is empty or misses things like images and canvases. Secure apps return a blank image."
        override val inputSchema = jobj(emptyList())
        override fun stepLabel(input: JSONObject) = "Looking at the screen"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = service(ctx) ?: return ToolResult(NOT_ENABLED, isError = true)
            val b64 = s.screenshotJpegBase64()
                ?: return ToolResult("Screenshot failed or the screen is protected.", isError = true)
            return ToolResult("Screenshot attached.", imageBase64 = b64)
        }
    }

    private object Wait : HermesTool {
        override val name = "wait"
        override val group = "screen"
        override val description = "Pause briefly, for example while an app loads. Max 3000 ms."
        override val inputSchema = jobj(listOf("ms"), "ms" to jint("Milliseconds to wait"))
        override fun stepLabel(input: JSONObject) = "Waiting"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            delay(input.optLong("ms", 1000).coerceIn(100, 3000))
            return ToolResult("Waited.")
        }
    }
}
