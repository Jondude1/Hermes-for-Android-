package com.jondude1.hermes.agent

import com.jondude1.hermes.core.ToolGroups
import org.json.JSONObject

/** Long-term memory, the core profile and self-written skills. */
object MemoryTools {
    fun all(): List<HermesTool> = listOf(Remember, Recall, Forget, UpdateProfile, SkillSave, SkillView, SkillDelete)

    private object Remember : HermesTool {
        override val name = "remember"
        override val group = ToolGroups.MEMORY
        override val description =
            "Save something worth knowing next time: a fact about Jon, a preference, a person, a project, a decision. " +
                "One fact per call, written so it makes sense on its own later. Save what he tells you, not your guesses. " +
                "Never save passwords, card numbers, codes or health details."
        override val inputSchema = jobj(
            listOf("text"),
            "text" to jstr("The fact, as a standalone sentence"),
            "kind" to jenum("What sort of memory", "fact", "preference", "person", "project", "event"),
        )
        override fun stepLabel(input: JSONObject) = "Remembering"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val text = input.optString("text").trim()
            if (text.length < 4) return ToolResult("Nothing to remember.", isError = true)
            if (Regex("\\b\\d{12,19}\\b").containsMatchIn(text.replace(" ", ""))) {
                return ToolResult("That looks like a card or account number. Not saved.", isError = true)
            }
            val e = ctx.controller.memory.save(text, input.optString("kind").ifBlank { "fact" })
            return ToolResult("Saved (id ${e.id}).")
        }
    }

    private object Recall : HermesTool {
        override val name = "recall"
        override val group = ToolGroups.MEMORY
        override val description =
            "Search long-term memory. The most relevant memories are already in your prompt; use this to look for anything else."
        override val inputSchema = jobj(listOf("query"), "query" to jstr("Words to search for"))
        override fun stepLabel(input: JSONObject) = "Searching memory"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val found = ctx.controller.memory.search(input.optString("query"), 12)
            return ToolResult(
                if (found.isEmpty()) "Nothing found."
                else found.joinToString("\n") { "[${it.id}] (${it.kind}) ${it.text}" }
            )
        }
    }

    private object Forget : HermesTool {
        override val name = "forget"
        override val group = ToolGroups.MEMORY
        override val description = "Delete a memory by id, or correct it by giving new text. Use when Jon says something is wrong or outdated."
        override val inputSchema = jobj(
            listOf("id"),
            "id" to jstr("Memory id from recall or the prompt"),
            "new_text" to jstr("Optional corrected text; omit to delete"),
        )
        override fun stepLabel(input: JSONObject) = "Updating memory"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val id = input.optString("id")
            val newText = input.optString("new_text").trim()
            val ok = if (newText.isNotEmpty()) ctx.controller.memory.update(id, newText) else ctx.controller.memory.forget(id)
            return if (ok) ToolResult(if (newText.isNotEmpty()) "Corrected." else "Forgotten.")
            else ToolResult("No memory with id $id.", isError = true)
        }
    }

    private object UpdateProfile : HermesTool {
        override val name = "update_profile"
        override val group = ToolGroups.MEMORY
        override val description =
            "Rewrite Jon's core profile, the short text you always carry about who he is and how he works. " +
                "Only for durable, important changes. Send the whole new profile, keeping everything still true."
        override val inputSchema = jobj(listOf("profile"), "profile" to jstr("The complete new profile text"))
        override fun stepLabel(input: JSONObject) = "Updating profile"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val text = input.optString("profile").trim()
            if (text.length < 40) return ToolResult("That profile is too short; send the whole thing.", isError = true)
            ctx.controller.memory.setProfile(text)
            return ToolResult("Profile updated.")
        }
    }

    private object SkillSave : HermesTool {
        override val name = "skill_save"
        override val group = ToolGroups.MEMORY
        override val description =
            "Write down a reusable procedure as a skill, or improve an existing one with the same name. Do this after you " +
                "work out how to do something that will come up again (a multi-step app flow, a Home Assistant routine, a report format). " +
                "Instructions should be concrete steps, including which tools to use and what tripped you up."
        override val inputSchema = jobj(
            listOf("name", "description", "instructions"),
            "name" to jstr("Short name, e.g. 'order-usual-coffee'"),
            "description" to jstr("One line: when to use it"),
            "instructions" to jstr("The steps"),
        )
        override fun stepLabel(input: JSONObject) = "Learning a skill"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = ctx.controller.skills.upsert(input.optString("name"), input.optString("description"), input.optString("instructions"))
            return ToolResult("Skill '${s.name}' saved.")
        }
    }

    private object SkillView : HermesTool {
        override val name = "skill_view"
        override val group = ToolGroups.MEMORY
        override val description = "Load a skill's full instructions before doing a task it covers."
        override val inputSchema = jobj(listOf("name"), "name" to jstr("Skill name from the list"))
        override fun stepLabel(input: JSONObject) = "Using skill ${input.optString("name")}"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = ctx.controller.skills.find(input.optString("name"))
                ?: return ToolResult("No skill named '${input.optString("name")}'.", isError = true)
            ctx.controller.skills.markUsed(s)
            return ToolResult("Skill ${s.name}: ${s.description}\n\n${s.instructions}")
        }
    }

    private object SkillDelete : HermesTool {
        override val name = "skill_delete"
        override val group = ToolGroups.MEMORY
        override val description = "Delete a skill that is wrong or no longer useful."
        override val inputSchema = jobj(listOf("name"), "name" to jstr("Skill name"))
        override fun stepLabel(input: JSONObject) = "Removing a skill"
        override suspend fun run(input: JSONObject, ctx: ToolContext): ToolResult {
            val s = ctx.controller.skills.find(input.optString("name"))
                ?: return ToolResult("No such skill.", isError = true)
            ctx.controller.skills.delete(s.id)
            return ToolResult("Deleted.")
        }
    }
}
