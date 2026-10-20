package com.mahudesh.tvremote.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.mahudesh.tvremote.data.AppShortcut
import com.mahudesh.tvremote.data.AppShortcuts
import com.mahudesh.tvremote.data.Macro
import com.mahudesh.tvremote.data.SavedTv
import com.mahudesh.tvremote.tv.Keys
import com.mahudesh.tvremote.tv.Link
import com.mahudesh.tvremote.tv.TvStatus
import com.mahudesh.tvremote.ui.components.DPad
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.components.RemoteButton
import com.mahudesh.tvremote.ui.components.StatusPill
import com.mahudesh.tvremote.ui.components.Touchpad
import com.mahudesh.tvremote.ui.components.rememberHaptics
import com.mahudesh.tvremote.ui.theme.Ink
import com.mahudesh.tvremote.ui.theme.Manrope
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Everything the remote screen can ask the TV to do. */
interface RemoteActions : TvSheetActions {
    fun key(code: Int)
    fun openApp(app: AppShortcut)
    fun runMacro(macro: Macro)
    fun syncText(text: String)
    fun submitText(text: String)
    fun keyboardOpened() {}
    fun searchYouTube(query: String) {}
    fun onSettings()
}

private enum class Overlay { None, Keyboard, Tvs }

@Composable
fun RemoteScreen(
    link: Link,
    status: TvStatus,
    pro: Boolean,
    macros: List<Macro>,
    tvs: List<SavedTv>,
    sleepAt: Long?,
    actions: RemoteActions,
) {
    var touchMode by rememberSaveable { mutableStateOf(false) }
    var voiceOn by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    val haze = rememberHazeState()

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        // Laid out inside the safe area: starts just below the status bar (time, battery,
        // network) and ends above the gesture bar on every phone.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .hazeSource(haze)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
        ) {
            val compact = maxHeight < 680.dp
            val tiny = maxHeight < 580.dp || maxWidth < 340.dp
            val key: Dp = if (tiny) 44.dp else if (compact) 50.dp else 56.dp
            val padSize = min(maxWidth * 0.80f, maxHeight * if (compact) 0.34f else 0.39f).coerceIn(176.dp, 330.dp)
            val gap = if (compact) 12.dp else 18.dp

            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(if (compact) 6.dp else 12.dp))

                // Header: TV identity, add TV, settings, power.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { StatusPill(link, pro, onClick = { overlay = Overlay.Tvs }) }
                    Spacer(Modifier.width(10.dp))
                    HeaderChip(Lu.Plus, "Add TV", onClick = actions::addTv)
                    Spacer(Modifier.width(8.dp))
                    HeaderChip(Lu.Settings, "Settings", onClick = actions::onSettings)
                    Spacer(Modifier.width(8.dp))
                    PowerKey(on = link is Link.Connected && status.powered != false) { actions.key(Keys.POWER) }
                }

                Spacer(Modifier.height(gap))

                // Four keys on one baseline. Voice, Touch and Type are toggles: they glow while on,
                // and tapping again undoes them (Back on the TV / back to the D-pad / close the keyboard).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    RemoteButton(
                        Lu.Home, "Home. Long-press for TV settings", { voiceOn = false; actions.key(Keys.HOME) }, size = key,
                        onLongPress = { actions.key(Keys.SETTINGS) },
                    )
                    RemoteButton(
                        Lu.Mic, if (voiceOn) "Close voice search" else "Voice search",
                        {
                            actions.key(if (voiceOn) Keys.BACK else Keys.SEARCH)
                            voiceOn = !voiceOn
                        },
                        size = key, active = voiceOn,
                    )
                    RemoteButton(
                        Lu.Touch, if (touchMode) "Back to D-pad" else "Touchpad",
                        { touchMode = !touchMode }, size = key, active = touchMode,
                    )
                    RemoteButton(
                        Lu.Keyboard, if (overlay == Overlay.Keyboard) "Close keyboard" else "Keyboard",
                        {
                            if (overlay == Overlay.Keyboard) overlay = Overlay.None
                            else { actions.keyboardOpened(); overlay = Overlay.Keyboard }
                        },
                        size = key, active = overlay == Overlay.Keyboard,
                    )
                }

                // D-pad / touchpad gets all the remaining space.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        touchMode,
                        transitionSpec = {
                            (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f)) togetherWith (fadeOut(tween(160)) + scaleOut(targetScale = 0.94f))
                        },
                        label = "pad",
                    ) { touch ->
                        if (touch) Touchpad(actions::key, Modifier.fillMaxWidth().height(padSize * 1.05f))
                        else DPad(
                            padSize, actions::key,
                            volume = status.volume?.let { v -> status.volumeMax?.takeIf { it > 0 }?.let { v / it.toFloat() } },
                            muted = status.muted,
                        )
                    }
                }

                // Control deck: volume and playback, each in one tidy capsule.
                ControlDeck(status, key, actions)

                Spacer(Modifier.height(gap))
                ShortcutTray(macros, actions, compact)
                Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
            }
        }

        // Scrim behind sheets; tap to dismiss.
        AnimatedVisibility(overlay != Overlay.None, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { overlay = Overlay.None },
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.Keyboard,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            KeyboardSheet(
                haze = haze,
                initial = status.textFieldValue,
                onChange = actions::syncText,
                onSubmit = { actions.submitText(it); overlay = Overlay.None },
                onClose = { overlay = Overlay.None },
                onSearchYouTube = { actions.searchYouTube(it); overlay = Overlay.None },
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.Tvs,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TvSheet(haze, link, tvs, sleepAt, object : TvSheetActions by actions {
                override fun close() { overlay = Overlay.None }
                override fun addTv() { overlay = Overlay.None; actions.addTv() }
                override fun switchTo(tv: SavedTv) { overlay = Overlay.None; actions.switchTo(tv) }
            })
        }
    }
}

@Composable
private fun ControlDeck(status: TvStatus, key: Dp, actions: RemoteActions) {
    val capsule = RoundedCornerShape(percent = 50)
    val h = key + 8.dp
    Column(Modifier.fillMaxWidth()) {
        // Back on the left, Mute on the right, just above the volume bar.
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillChip(Lu.Back, "Back") { actions.key(Keys.BACK) }
            Spacer(Modifier.weight(1f))
            MuteChip(status.muted) { actions.key(Keys.MUTE) }
        }
        // Volume: −  level  +   (full width)
        Row(
            Modifier
                .fillMaxWidth()
                .height(h)
                .clip(capsule)
                .background(Ink.surface)
                .border(1.dp, Ink.line.copy(alpha = 0.6f), capsule)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteButton(Lu.Minus, "Volume down", { actions.key(Keys.VOLUME_DOWN) }, size = key, flat = true, repeat = true)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOLUME", style = MaterialTheme.typography.labelSmall, color = Ink.faint)
                val level = when {
                    status.muted -> "Muted"
                    status.volume != null -> status.volume.toString()
                    else -> null
                }
                if (level != null) AnimatedContent(level, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }, label = "lvl") {
                    Text(
                        it, color = if (status.muted) Ink.amber else Ink.text,
                        fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 17.sp,
                    )
                }
            }
            RemoteButton(Lu.Plus, "Volume up", { actions.key(Keys.VOLUME_UP) }, size = key, flat = true, repeat = true)
        }
        Spacer(Modifier.height(18.dp))
        // Playback (full width)
        Row(
            Modifier
                .fillMaxWidth()
                .height(h)
                .clip(capsule)
                .background(Ink.surface)
                .border(1.dp, Ink.line.copy(alpha = 0.6f), capsule)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RemoteButton(Lu.Rewind, "Rewind", { actions.key(Keys.REWIND) }, size = key, flat = true, repeat = true)
            RemoteButton(Lu.Play, "Play or pause", { actions.key(Keys.PLAY_PAUSE) }, size = key, flat = true)
            RemoteButton(Lu.FastForward, "Fast forward", { actions.key(Keys.FAST_FORWARD) }, size = key, flat = true, repeat = true)
        }
    }
}

/** Compact pill with icon + label, same shape as the mute pill. */
@Composable
private fun PillChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .clip(shape)
            .background(Ink.surface)
            .border(1.dp, Ink.line.copy(alpha = 0.6f), shape)
            .clickable(onClickLabel = label) { haptics.tap(); onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Ink.text, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ink.text)
    }
}

/** Compact pill: speaker icon + state, amber when muted. */
@Composable
private fun MuteChip(muted: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val bg by animateColorAsState(if (muted) Ink.amber else Ink.surface, label = "mute")
    val fg = if (muted) Ink.bg else Ink.text
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (muted) Color.Transparent else Ink.line.copy(alpha = 0.6f), shape)
            .clickable(onClickLabel = if (muted) "Unmute" else "Mute") { haptics.tap(); onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (muted) Lu.Mute else Lu.Volume, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(if (muted) "Unmute" else "Mute", style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Power key with a status dot: green = TV on and connected, grey = off / unreachable. */
@Composable
private fun PowerKey(on: Boolean, onClick: () -> Unit) {
    val dot by animateColorAsState(if (on) Ink.green else Ink.faint, label = "power")
    Box {
        RemoteButton(Lu.Power, if (on) "Power. TV is on" else "Power. TV is off or offline", onClick, size = 44.dp, accent = on)
        Box(
            Modifier.align(Alignment.TopEnd).size(12.dp).clip(CircleShape)
                .background(Ink.bg).padding(2.dp).clip(CircleShape).background(dot),
        )
    }
}

@Composable
private fun HeaderChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Ink.surface)
            .border(1.dp, Ink.line.copy(alpha = 0.7f), CircleShape)
            .clickable(onClickLabel = label) { haptics.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Ink.text, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun ShortcutTray(macros: List<Macro>, actions: RemoteActions, compact: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val haptics = rememberHaptics()
    val tile = if (compact) 52.dp else 58.dp
    Column(Modifier.fillMaxWidth()) {
        // Segmented switch.
        Row(
            Modifier
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Ink.surface)
                .padding(3.dp),
        ) {
            listOf("Apps", "Macros").forEachIndexed { i, label ->
                val bg by animateColorAsState(if (tab == i) Ink.raised else Color.Transparent, label = "seg")
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tab == i) Ink.text else Ink.faint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(bg)
                        .clickable { haptics.tick(); tab = i }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
        AnimatedContent(tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tray") { t ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 8.dp)) {
                if (t == 0) items(AppShortcuts, key = { it.name }) { app ->
                    AppTile(app, tile) { haptics.tap(); actions.openApp(app) }
                } else items(macros, key = { it.id }) { m ->
                    Tile(m.name, tile, onClick = { haptics.success(); actions.runMacro(m) }) {
                        Box(Modifier.fillMaxSize().background(Ink.raised), contentAlignment = Alignment.Center) {
                            Text(m.emoji, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: AppShortcut, size: Dp, onClick: () -> Unit) {
    Tile(app.name, size, onClick) {
        Box(Modifier.fillMaxSize().background(app.color), contentAlignment = Alignment.Center) {
            if (app.logo != null) {
                Image(
                    painterResource(app.logo), app.name,
                    colorFilter = app.logoTint?.let { ColorFilter.tint(it) },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(size * app.logoScale).height(size * 0.62f),
                )
            } else {
                Text(
                    app.monogram, color = Color.White, fontFamily = Manrope, fontWeight = FontWeight(800),
                    fontSize = if (app.monogram.length > 2) 13.sp else 18.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Tile(label: String, size: Dp, onClick: () -> Unit, art: @Composable () -> Unit) {
    Column(
        Modifier.width(size + 12.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(size).clip(RoundedCornerShape(18.dp))) { art() }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
