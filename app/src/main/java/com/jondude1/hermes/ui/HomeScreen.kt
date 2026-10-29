package com.jondude1.hermes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.agent.AgentSaid
import com.jondude1.hermes.agent.CardShown
import com.jondude1.hermes.agent.FeedItem
import com.jondude1.hermes.agent.OrbState
import com.jondude1.hermes.agent.UserSaid
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Things only the Activity can do. */
class HostActions(
    val requestPermissions: (Array<String>) -> Unit,
    val requestRole: (String) -> Unit,
    val setImmersive: (Boolean) -> Unit,
)

@Composable
fun HomeScreen(c: AgentController, onMic: () -> Unit, host: HostActions) {
    var drawer by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var taskPanel by remember { mutableStateOf(false) }
    var hub by remember { mutableStateOf(false) }
    var personaPicker by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    // As the home screen there is nothing to go back to; Back just closes whatever is open.
    BackHandler(enabled = true) {
        when {
            c.approval != null -> Unit
            personaPicker -> personaPicker = false
            hub -> hub = false
            settings -> settings = false
            drawer -> drawer = false
            taskPanel -> taskPanel = false
            else -> focus.clearFocus()
        }
    }

    fun send() {
        val text = input
        input = ""
        focus.clearFocus()
        c.submit(text)
    }

    Box(Modifier.fillMaxSize().background(W.Void)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp),
        ) {
            TopBar(
                c = c,
                onSettings = { settings = true },
                onHub = { hub = true },
                onPersona = { personaPicker = true },
            )
            UpdateBanner()

            // Orb stage: tap to talk, swipe up for apps
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(0.62f)
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -90f) drawer = true },
                        ) { _, dy -> total += dy }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val orbSize = minOf(minOf(maxWidth * 0.78f, maxHeight - 44.dp), 300.dp).coerceAtLeast(96.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Sphere(
                        state = c.orb,
                        level = c.level,
                        modifier = Modifier
                            .size(orbSize)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (c.inConversation && c.orb != OrbState.Listening) c.endConversation() else onMic()
                            },
                    )
                    val line = c.partial.ifBlank { c.statusLine }.ifBlank {
                        if (c.inConversation) "Conversation on. Say \"that's all\" or tap to end." else ""
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = line,
                        style = if (c.partial.isNotBlank()) T.bodyBone else T.small,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.height(38.dp),
                    )
                }
            }

            // Conversation, anchored to the bottom
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (c.feed.isEmpty()) {
                    Greeting(firstRun = c.tasks.isEmpty(), onPick = { c.submit(it) })
                } else {
                    val newestFirst = c.feed.reversed()
                    LazyColumn(
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(newestFirst, key = { it.key }) { FeedRow(it, c) }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .align(Alignment.TopCenter)
                            .background(Brush.verticalGradient(listOf(W.Void, Color.Transparent)))
                    )
                }
            }

            if (c.tasks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TaskTray(c.tasks, onOpen = { taskPanel = true })
            }
            Spacer(Modifier.height(12.dp))
            InputBar(
                value = input,
                onValue = { input = it },
                listening = c.orb == OrbState.Listening,
                onSend = ::send,
                onMic = onMic,
                onApps = { drawer = true },
            )
            Spacer(Modifier.height(12.dp))
        }

        // Layers, lowest to highest
        AnimatedVisibility(
            visible = hub,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
        ) { HubScreen(c, onClose = { hub = false }) }

        PersonaPicker(
            visible = personaPicker,
            c = c,
            onPick = { c.selectPersona(it); personaPicker = false },
            onClose = { personaPicker = false },
        )

        TaskPanel(
            visible = taskPanel,
            tasks = c.tasks,
            onStop = { c.stopTask(it) },
            onClear = { c.clearFinished(); if (c.tasks.isEmpty()) taskPanel = false },
            onClose = { taskPanel = false },
        )

        AnimatedVisibility(
            visible = drawer,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
        ) { AppsDrawer(c, onClose = { drawer = false }) }

        AnimatedVisibility(
            visible = settings,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
        ) { SettingsScreen(c, host, onClose = { settings = false }) }

        ApprovalPanel(c.approval) { a, ok -> c.resolveApproval(a, ok) }
    }
}

@Composable
private fun TopBar(c: AgentController, onSettings: () -> Unit, onHub: () -> Unit, onPersona: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(now.format(DateTimeFormatter.ofPattern("h:mm")), style = T.title.copy(fontSize = 19.sp))
            Text(now.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), style = T.small)
        }
        // Who you're talking to. Tap to switch persona.
        Text(
            c.personas.active.name,
            style = T.small.copy(color = W.Gold, fontSize = 14.sp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, W.Haze, RoundedCornerShape(16.dp))
                .clickable(onClick = onPersona)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onHub),
            contentAlignment = Alignment.Center,
        ) { GridIcon(W.Fog) }
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { DotsIcon(W.Fog) }
    }
}

@Composable
private fun Greeting(firstRun: Boolean, onPick: (String) -> Unit) {
    val hour = remember { LocalDateTime.now().hour }
    val hello = when (hour) {
        in 5..11 -> "Morning, Jon."
        in 12..16 -> "Afternoon, Jon."
        in 17..21 -> "Evening, Jon."
        else -> "Still up, Jon?"
    }
    val prompts = if (firstRun) listOf(
        "Tell me what you already know about me" to "Tell me what you already know about me, and ask me anything you'd want to know to work well with me.",
        "What can you do?" to "In a few lines, what can you do for me right now, and what should I connect next?",
        "What did I miss?" to "What did I miss in my notifications?",
    ) else listOf(
        "What did I miss?" to "What did I miss in my notifications?",
        "Set up a morning brief" to "Set up a scheduled bot that gives me a short morning brief on weekdays.",
        "How's the house?" to "Give me a one-line status of the house.",
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Text(hello, style = T.greeting)
        Spacer(Modifier.height(18.dp))
        prompts.forEach { (label, prompt) ->
            Text(
                label,
                style = T.body.copy(fontSize = 17.sp),
                modifier = Modifier.fillMaxWidth().clickable { onPick(prompt) }.padding(vertical = 9.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeedRow(item: FeedItem, c: AgentController) {
    when (item) {
        is UserSaid -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                item.text,
                style = T.small.copy(color = W.Bone, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(W.Haze)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        is AgentSaid -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            item.who?.let { Text(it, style = T.small.copy(color = W.Gold, fontSize = 12.sp)) }
            Text(item.text, style = T.speech)
        }
        is CardShown -> CardView(item.card) { c.onCardAction(item.card, it) }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValue: (String) -> Unit,
    listening: Boolean,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onApps: () -> Unit,
) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(W.Deep)
            .border(1.dp, W.Haze, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onApps), contentAlignment = Alignment.Center) {
            GridIcon(W.Fog)
        }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Ask Hermes", style = T.body.copy(fontSize = 17.sp))
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = T.bodyBone.copy(fontSize = 17.sp),
                cursorBrush = SolidColor(W.Bone),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val hasText = value.isNotBlank()
        val filled = hasText || listening
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (filled) W.Bone else W.Haze)
                .clickable { if (hasText) onSend() else onMic() },
            contentAlignment = Alignment.Center,
        ) {
            if (hasText) SendIcon(W.Void) else MicIcon(if (listening) W.Void else W.Bone)
        }
    }
}
