package com.jondude1.hermes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jondude1.hermes.agent.OrbState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Fibonacci sphere: a few hundred nodes spread evenly over a sphere by the golden angle,
 * turning slowly in space. It shows what Hermes is doing without a word:
 *  - idle: a slow drift and a faint breath
 *  - listening: the shell swells and ripples with your voice
 *  - thinking: it spins up and the nodes link into a lattice
 *  - acting: a bright band sweeps the sphere pole to pole
 *  - speaking: waves roll across the surface in time with the voice
 * Every state change is eased per frame, so it never snaps.
 */
private class Node(val x: Float, val y: Float, val z: Float, val lat: Float, val seed: Float)

private const val COUNT = 380
private val GOLDEN = (PI * (3.0 - sqrt(5.0))).toFloat()

private val NODES: List<Node> = List(COUNT) { i ->
    val y = 1f - (i / (COUNT - 1f)) * 2f
    val r = sqrt(1f - y * y)
    val theta = GOLDEN * i
    Node(cos(theta) * r, y, sin(theta) * r, acos(y.coerceIn(-1f, 1f)) / PI.toFloat(), (i * 0.618f) % 1f)
}

/** Each node linked to its two nearest neighbours. Fibonacci neighbours sit a Fibonacci step apart in index. */
private val LINKS: List<Pair<Int, Int>> = buildList {
    val steps = intArrayOf(1, 2, 3, 5, 8, 13, 21, 34, 55)
    for (i in NODES.indices) {
        val a = NODES[i]
        val near = steps.mapNotNull { s -> (i + s).takeIf { it < COUNT } }
            .map { j -> val b = NODES[j]; j to ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)) }
            .sortedBy { it.second }
            .take(2)
        near.forEach { add(i to it.first) }
    }
}

@Composable
fun Sphere(state: OrbState, level: Float, modifier: Modifier = Modifier) {
    val stateNow by rememberUpdatedState(state)
    val levelNow by rememberUpdatedState(level)

    var spin by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(0.12f) }
    var time by remember { mutableFloatStateOf(0f) }
    var voice by remember { mutableFloatStateOf(0f) }   // listening energy
    var busy by remember { mutableFloatStateOf(0f) }    // thinking lattice
    var sweep by remember { mutableFloatStateOf(0f) }   // acting band strength
    var talk by remember { mutableFloatStateOf(0f) }    // speaking waves

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    val s = stateNow
                    val targetSpeed = when (s) {
                        OrbState.Idle -> 0.12f
                        OrbState.Listening -> 0.3f
                        OrbState.Thinking -> 1.1f
                        OrbState.Acting -> 0.6f
                        OrbState.Speaking -> 0.25f
                    }
                    fun ease(cur: Float, target: Float, rate: Float) = cur + (target - cur) * (dt * rate).coerceAtMost(1f)
                    speed = ease(speed, targetSpeed, 2f)
                    spin = (spin + speed * dt) % (2f * PI.toFloat())
                    time += dt
                    voice = ease(voice, if (s == OrbState.Listening) levelNow else 0f, 10f)
                    busy = ease(busy, if (s == OrbState.Thinking) 1f else if (s == OrbState.Acting) 0.4f else 0f, 3f)
                    sweep = ease(sweep, if (s == OrbState.Acting) 1f else 0f, 4f)
                    talk = ease(talk, if (s == OrbState.Speaking) 1f else 0f, 5f)
                }
                last = now
            }
        }
    }

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = size.minDimension * 0.36f
        val breath = 1f + 0.025f * sin(time * 1.1f) + voice * 0.16f
        val r = baseR * breath
        val tilt = 0.38f
        val cosS = cos(spin); val sinS = sin(spin)
        val cosT = cos(tilt); val sinT = sin(tilt)
        val bandAt = (time * 0.45f) % 1f

        // Soft glow behind everything
        drawCircle(
            brush = Brush.radialGradient(
                listOf(W.Gold.copy(alpha = 0.10f + 0.10f * busy + 0.12f * voice), Color.Transparent),
                Offset(cx, cy), r * 1.5f,
            ),
            radius = r * 1.5f, center = Offset(cx, cy),
        )

        val px = FloatArray(COUNT); val py = FloatArray(COUNT); val pz = FloatArray(COUNT)
        for (i in 0 until COUNT) {
            val n = NODES[i]
            // Surface displacement: voice ripples, speaking waves, a little shimmer while thinking
            val ripple = voice * 0.10f * sin(n.lat * 18f - time * 9f)
            val wave = talk * 0.07f * sin(n.lat * 10f + time * 7f + n.seed * 3f)
            val shimmer = busy * 0.025f * sin(time * 12f + n.seed * 40f)
            val k = 1f + ripple + wave + shimmer
            // Rotate around Y, then tilt around X
            val x1 = n.x * cosS + n.z * sinS
            val z1 = -n.x * sinS + n.z * cosS
            val y2 = n.y * cosT - z1 * sinT
            val z2 = n.y * sinT + z1 * cosT
            val persp = 2.6f / (2.6f + z2 * k)
            px[i] = cx + x1 * k * r * persp
            py[i] = cy + y2 * k * r * persp
            pz[i] = z2
        }

        // The lattice appears while thinking
        if (busy > 0.03f) {
            for ((a, b) in LINKS) {
                val depth = (2f - (pz[a] + pz[b])) / 4f // 0 back .. 1 front
                val alpha = busy * (0.08f + 0.32f * depth)
                drawLine(lerp(W.Moss, W.Gold, depth), Offset(px[a], py[a]), Offset(px[b], py[b]), strokeWidth = 1.1f, alpha = alpha)
            }
        }

        for (i in 0 until COUNT) {
            val n = NODES[i]
            val depth = (1f - pz[i]) / 2f // 0 back .. 1 front
            val inBand = sweep * (1f - (abs(n.lat - bandAt) * 9f).coerceAtMost(1f))
            val twinkle = 0.75f + 0.25f * sin(time * 2f + n.seed * 25f)
            val color = lerp(lerp(W.Moss, W.Gold, depth), W.Bone, inBand * 0.8f)
            val alpha = ((0.18f + 0.82f * depth) * twinkle + inBand * 0.4f).coerceIn(0f, 1f)
            val dot = size.minDimension * (0.0035f + 0.0045f * depth) * (1f + inBand * 0.8f + voice * 0.3f)
            drawCircle(color, radius = dot, center = Offset(px[i], py[i]), alpha = alpha)
        }
    }
}
