package com.mahudesh.tvremote.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.tv.Keys
import com.mahudesh.tvremote.ui.theme.Ink
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Swipe surface: every ~44dp of travel sends one D-pad step (with a haptic tick),
 * a tap sends OK. Feels like the trackpad on a streaming-stick remote.
 */
@Composable
fun Touchpad(onKey: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val fire by rememberUpdatedState(onKey)
    val step = with(LocalDensity.current) { 44.dp.toPx() }
    val tapSlop = with(LocalDensity.current) { 10.dp.toPx() }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val ripple = remember { Animatable(0f) }
    var rippleAt by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(32.dp)

    Box(
        modifier
            .clip(shape)
            .background(Ink.surface)
            .border(1.dp, Ink.line.copy(alpha = 0.7f), shape)
            .semantics { contentDescription = "Touchpad. Swipe to move, tap to select" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touch = down.position
                    var accX = 0f
                    var accY = 0f
                    var travelled = 0f
                    var twoFinger = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) twoFinger = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val d = change.positionChange()
                        change.consume()
                        touch = change.position
                        travelled += abs(d.x) + abs(d.y)
                        accX += d.x; accY += d.y
                        // Commit to the dominant axis so diagonal swipes don't zig-zag.
                        while (abs(accX) >= step && abs(accX) >= abs(accY)) {
                            fire(if (accX > 0) Keys.RIGHT else Keys.LEFT); haptics.tick()
                            accX -= step * kotlin.math.sign(accX); accY = 0f
                        }
                        while (abs(accY) >= step && abs(accY) > abs(accX)) {
                            fire(if (accY > 0) Keys.DOWN else Keys.UP); haptics.tick()
                            accY -= step * kotlin.math.sign(accY); accX = 0f
                        }
                    }
                    if (twoFinger && travelled < tapSlop * 3) {
                        fire(Keys.BACK); haptics.tap()
                    } else if (travelled < tapSlop) {
                        fire(Keys.CENTER); haptics.heavy()
                        rippleAt = down.position
                        scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(420)) }
                    }
                    touch = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Dot grid texture.
            val gap = 22.dp.toPx()
            var y = gap / 2
            while (y < size.height) {
                var x = gap / 2
                while (x < size.width) { drawCircle(Ink.line.copy(alpha = 0.55f), 1.2f, Offset(x, y)); x += gap }
                y += gap
            }
            touch?.let {
                drawCircle(Ink.amber.copy(alpha = 0.10f), 56.dp.toPx(), it)
                drawCircle(Ink.amber.copy(alpha = 0.9f), 7.dp.toPx(), it)
            }
            if (ripple.value in 0.001f..0.999f) {
                drawCircle(Ink.amber.copy(alpha = 0.35f * (1 - ripple.value)), 80.dp.toPx() * ripple.value, rippleAt)
            }
        }
        if (touch == null) {
            Text(
                "Swipe to move · Tap to select · Two-finger tap for Back",
                color = Ink.faint,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}
