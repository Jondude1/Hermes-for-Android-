package com.jondude1.hermes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// Small hand-drawn icons so the app needs no icon library.

@Composable
fun MicIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    val sw = w * 0.09f
    drawRoundRect(color, Offset(w * 0.36f, h * 0.08f), Size(w * 0.28f, h * 0.5f), CornerRadius(w * 0.14f), Stroke(sw))
    drawArc(color, 0f, 180f, false, Offset(w * 0.2f, h * 0.3f), Size(w * 0.6f, h * 0.44f), style = Stroke(sw, cap = StrokeCap.Round))
    drawLine(color, Offset(w / 2, h * 0.74f), Offset(w / 2, h * 0.92f), sw, StrokeCap.Round)
}

@Composable
fun SendIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    val sw = w * 0.1f
    drawLine(color, Offset(w / 2, h * 0.82f), Offset(w / 2, h * 0.2f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.26f, h * 0.44f), Offset(w / 2, h * 0.2f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.74f, h * 0.44f), Offset(w / 2, h * 0.2f), sw, StrokeCap.Round)
}

@Composable
fun GridIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) = Canvas(modifier) {
    val r = size.width * 0.075f
    for (row in 0..2) for (col in 0..2) {
        drawCircle(color, r, Offset(size.width * (0.2f + 0.3f * col), size.height * (0.2f + 0.3f * row)))
    }
}

@Composable
fun DotsIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) = Canvas(modifier) {
    val r = size.width * 0.07f
    for (i in 0..2) drawCircle(color, r, Offset(size.width * (0.2f + 0.3f * i), size.height / 2))
}

@Composable
fun CloseIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) = Canvas(modifier) {
    val sw = size.width * 0.09f
    drawLine(color, Offset(size.width * 0.26f, size.height * 0.26f), Offset(size.width * 0.74f, size.height * 0.74f), sw, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.74f, size.height * 0.26f), Offset(size.width * 0.26f, size.height * 0.74f), sw, StrokeCap.Round)
}

@Composable
fun CheckIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) = Canvas(modifier) {
    val sw = size.width * 0.12f
    drawLine(color, Offset(size.width * 0.2f, size.height * 0.54f), Offset(size.width * 0.43f, size.height * 0.76f), sw, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.43f, size.height * 0.76f), Offset(size.width * 0.82f, size.height * 0.28f), sw, StrokeCap.Round)
}
