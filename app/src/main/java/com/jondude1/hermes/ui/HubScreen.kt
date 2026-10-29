package com.jondude1.hermes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.core.Personas
import com.jondude1.hermes.core.ToolGroups

/**
 * Everything Hermes knows and can do, in the open: the profile and memories, learned skills,
 * personas and scheduled bots. Jon can read, correct and delete all of it.
 */
@Composable
fun HubScreen(c: AgentController, onClose: () -> Unit) {
    var tab by remember { mutableStateOf("Mind") }
    Box(
        Modifier
            .fillMaxSize()
            .background(W.Void.copy(alpha = 0.985f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Hermes", style = T.greeting.copy(fontSize = 30.sp, lineHeight = 36.sp), modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    CloseIcon(W.Fog)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Mind", "Skills", "Personas", "Bots")) { t ->
                    val on = t == tab
                    Text(
                        t,
                        style = T.button.copy(fontSize = 14.sp, color = if (on) W.Void else W.Bone),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (on) W.Gold else W.Haze)
                            .clickable { tab = t }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            when (tab) {
                "Mind" -> MindTab(c)
                "Skills" -> SkillsTab(c)
                "Personas" -> PersonasTab(c)
                else -> BotsTab(c, onClose)
            }
        }
    }
}

@Composable
private fun MindTab(c: AgentController) {
    var profile by remember { mutableStateOf(c.memory.profile) }
    var query by remember { mutableStateOf("") }
    var newMemory by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) c.memory.entries.toList() else c.memory.search(query, 50)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { SectionTitle("Profile: what Hermes always knows about you") }
        item { TextBox("", profile, { profile = it }, singleLine = false, minHeight = 140) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Save profile", color = if (profile != c.memory.profile) W.Gold else W.Bone) { c.memory.saveProfile(profile) }
                if (profile != c.memory.profile) Pill("Undo") { profile = c.memory.profile }
            }
        }
        item { SectionTitle("Memories (${c.memory.entries.size})") }
        item { TextBox("", newMemory, { newMemory = it }, hint = "Add something Hermes should remember") }
        item {
            if (newMemory.isNotBlank()) Pill("Remember this") { c.memory.save(newMemory, "fact"); newMemory = "" }
        }
        item { TextBox("", query, { query = it }, hint = "Search memories") }
        items(shown, key = { it.id }) { m ->
            Tile(m.text, "", "${m.kind} · used ${m.hits}×") {
                Pill("Forget", color = W.Ember) { c.memory.forget(m.id) }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun SkillsTab(c: AgentController) {
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Skills are procedures Hermes writes down after figuring something out, so the next time is faster and cheaper.",
                style = T.small,
            )
        }
        if (c.skills.all.isEmpty()) item { Text("None yet. They'll appear as Hermes learns.", style = T.body) }
        items(c.skills.all.toList(), key = { it.id }) { s ->
            Tile(s.name, s.description, "used ${s.uses}×") {
                Pill(if (open == s.id) "Hide" else "Read") { open = if (open == s.id) null else s.id }
                Pill("Delete", color = W.Ember) { c.skills.delete(s.id) }
            }
            if (open == s.id) Text(s.instructions, style = T.body, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun PersonasTab(c: AgentController) {
    var editing by remember { mutableStateOf<String?>(null) } // persona id, or "new"
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(ToolGroups.ALL.toSet()) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text("Personas are bots with their own role, tools and voice. All of them share Hermes's memory.", style = T.small)
        }
        items(c.personas.all.toList(), key = { it.id }) { p ->
            val active = p.id == c.personas.activeId
            Tile(p.name + if (active) "  ·  active" else "", p.role.take(160), p.groups.joinToString(" · ")) {
                if (!active) Pill("Use") { c.selectPersona(p.id) }
                if (p.id != Personas.DEFAULT_ID) {
                    Pill("Edit") { editing = p.id; name = p.name; role = p.role; groups = p.groups.toSet() }
                    Pill("Delete", color = W.Ember) { c.personas.delete(p.id) }
                }
            }
        }
        item {
            if (editing == null) Pill("New persona") { editing = "new"; name = ""; role = ""; groups = ToolGroups.ALL.toSet() }
        }
        if (editing != null) {
            item { SectionTitle(if (editing == "new") "New persona" else "Edit persona") }
            item { TextBox("Name", name, { name = it }) }
            item { TextBox("Role", role, { role = it }, singleLine = false, minHeight = 90, hint = "Who it is and how it works, e.g. 'You run the house. Terse, practical, safety-first.'") }
            items(ToolGroups.ALL) { g ->
                ToggleRow(g, groupHelp(g), g in groups) { on -> groups = if (on) groups + g else groups - g }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Save", color = W.Gold) {
                        if (name.isNotBlank() && role.isNotBlank()) {
                            c.personas.upsert(name, role, groups.toList())
                            editing = null
                        }
                    }
                    Pill("Cancel") { editing = null }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

private fun groupHelp(g: String) = when (g) {
    ToolGroups.PHONE -> "Alarms, texts, calendar, notifications, apps"
    ToolGroups.SCREEN -> "Read and drive any app on screen"
    ToolGroups.HOME -> "Home Assistant"
    ToolGroups.MEMORY -> "Remember, recall and learn skills"
    ToolGroups.WEB -> "Search and read the web"
    ToolGroups.BOTS -> "Subagents, personas and schedules"
    ToolGroups.CONNECTIONS -> "MCP servers"
    else -> ""
}

@Composable
private fun BotsTab(c: AgentController, onClose: () -> Unit) {
    var ask by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { Text("Scheduled bots run on their own and report back. Describe one and Hermes sets it up.", style = T.small) }
        item { TextBox("", ask, { ask = it }, singleLine = false, hint = "e.g. Every weekday at 7, a short brief: weather, calendar, and anything in the house that needs attention") }
        item {
            if (ask.isNotBlank()) Pill("Create it", color = W.Gold) {
                c.submit("Create a scheduled bot for this: $ask")
                ask = ""
                onClose()
            }
        }
        if (c.schedules.all.isEmpty()) item { Text("No scheduled bots yet.", style = T.body) }
        items(c.schedules.all.toList(), key = { it.id }) { s ->
            Tile(
                s.name,
                s.prompt.take(160),
                "${s.describe()} · ${s.deliver.joinToString("/")}" + if (s.lastResult.isNotBlank()) "\nLast: ${s.lastResult.take(120)}" else "",
            ) {
                Pill(if (s.enabled) "Pause" else "Resume") { c.schedules.setEnabled(s.id, !s.enabled) }
                Pill("Delete", color = W.Ember) { c.schedules.delete(s.id) }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

/** Bottom sheet for picking who you're talking to. */
@Composable
fun PersonaPicker(visible: Boolean, c: AgentController, onPick: (String) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(shape).background(W.Deep).border(1.dp, W.Haze, shape)
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Talk to", style = T.title.copy(fontSize = 21.sp))
                Spacer(Modifier.height(6.dp))
                c.personas.all.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(p.id) }.padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = T.bodyBone.copy(color = if (p.id == c.personas.activeId) W.Gold else W.Bone))
                            Text(p.role.take(80), style = T.small, maxLines = 1)
                        }
                        if (p.id == c.personas.activeId) CheckIcon(W.Gold)
                    }
                }
            }
        }
    }
}
