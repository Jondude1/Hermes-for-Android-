package com.jondude1.hermes.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Generative UI. The model describes what it wants to show; Hermes validates the spec and renders it
 * with its own components. The model can never inject layout, code or styling.
 */
private val KINDS = setOf("info", "list", "metric", "steps")
private val STYLES = setOf("primary", "quiet", "danger")

object UiTools {
    fun all(): List<HermesTool> = listOf(ShowCard, AskUser, RequestApproval)

    private object ShowCard : HermesTool {
        override val name = "show_card"
        override val description =
            "Show a card on the home screen. Kinds: info (title and short body), list (rows of label/detail/value), " +
                "metric (one big value with a label, put it in items[0]), steps (a checklist, set done on each item). " +
                "Use for results the person will glance at. Keep text short."
        override val inputSchema = jobj(
            listOf("kind", "title"),
            "kind" to jenum("Card kind", "info", "list", "metric", "steps"),
            "title" to jstr("Short title"),
            "body" to jstr("Optional one or two sentences"),
            "items" to jarr(
                "Rows for list, metric or steps (max 12)",
                jobj(
                    listOf("label"),
                    "label" to jstr("Row label"),
                    "detail" to jstr("Optional smaller text under the label"),
                    "value" to jstr("Optional right-aligned value, or the big number for metric"),
                    "done" to jbool("For steps: whether it is done"),
                ),
            ),
            "actions" to jarr(
                "Optional buttons (max 3). Tapping one sends its label back as the person's next message.",
                jobj(
                    listOf("label"),
                    "label" to jstr("Button text"),
                    "style" to jenum("Look", "primary", "quiet", "danger"),
                ),
            ),
        )
        override fun stepLabel(input: JSONObject) = "Showing ${input.optString("title").take(30)}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val kind = input.optString("kind").takeIf { it in KINDS } ?: "info"
            val items = parseItems(input.optJSONArray("items"))
            val actions = parseActions(input.optJSONArray("actions"))
            val card = AgentCard(
                id = UUID.randomUUID().toString(),
                kind = kind,
                title = input.optString("title").take(80),
                body = input.optString("body").take(500),
                items = items,
                actions = actions,
            )
            ctx.controller.pushCard(card)
            return ToolResult("Card shown.")
        }

        private fun parseItems(arr: JSONArray?): List<CardItem> {
            if (arr == null) return emptyList()
            val out = mutableListOf<CardItem>()
            for (i in 0 until minOf(arr.length(), 12)) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label").take(80)
                if (label.isBlank()) continue
                out.add(CardItem(label, o.optString("detail").take(120), o.optString("value").take(40), o.optBoolean("done")))
            }
            return out
        }

        private fun parseActions(arr: JSONArray?): List<CardAction> {
            if (arr == null) return emptyList()
            val out = mutableListOf<CardAction>()
            for (i in 0 until minOf(arr.length(), 3)) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label").take(40)
                if (label.isBlank()) continue
                val style = o.optString("style").takeIf { it in STYLES } ?: "quiet"
                out.add(CardAction("a$i", label, style))
            }
            return out
        }
    }

    private object AskUser : HermesTool {
        override val name = "ask_user"
        override val headlessOk = false
        override val description =
            "Ask the person a question and wait for the answer. Give 2-4 short options when the answer is a choice; " +
                "leave options empty for an open question (they reply in the input box). Returns what they chose or typed."
        override val inputSchema = jobj(
            listOf("question"),
            "question" to jstr("The question, one sentence"),
            "options" to jarr("Short answer buttons (max 4)", jstr("Option text")),
        )
        override fun stepLabel(input: JSONObject) = "Asking you a question"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val opts = mutableListOf<String>()
            input.optJSONArray("options")?.let { a ->
                for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { opts.add(it) }
            }
            val answer = ctx.controller.ask(ctx.task, input.optString("question"), opts)
            return ToolResult("The person answered: $answer")
        }
    }

    private object RequestApproval : HermesTool {
        override val name = "request_approval"
        override val description =
            "Ask the person to approve an action before you do it. Required before anything that sends, pays, posts, deletes, orders or can't be undone inside another app. " +
                "Describe exactly what will happen. Returns approved or declined."
        override val inputSchema = jobj(
            listOf("title", "detail"),
            "title" to jstr("Short question, e.g. 'Place this order?'"),
            "detail" to jstr("Exactly what will happen, with amounts and recipients"),
            "irreversible" to jbool("True if it can't be undone"),
        )
        override fun stepLabel(input: JSONObject) = "Waiting for your OK"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val ok = ctx.controller.requestApproval(
                ctx.task,
                input.optString("title"),
                input.optString("detail"),
                input.optBoolean("irreversible", true),
            )
            return if (ok) ToolResult("Approved. Go ahead.") else ToolResult("Declined. Do not do it.")
        }
    }
}
