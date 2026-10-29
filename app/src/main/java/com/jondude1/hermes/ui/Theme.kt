package com.jondude1.hermes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design tokens.
 *
 * Void is true black: the Pixel's OLED turns the background off, so the sphere and text float.
 * Deep forest green and brushed gold, Jon's palette. The sphere carries the color; everything else
 * stays quiet, and Ember is reserved for moments that need a human decision.
 */
object W {
    val Void = Color(0xFF000000)
    val Deep = Color(0xFF0A120D)   // panels: black with a breath of forest green
    val Haze = Color(0xFF17251C)   // borders and quiet fills
    val Bone = Color(0xFFEFE9DA)   // primary text, warm white
    val Fog = Color(0xFF8E9C91)    // secondary text
    val Ember = Color(0xFFE8A33D)  // reserved for "this needs you"

    val Gold = Color(0xFFC9A44C)   // brushed gold: the sphere's near side
    val Moss = Color(0xFF2F6B4C)   // forest green: the sphere's far side
    val Tide = Color(0xFF7FC29B)   // running
    val Iris = Gold
    val Orchid = Moss
    val Rose = Color(0xFFE07A6B)   // failed
    val Aurora = listOf(Moss, Gold, Bone, Gold, Moss)
}

object T {
    /** What the agent says. Large and light, set like a quiet line of speech rather than a chat bubble. */
    val speech = TextStyle(color = W.Bone, fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.3).sp)
    val greeting = TextStyle(color = W.Bone, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.9).sp)
    val title = TextStyle(color = W.Bone, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(color = W.Fog, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyBone = TextStyle(color = W.Bone, fontSize = 15.sp, lineHeight = 22.sp)
    val small = TextStyle(color = W.Fog, fontSize = 13.sp, lineHeight = 18.sp)
    val button = TextStyle(color = W.Bone, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val appName = TextStyle(color = W.Bone, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.4).sp)
    val metric = TextStyle(color = W.Bone, fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = W.Void,
            surface = W.Deep,
            onBackground = W.Bone,
            onSurface = W.Bone,
            primary = W.Bone,
            onPrimary = W.Void,
        ),
        content = content,
    )
}
