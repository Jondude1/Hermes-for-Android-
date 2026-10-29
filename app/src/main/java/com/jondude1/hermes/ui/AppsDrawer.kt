package com.jondude1.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude1.hermes.agent.AgentController
import com.jondude1.hermes.agent.AppEntry
import com.jondude1.hermes.agent.InstalledApps
import kotlinx.coroutines.launch

private fun letterOf(app: AppEntry): Char =
    app.label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'

/**
 * The escape hatch to your plain app list: text only, large and light, with an alphabet rail on
 * the right edge for one-handed jumping. If what you type matches nothing, the last row hands the
 * words to the agent instead.
 */
@Composable
fun AppsDrawer(c: AgentController, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { InstalledApps.list(ctx) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .background(W.Void.copy(alpha = 0.97f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 26.dp, end = 40.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", style = T.body.copy(fontSize = 18.sp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = T.bodyBone.copy(fontSize = 18.sp),
                        cursorBrush = SolidColor(W.Bone),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            filtered.firstOrNull()?.let { InstalledApps.launch(ctx, it.pkg); onClose() }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    CloseIcon(W.Fog)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.pkg }) { app ->
                    Text(
                        app.label,
                        style = T.appName,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (InstalledApps.launch(ctx, app.pkg)) onClose() }
                            .padding(vertical = 9.dp),
                    )
                }
                if (filtered.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            "Ask Hermes: ${query.trim()}",
                            style = T.appName.copy(color = W.Tide),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { c.submit(query); onClose() }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }

        // Alphabet rail
        val letters = remember(filtered) { filtered.map(::letterOf).distinct() }
        if (query.isBlank() && letters.size > 1) {
            var railHeight by remember { mutableIntStateOf(1) }
            fun jump(y: Float) {
                val i = ((y / railHeight) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                val idx = filtered.indexOfFirst { letterOf(it) == letters[i] }
                if (idx >= 0) scope.launch { listState.scrollToItem(idx) }
            }
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = 6.dp)
                    .onSizeChanged { railHeight = it.height.coerceAtLeast(1) }
                    .pointerInput(letters) { detectTapGestures { jump(it.y) } }
                    .pointerInput(letters) {
                        detectVerticalDragGestures(onDragStart = { jump(it.y) }) { change, _ -> jump(change.position.y) }
                    }
                    .padding(horizontal = 8.dp, vertical = 70.dp),
            ) {
                letters.forEach { Text(it.toString(), style = T.small.copy(fontSize = 11.sp, lineHeight = 15.sp)) }
            }
        }
    }
}
