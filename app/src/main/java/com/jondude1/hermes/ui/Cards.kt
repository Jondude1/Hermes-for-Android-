package com.jondude1.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jondude1.hermes.agent.AgentCard
import com.jondude1.hermes.agent.CardAction

/**
 * Renders an agent-described card. The model picks a kind and fills in text; every pixel of layout
 * and styling comes from here, so a model can never produce a broken or hostile screen.
 */
@Composable
fun CardView(card: AgentCard, onAction: (CardAction) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(W.Deep)
            .border(1.dp, W.Haze, shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (card.title.isNotBlank()) Text(card.title, style = T.title)
        if (card.body.isNotBlank()) Text(card.body, style = T.body)

        when (card.kind) {
            "metric" -> card.items.firstOrNull()?.let { m ->
                Column {
                    Text(m.value.ifBlank { m.label }, style = T.metric)
                    if (m.value.isNotBlank()) Text(m.label, style = T.small)
                    if (m.detail.isNotBlank()) Text(m.detail, style = T.small)
                }
            }

            "list" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                card.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = T.bodyBone)
                            if (item.detail.isNotBlank()) Text(item.detail, style = T.small)
                        }
                        if (item.value.isNotBlank()) {
                            Text(item.value, style = T.bodyBone, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }

            "steps" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                card.items.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (item.done) W.Tide else Color.Transparent)
                                .border(1.5.dp, if (item.done) W.Tide else W.Fog, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (item.done) CheckIcon(W.Void, Modifier.size(14.dp)) }
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(item.label, style = if (item.done) T.body else T.bodyBone)
                            if (item.detail.isNotBlank()) Text(item.detail, style = T.small)
                        }
                    }
                }
            }
        }

        val chosen = card.chosen
        if (card.actions.isNotEmpty()) {
            if (chosen != null) {
                Text(chosen, style = T.small)
            } else if (card.kind == "choice") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.actions.forEach { ActionButton(it, Modifier.fillMaxWidth(), onAction) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.actions.forEach { ActionButton(it, Modifier, onAction) }
                }
            }
        }
    }
}

@Composable
fun ActionButton(action: CardAction, modifier: Modifier, onClick: (CardAction) -> Unit) {
    val (fill, ink) = when (action.style) {
        "primary" -> W.Bone to W.Void
        "danger" -> W.Haze to W.Ember
        else -> W.Haze to W.Bone
    }
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(fill)
            .clickable { onClick(action) }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(action.label, style = T.button.copy(color = ink))
    }
}
