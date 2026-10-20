package com.mahudesh.tvremote.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.data.SavedTv
import com.mahudesh.tvremote.tv.Link
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.components.rememberHaptics
import com.mahudesh.tvremote.ui.components.statusColor
import com.mahudesh.tvremote.ui.theme.Ink
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface TvSheetActions {
    suspend fun ping(): Long?
    fun reconnect()
    fun disconnect()
    fun forget(tv: SavedTv)
    fun switchTo(tv: SavedTv)
    fun addTv()
    fun sleepIn(minutes: Int?)
    fun close()
}

private sealed interface PingState {
    data object Idle : PingState
    data object Testing : PingState
    data class Result(val ms: Long?) : PingState
}

/** Everything about "which TV, and how is the link": switch, test, reconnect, disconnect, forget, sleep. */
@Composable
fun TvSheet(
    haze: HazeState,
    link: Link,
    tvs: List<SavedTv>,
    sleepAt: Long?,
    actions: TvSheetActions,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var ping by remember { mutableStateOf<PingState>(PingState.Idle) }
    var confirmForget by remember { mutableStateOf(false) }
    val current = when (link) {
        is Link.Connected -> link.tv
        is Link.Connecting -> link.tv
        is Link.Offline -> link.tv
        Link.NoTv -> null
    }
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .hazeBlur(
                input = HazeInput.Sources(haze),
                style = HazeBlurStyle {
                    blurRadius(28.dp)
                    backgroundColor(Ink.bg)
                    colorEffects(listOf(HazeColorEffect.tint(Ink.surface.copy(alpha = 0.82f))))
                    noiseFactor(0.06f)
                },
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(Ink.line))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (tvs.isEmpty()) "TVs" else "Your TVs", style = MaterialTheme.typography.titleLarge, color = Ink.text, modifier = Modifier.weight(1f))
            RoundIcon(Lu.Close, "Close") { actions.close() }
        }
        Spacer(Modifier.height(14.dp))

        // Current TV card
        if (current != null) {
            val statusColor by animateColorAsState(link.statusColor(), label = "c")
            val cardShape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(Ink.bg.copy(alpha = 0.55f))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(statusColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lu.Tv, null, tint = statusColor, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (link) {
                                is Link.Connected -> "Connected · ${current.lastHost}"
                                is Link.Connecting -> "Connecting…"
                                is Link.Offline -> link.reason
                                Link.NoTv -> ""
                            },
                            style = MaterialTheme.typography.labelMedium, color = Ink.muted,
                        )
                    }
                    SignalBars((ping as? PingState.Result)?.ms, link is Link.Connected)
                }
                Spacer(Modifier.height(14.dp))
                PingRow(ping)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(Lu.Activity, "Test", Modifier.weight(1f)) {
                        haptics.tap()
                        ping = PingState.Testing
                        scope.launch {
                            val ms = actions.ping()
                            ping = PingState.Result(ms)
                            if (ms != null) haptics.success() else haptics.error()
                        }
                    }
                    if (link is Link.Connected) {
                        ActionTile(Lu.Unplug, "Disconnect", Modifier.weight(1f)) { haptics.tap(); actions.disconnect() }
                    } else {
                        ActionTile(Lu.Plug, "Reconnect", Modifier.weight(1f), accent = true) { haptics.tap(); actions.reconnect() }
                    }
                    ActionTile(Lu.Trash, if (confirmForget) "Sure?" else "Forget", Modifier.weight(1f), danger = confirmForget) {
                        if (confirmForget) { haptics.heavy(); actions.forget(current); confirmForget = false }
                        else { haptics.tap(); confirmForget = true }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            SleepTimer(sleepAt, actions::sleepIn)
        }

        // Other TVs
        val others = tvs.filter { it.fingerprint != current?.fingerprint }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("OTHER TVS")
            others.forEach { tv ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { haptics.tap(); actions.switchTo(tv) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lu.Tv, null, tint = Ink.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, style = MaterialTheme.typography.bodyLarge, color = Ink.text)
                        Text(tv.lastHost, style = MaterialTheme.typography.labelMedium, color = Ink.faint)
                    }
                    Text("Switch", style = MaterialTheme.typography.labelLarge, color = Ink.amber)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        // Add TV
        val addShape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(addShape)
                .border(1.dp, Ink.line, addShape)
                .clickable { haptics.tap(); actions.addTv() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Ink.amber), contentAlignment = Alignment.Center) {
                Icon(Lu.Plus, null, tint = Ink.bg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (tvs.isEmpty()) "Add your first TV" else "Add another TV", style = MaterialTheme.typography.titleMedium, color = Ink.text)
                Text(
                    if (tvs.isEmpty()) "Finds Android TVs on your Wi-Fi or hotspot" else "Home, a friend's place — switch anytime",
                    style = MaterialTheme.typography.labelMedium, color = Ink.muted,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PingRow(ping: PingState) {
    AnimatedContent(ping, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "ping") { p ->
        val (text, color) = when (p) {
            PingState.Idle -> "Run a quick test to check the link to your TV." to Ink.muted
            PingState.Testing -> "Pinging TV…" to Ink.amber
            is PingState.Result -> when {
                p.ms == null -> "No reply. Check the TV is on and on the same Wi-Fi." to Ink.red
                p.ms < 30 -> "Excellent · ${p.ms} ms" to Ink.green
                p.ms < 90 -> "Good · ${p.ms} ms" to Ink.green
                else -> "Slow · ${p.ms} ms. Buttons may lag; move closer to the router." to Ink.amber
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Four rising bars; how many light up depends on latency. */
@Composable
private fun SignalBars(ms: Long?, connected: Boolean) {
    val level = when {
        ms == null -> if (connected) 3 else 0
        ms < 30 -> 4
        ms < 90 -> 3
        ms < 200 -> 2
        else -> 1
    }
    val on = if (level >= 3) Ink.green else if (level == 2) Ink.amber else Ink.red
    Canvas(Modifier.size(24.dp, 18.dp)) {
        val w = size.width / 4f
        repeat(4) { i ->
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                if (i < level) on else Ink.line,
                topLeft = Offset(i * w + w * 0.15f, size.height - h),
                size = Size(w * 0.7f, h),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun SleepTimer(sleepAt: Long?, onSet: (Int?) -> Unit) {
    val haptics = rememberHaptics()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAt) { while (sleepAt != null) { now = System.currentTimeMillis(); delay(1000) } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Lu.Moon, null, tint = Ink.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        SectionLabel(
            if (sleepAt == null) "SLEEP TIMER"
            else "TV OFF IN " + ((sleepAt - now).coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60, 90).forEach { m ->
            Pill("${m}m", Modifier.weight(1f)) { haptics.tap(); onSet(m) }
        }
        if (sleepAt != null) Pill("Off", Modifier.weight(1f), accent = true) { haptics.tap(); onSet(null) }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = Ink.faint, modifier = Modifier.padding(vertical = 8.dp))

@Composable
private fun Pill(text: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (accent) Ink.amber else Ink.bg.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (accent) Ink.bg else Ink.text) }
}

@Composable
private fun ActionTile(
    icon: ImageVector, label: String, modifier: Modifier,
    accent: Boolean = false, danger: Boolean = false, onClick: () -> Unit,
) {
    val bg = when { accent -> Ink.amber; danger -> Ink.red.copy(alpha = 0.18f); else -> Ink.raised }
    val fg = when { accent -> Ink.bg; danger -> Ink.red; else -> Ink.text }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
internal fun RoundIcon(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (accent) Ink.amber else Ink.raised)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (accent) Ink.bg else Ink.text, modifier = Modifier.size(18.dp)) }
}
