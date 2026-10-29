package com.jondude1.hermes.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jondude1.hermes.BuildConfig
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.services.HermesAccessibilityService
import com.jondude1.hermes.update.Updater
import com.jondude1.hermes.core.ErrorLog
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun roleHeld(ctx: Context, role: String): Boolean =
    runCatching { ctx.getSystemService(RoleManager::class.java).isRoleHeld(role) }.getOrDefault(false)

private fun open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun SettingsScreen(c: AgentController, host: HostActions, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val s = c.settings
    val scope = rememberCoroutineScope()
    c.resumeTick // reading this re-checks every status below whenever you come back from a system screen

    val a11yOn = HermesAccessibilityService.instance != null
    val notifOn = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val micOn = granted(ctx, Manifest.permission.RECORD_AUDIO)
    val smsOn = granted(ctx, Manifest.permission.SEND_SMS) && granted(ctx, Manifest.permission.READ_CONTACTS)
    val postOn = granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    val installOn = ctx.packageManager.canRequestPackageInstalls()

    Box(
        Modifier
            .fillMaxSize()
            .background(W.Void.copy(alpha = 0.985f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", style = T.greeting.copy(fontSize = 30.sp, lineHeight = 36.sp), modifier = Modifier.weight(1f))
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        CloseIcon(W.Fog)
                    }
                }
            }

            item { Section("The brain") }
            item { Field("Anthropic API key", s.apiKey, hide = true) { s.updateApiKey(it) } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Brain mode", style = T.bodyBone)
                    Text(
                        when (s.brainMode) {
                            "economy" -> "Economy: always the cheap model. Lowest cost, weaker on hard multi-step jobs."
                            "max" -> "Max: always the main model. Best results, about 20 times the cost of economy."
                            else -> "Balanced: the cheap model for everyday requests, the main model when a request looks hard or a run struggles."
                        },
                        style = T.small,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("economy" to "Economy", "balanced" to "Balanced", "max" to "Max").forEach { (k, label) ->
                            Pill(label, color = if (s.brainMode == k) W.Gold else W.Bone) { s.updateBrainMode(k) }
                        }
                    }
                }
            }
            item {
                val u = c.usage
                val pct = (u.monthCost / s.monthlyBudget * 100).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Spending (estimate)", style = T.bodyBone)
                    Text(
                        "This month: $" + "%.2f".format(u.monthCost) + " of $" + "%.0f".format(s.monthlyBudget) + " ($pct%) · today $" + "%.2f".format(u.dayCost) +
                            "\n${u.monthRequests} requests, ${u.cheapRequests} on the cheap model." +
                            if (c.overBudget) "\nBudget reached: Hermes is on the cheap model until next month or until you raise it." else "",
                        style = T.small.copy(color = if (c.overBudget) W.Ember else W.Fog),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5f, 10f, 20f, 40f).forEach { b ->
                            Pill("$" + b.toInt(), color = if (s.monthlyBudget == b) W.Gold else W.Bone) { s.updateBudget(b) }
                        }
                    }
                }
            }
            item { Field("Main model (hard requests)", s.model, hide = false) { s.updateModel(it) } }
            item { Field("Cheap model (everyday, bots, subagents)", s.fastModel, hide = false) { s.updateFastModel(it) } }
            item { Toggle("Web search", "Lets Hermes look things up. About a cent per search.", s.webSearch) { s.updateWebSearch(it) } }
            item {
                Item("Start a new conversation", "Clears the home screen thread. Memory, skills and bots are kept.", null, "New") { c.newConversation() }
            }

            item { Section("Voice") }
            item { Toggle("Speak replies", "After you talk to Hermes, it answers out loud.", s.speakReplies) { s.updateSpeak(it) } }
            item { Toggle("Conversation mode", "After answering out loud, Hermes listens again. Silence or \"that's all\" ends it.", s.conversationMode) { s.updateConversation(it) } }

            item { Section("Connections") }
            item { Text("Home Assistant", style = T.bodyBone) }
            item { Field("URL (Tailscale address works)", s.haUrl, hide = false) { s.updateHaUrl(it) } }
            item { Field("Long-lived access token", s.haToken, hide = true) { s.updateHaToken(it) } }
            item {
                var result by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Test") { scope.launch { result = runCatching { c.home.ping() }.fold({ "Connected: $it" }, { it.message ?: "Failed" }) } }
                    Text(result, style = T.small, modifier = Modifier.weight(1f))
                }
            }

            item { Text("MCP servers", style = T.bodyBone, modifier = Modifier.padding(top = 10.dp)) }
            item { Text("Any MCP server over HTTP: an Obsidian bridge, Nextcloud, your own tools on Obelisk.", style = T.small) }
            items(c.mcp.servers.toList(), key = { it.id }) { m ->
                Tile(m.name, m.url, c.mcp.status[m.name] ?: "Not checked yet") {
                    Pill("Test") { scope.launch { runCatching { c.mcp.listTools(m) }.onFailure { c.mcp.status[m.name] = "Failed: ${it.message}" } } }
                    Pill("Remove", color = W.Ember) { c.mcp.remove(m.id) }
                }
            }
            item {
                var name by remember { mutableStateOf("") }
                var url by remember { mutableStateOf("") }
                var token by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBox("", name, { name = it }, hint = "Name, e.g. obsidian")
                    TextBox("", url, { url = it }, hint = "URL, e.g. http://obelisk:8080/mcp")
                    TextBox("", token, { token = it }, hide = true, hint = "Bearer token (optional)")
                    if (name.isNotBlank() && url.isNotBlank()) Pill("Add server", color = W.Gold) {
                        val added = c.mcp.add(name, url, token)
                        name = ""; url = ""; token = ""
                        scope.launch { runCatching { c.mcp.listTools(added) }.onFailure { c.mcp.status[added.name] = "Failed: ${it.message}" } }
                    }
                }
            }

            item { Text("Telegram", style = T.bodyBone, modifier = Modifier.padding(top = 10.dp)) }
            item { Text("Make a bot with @BotFather, paste its token, then message it. It replies with your chat id; add that below.", style = T.small) }
            item { Field("Bot token", s.telegramToken, hide = true) { s.updateTelegramToken(it) } }
            item { Field("Allowed chat ids (comma-separated)", s.telegramChats, hide = false) { s.updateTelegramChats(it) } }
            item { Text("Status: ${c.gateways.telegram.status}", style = T.small) }

            item { Text("Discord", style = T.bodyBone, modifier = Modifier.padding(top = 10.dp)) }
            item { Text("Create a bot in the Discord developer portal, turn on the Message Content intent, paste its token. DM it to get your user id.", style = T.small) }
            item { Field("Bot token", s.discordToken, hide = true) { s.updateDiscordToken(it) } }
            item { Field("Allowed user ids (comma-separated)", s.discordUsers, hide = false) { s.updateDiscordUsers(it) } }
            item { Text("Status: ${c.gateways.discord.status}", style = T.small) }
            item { Toggle("Remote screen control", "Let Telegram and Discord requests drive apps on the phone. Off by default.", s.remoteScreen) { s.updateRemoteScreen(it) } }
            item { Item("Apply messaging changes", "Reconnects Telegram and Discord with the settings above.", null, "Reconnect") { c.gateways.restart() } }

            item { Text("Ask Hermes key", style = T.bodyBone, modifier = Modifier.padding(top = 10.dp)) }
            item {
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your other apps (DeskGlass, DriveDeck, Orbit) send this key with each request to Hermes.", style = T.small)
                    Text(s.askKey, style = T.small.copy(color = W.Bone))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Copy") { clip.setText(androidx.compose.ui.text.AnnotatedString(s.askKey)) }
                        Pill("New key", color = W.Ember) { s.rotateAskKey() }
                    }
                }
            }

            item { Section("Making it your phone") }
            item {
                Item(
                    "Home screen", "Make Hermes what you see when you press Home.",
                    roleHeld(ctx, RoleManager.ROLE_HOME), "Set as home",
                ) { host.requestRole(RoleManager.ROLE_HOME) }
            }
            item {
                Item(
                    "Assistant", "Power-button hold or a corner swipe opens Hermes, already listening.",
                    roleHeld(ctx, RoleManager.ROLE_ASSISTANT), "Set as assistant",
                ) { host.requestRole(RoleManager.ROLE_ASSISTANT) }
            }
            item { Toggle("Immersive home", "Hide the status and navigation bars. Swipe from an edge to peek.", s.immersive) { s.updateImmersive(it); host.setImmersive(it) } }

            item { Section("What Hermes is allowed to do") }
            item {
                Item(
                    "Screen control", "Read the screen and tap, type and scroll in other apps, only while a task you started is running.",
                    a11yOn, "Turn on",
                ) { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            if (!a11yOn) {
                item {
                    Item(
                        "Can't switch it on?", "Apps installed outside the Play Store need one extra step: open App info, tap the menu, choose Allow restricted settings.",
                        false, "Open App info",
                    ) { open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                }
            }
            item {
                Item(
                    "Notification access", "So Hermes can tell you what you missed.",
                    notifOn, "Turn on",
                ) { open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            item {
                Item("Microphone", "For talking to Hermes.", micOn, "Allow") {
                    host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            }
            item {
                Item("Texting and contacts", "Look up a contact and send a text, after you approve each one.", smsOn, "Allow") {
                    host.requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
                }
            }
            item {
                Item("Notifications from Hermes", "Shows that a task is running in the background.", postOn, "Allow") {
                    host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            item {
                Item("Install updates", "Lets Hermes install its own new versions.", installOn, "Allow") {
                    open(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                }
            }

            item { Section("Error log") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ErrorLog.lines.isEmpty()) Text("No errors recorded.", style = T.small)
                    ErrorLog.lines.takeLast(25).asReversed().forEach { Text(it, style = T.small.copy(fontSize = 12.sp)) }
                    if (ErrorLog.lines.isNotEmpty()) Pill("Clear") { ErrorLog.clear() }
                }
            }

            item { Section("Updates") }
            item {
                Item(
                    "Version ${BuildConfig.VERSION_NAME}",
                    Updater.status.ifBlank { "Hermes checks for new versions on its own." },
                    null, "Check now",
                ) { scope.launch { Updater.check(force = true) } }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = T.small.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 18.dp))
}

/** One row of status. `ok` true shows a check, false or null shows the action button. */
@Composable
private fun Item(title: String, subtitle: String, ok: Boolean?, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = T.bodyBone)
            Text(subtitle, style = T.small)
        }
        if (ok == true) {
            CheckIcon(W.Tide, Modifier.size(22.dp))
        } else {
            Text(
                action,
                style = T.button.copy(fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(W.Haze)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = T.bodyBone)
            Text(subtitle, style = T.small)
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = W.Void,
                checkedTrackColor = W.Bone,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = W.Fog,
                uncheckedTrackColor = W.Haze,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Field(label: String, value: String, hide: Boolean, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = T.small)
        val shape = RoundedCornerShape(16.dp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = T.bodyBone,
            cursorBrush = SolidColor(W.Bone),
            visualTransformation = if (hide) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(W.Deep)
                .border(1.dp, W.Haze, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}
