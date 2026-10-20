package com.mahudesh.tvremote.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.tv.Keys
import com.mahudesh.tvremote.ui.theme.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

private enum class Zone(val key: Int, val angle: Float) {
    UP(Keys.UP, 270f), RIGHT(Keys.RIGHT, 0f), DOWN(Keys.DOWN, 90f), LEFT(Keys.LEFT, 180f), OK(Keys.CENTER, 0f)
}

/**
 * Custom-drawn circular D-pad: one continuous disc, four wedge zones and a centre OK.
 * The pressed wedge lights up with a soft amber sweep; holding a direction auto-repeats.
 */
@Composable
fun DPad(size: Dp, onKey: (Int) -> Unit, modifier: Modifier = Modifier, volume: Float? = null, muted: Boolean = false) {
    val haptics = rememberHaptics()
    val fire by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf<Zone?>(null) }
    val okScale by animateFloatAsState(
        if (pressed == Zone.OK) 0.92f else 1f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "ok",
    )
    val glow = remember { Animatable(0f) }
    val vol by animateFloatAsState(volume ?: 0f, tween(260), label = "vol")
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .size(size)
            .semantics { contentDescription = "Direction pad" }
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        val d = down.position - c
                        val r = hypot(d.x, d.y)
                        val outer = this.size.width / 2f
                        if (r > outer) return@awaitEachGesture
                        val zone = if (r < outer * 0.36f) Zone.OK else {
                            val deg = (Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat() + 360f) % 360f
                            when {
                                deg >= 315 || deg < 45 -> Zone.RIGHT
                                deg < 135 -> Zone.DOWN
                                deg < 225 -> Zone.LEFT
                                else -> Zone.UP
                            }
                        }
                        pressed = zone
                        if (zone == Zone.OK) haptics.heavy() else haptics.tap()
                        fire(zone.key)
                        scope.launch { glow.snapTo(1f) }
                        var job: Job? = null
                        if (zone != Zone.OK) job = launch {
                            delay(420)
                            while (true) { haptics.tick(); fire(zone.key); delay(110) }
                        }
                        waitForUpOrCancellation()
                        job?.cancel()
                        pressed = null
                        scope.launch { glow.animateTo(0f, tween(260)) }
                    }
                }
            },
    ) {
        val last = remember { mutableStateOf(Zone.UP) }
        pressed?.takeIf { it != Zone.OK }?.let { last.value = it }
        Canvas(Modifier.size(size)) {
            val radius = this.size.minDimension / 2f
            val c = center
            // Disc with a subtle top-lit gradient for depth.
            drawCircle(Brush.verticalGradient(listOf(Ink.raised, Ink.surface)), radius, c)
            drawCircle(Ink.line.copy(alpha = 0.7f), radius - 0.5f, c, style = Stroke(1.5f))

            // Live TV volume as a thin ring hugging the rim (only when the TV reports it).
            if (volume != null) {
                val inset = 7.dp.toPx()
                val ringSize = Size((radius - inset) * 2, (radius - inset) * 2)
                val tl = Offset(c.x - radius + inset, c.y - radius + inset)
                drawArc(Ink.line.copy(alpha = 0.5f), 135f, 270f, false, tl, ringSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                if (vol > 0f) drawArc(
                    if (muted) Ink.faint else Ink.amber.copy(alpha = 0.85f), 135f, 270f * vol, false, tl, ringSize,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Pressed wedge highlight.
            if (glow.value > 0f) {
                val z = last.value
                drawArc(
                    brush = Brush.radialGradient(
                        listOf(Ink.amber.copy(alpha = 0.28f * glow.value), Ink.amber.copy(alpha = 0f)),
                        center = c, radius = radius,
                    ),
                    startAngle = z.angle - 45f, sweepAngle = 90f, useCenter = true,
                    topLeft = Offset(c.x - radius, c.y - radius), size = Size(radius * 2, radius * 2),
                )
            }

            // Direction chevrons.
            Zone.entries.filter { it != Zone.OK }.forEach { z ->
                val active = pressed == z
                rotate(z.angle + 90f, c) {
                    chevron(Offset(c.x, c.y - radius * 0.70f), radius * 0.05f, if (active) Ink.amber else Ink.muted)
                }
            }

            // OK button.
            val okR = radius * 0.34f * okScale
            drawCircle(if (pressed == Zone.OK) Ink.line else Ink.bg, okR, c)
            drawCircle(Ink.line, okR, c, style = Stroke(1.5f))
            drawCircle(if (pressed == Zone.OK) Ink.amber else Ink.amber.copy(alpha = 0.85f), okR * 0.14f, c)
        }
    }
}

private fun DrawScope.chevron(at: Offset, s: Float, color: androidx.compose.ui.graphics.Color) {
    val p = Path().apply {
        moveTo(at.x - s * 1.6f, at.y + s * 0.8f)
        lineTo(at.x, at.y - s * 0.8f)
        lineTo(at.x + s * 1.6f, at.y + s * 0.8f)
    }
    drawPath(p, color, style = Stroke(width = s * 0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
