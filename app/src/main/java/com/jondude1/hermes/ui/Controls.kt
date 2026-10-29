package com.jondude1.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small shared controls for the Hub and Settings, in the house style. */

@Composable
fun SectionTitle(title: String) {
    Text(title, style = T.small.copy(fontSize = 14.sp, color = W.Gold), modifier = Modifier.padding(top = 18.dp))
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = W.Bone, onClick: () -> Unit) {
    Text(
        text,
        style = T.button.copy(fontSize = 14.sp, color = color),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(W.Haze)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
fun TextBox(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hide: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Int = 0,
    hint: String = "",
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty()) Text(label, style = T.small)
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(W.Deep)
                .border(1.dp, W.Haze, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .heightIn(min = minHeight.dp),
        ) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = T.body.copy(color = W.Fog.copy(alpha = 0.6f)))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = T.bodyBone,
                cursorBrush = SolidColor(W.Bone),
                visualTransformation = if (hide) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = T.bodyBone)
            if (subtitle.isNotEmpty()) Text(subtitle, style = T.small)
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = W.Void,
                checkedTrackColor = W.Gold,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = W.Fog,
                uncheckedTrackColor = W.Haze,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A tile with a title, body text and optional trailing actions. */
@Composable
fun Tile(title: String, body: String, meta: String = "", actions: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(W.Deep).border(1.dp, W.Haze, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = T.bodyBone)
        if (body.isNotBlank()) Text(body, style = T.body)
        if (meta.isNotBlank()) Text(meta, style = T.small.copy(fontSize = 12.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}
