package com.mahudesh.tvremote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.ui.theme.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Press detection shared by every remote key: fires on press-down (feels instant, like a
 * physical remote), and optionally auto-repeats while held for D-pad / volume.
 */
fun Modifier.remoteKey(
    repeat: Boolean,
    onPressedChange: (Boolean) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onFire: (repeated: Boolean) -> Unit,
): Modifier = this.pointerInput(repeat, onLongPress != null) {
    kotlinx.coroutines.coroutineScope {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onPressedChange(true)
            if (onLongPress != null) {
                // Keys with a long-press action fire on release instead, so the two don't both trigger.
                val up = withTimeoutOrNull(450) { waitForUpOrCancellation() }
                if (up != null) onFire(false) else { onLongPress(); waitForUpOrCancellation() }
                onPressedChange(false)
                return@awaitEachGesture
            }
            onFire(false)
            var job: Job? = null
            if (repeat) {
                job = launch {
                    delay(420)
                    while (true) {
                        onFire(true)
                        delay(110)
                    }
                }
            }
            waitForUpOrCancellation()
            job?.cancel()
            onPressedChange(false)
        }
    }
}

/** Round or pill key with spring press animation and haptics. */
@Composable
fun RemoteButton(
    icon: ImageVector,
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    shape: Shape = CircleShape,
    accent: Boolean = false,
    repeat: Boolean = false,
    caption: String? = null,
    onLongPress: (() -> Unit)? = null,
    flat: Boolean = false,
    /** Toggle buttons (voice, touchpad, keyboard) glow amber while their mode is on. */
    active: Boolean = false,
) {
    val haptics = rememberHaptics()
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "scale")
    val bg by animateColorAsState(
        when {
            accent && pressed -> Ink.amber.copy(alpha = 0.8f)
            accent -> Ink.amber
            active && pressed -> Ink.amber.copy(alpha = 0.35f)
            active -> Ink.amberDim
            pressed -> Ink.line
            flat -> Color.Transparent
            else -> Ink.raised
        },
        label = "bg",
    )
    val fire by rememberUpdatedState(onPress)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(bg)
                .border(1.dp, if (active) Ink.amber.copy(alpha = 0.7f) else if (accent || flat) Color.Transparent else Ink.line.copy(alpha = 0.6f), shape)
                .semantics { contentDescription = label; role = Role.Button }
                .remoteKey(
                    repeat = repeat,
                    onPressedChange = { pressed = it },
                    onLongPress = onLongPress?.let { long -> { haptics.heavy(); long() } },
                    onFire = { repeated -> if (repeated) haptics.tick() else haptics.tap(); fire() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (accent) Ink.bg else if (active) Ink.amber else Ink.text, modifier = Modifier.size(size * 0.38f))
        }
        if (caption != null) {
            Text(caption, color = Ink.muted, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        }
    }
}

/** Vertical rocker: + on top, − at the bottom, label in the middle. Hold to repeat. */
@Composable
fun Rocker(
    label: String,
    topIcon: ImageVector,
    bottomIcon: ImageVector,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 60.dp,
    height: Dp = 132.dp,
) {
    val haptics = rememberHaptics()
    val top by rememberUpdatedState(onTop)
    val bottom by rememberUpdatedState(onBottom)
    var topPressed by remember { mutableStateOf(false) }
    var bottomPressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)

    Column(
        modifier
            .size(width, height)
            .clip(shape)
            .background(Ink.raised)
            .border(1.dp, Ink.line.copy(alpha = 0.6f), shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (topPressed) Ink.line else Color.Transparent)
                .semantics { contentDescription = "$label up"; role = Role.Button }
                .remoteKey(true, { topPressed = it }) { r -> if (r) haptics.tick() else haptics.tap(); top() },
            contentAlignment = Alignment.Center,
        ) { Icon(topIcon, null, tint = Ink.text, modifier = Modifier.size(20.dp)) }
        Text(label, color = Ink.faint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (bottomPressed) Ink.line else Color.Transparent)
                .semantics { contentDescription = "$label down"; role = Role.Button }
                .remoteKey(true, { bottomPressed = it }) { r -> if (r) haptics.tick() else haptics.tap(); bottom() },
            contentAlignment = Alignment.Center,
        ) { Icon(bottomIcon, null, tint = Ink.text, modifier = Modifier.size(20.dp)) }
    }
}
