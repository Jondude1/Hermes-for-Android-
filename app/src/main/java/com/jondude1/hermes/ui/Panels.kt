package com.jondude1.hermes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude1.hermes.agent.AgentTask
import com.jondude1.hermes.agent.Approval
import com.jondude1.hermes.agent.CardAction
import com.jondude1.hermes.agent.TaskStatus

// ---- Approval --------------------------------------------------------------------------------

/**
 * The human gate. Anything that sends, pays or can't be undone stops here until you say so. Ember
 * is used nowhere else on the home screen, so seeing it means "this needs you".
 */
@Composable
fun ApprovalPanel(approval: Approval?, onResolve: (Approval, Boolean) -> Unit) {
    var shown by remember { mutableStateOf<Approval?>(null) }
    if (approval != null) shown = approval

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = approval != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            )
        }
        AnimatedVisibility(
            visible = approval != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val a = shown
            if (a != null) {
                val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(W.Deep)
                        .border(1.dp, W.Haze, shape)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(W.Ember))
                    Spacer(Modifier.size(4.dp))
                    Text(a.title, style = T.title.copy(fontSize = 21.sp))
                    Text(a.detail, style = T.body)
                    if (a.irreversible) Text("This can't be undone.", style = T.small.copy(color = W.Ember))
                    Spacer(Modifier.size(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(CardAction("no", "Decline", "quiet"), Modifier.weight(1f)) { onResolve(a, false) }
                        ActionButton(CardAction("yes", "Approve", "primary"), Modifier.weight(1f)) { onResolve(a, true) }
                    }
                }
            }
        }
    }
}


// ---- Task tray -------------------------------------------------------------------------------

@Composable
fun StatusDot(status: TaskStatus, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "dot")
    val a by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dotAlpha",
    )
    val color = when (status) {
        TaskStatus.Running -> W.Tide
        TaskStatus.WaitingForYou -> W.Ember
        TaskStatus.Done -> W.Fog
        TaskStatus.Failed -> W.Rose
        TaskStatus.Stopped -> W.Fog
    }
    val live = status == TaskStatus.Running || status == TaskStatus.WaitingForYou
    Box(modifier.size(8.dp).clip(CircleShape).background(color).alpha(if (live) a else 1f))
}

@Composable
fun TaskTray(tasks: List<AgentTask>, onOpen: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(tasks.take(5), key = { it.id }) { t ->
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .clip(shape)
                    .background(W.Deep)
                    .border(1.dp, if (t.status == TaskStatus.WaitingForYou) W.Ember.copy(alpha = 0.6f) else W.Haze, shape)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(t.status)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.widthIn(max = 190.dp)) {
                    Text(t.title, style = T.small.copy(color = W.Bone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when (t.status) {
                        TaskStatus.Running -> t.steps.lastOrNull()?.label ?: "Starting"
                        TaskStatus.WaitingForYou -> "Needs you"
                        TaskStatus.Done -> "Done"
                        TaskStatus.Failed -> "Didn't finish"
                        TaskStatus.Stopped -> "Stopped"
                    }
                    Text(sub, style = T.small.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


@Composable
fun TaskPanel(
    visible: Boolean,
    tasks: List<AgentTask>,
    onStop: (AgentTask) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
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
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .clip(shape)
                    .background(W.Deep)
                    .border(1.dp, W.Haze, shape)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tasks", style = T.title.copy(fontSize = 21.sp), modifier = Modifier.weight(1f))
                    Text("Clear finished", style = T.small.copy(color = W.Bone), modifier = Modifier.clickable(onClick = onClear).padding(8.dp))
                }
                Spacer(Modifier.size(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(tasks, key = { it.id }) { t ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(t.status)
                                Spacer(Modifier.width(10.dp))
                                Text(t.title, style = T.bodyBone, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (t.status == TaskStatus.Running || t.status == TaskStatus.WaitingForYou) {
                                    Text("Stop", style = T.small.copy(color = W.Ember), modifier = Modifier.clickable { onStop(t) }.padding(8.dp))
                                }
                            }
                            t.steps.takeLast(6).forEach { Text(it.label, style = T.small, modifier = Modifier.padding(start = 18.dp)) }
                            if (t.result.isNotBlank() && t.status != TaskStatus.Running) {
                                Text(t.result, style = T.body.copy(color = W.Bone), modifier = Modifier.padding(start = 18.dp, top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
