package com.mahudesh.tvremote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.ExperimentalFoundationStyleApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mahudesh.tvremote.data.AppShortcuts
import com.mahudesh.tvremote.data.Macro
import com.mahudesh.tvremote.data.MacroStep
import com.mahudesh.tvremote.data.SavedTv
import com.mahudesh.tvremote.tv.Keys
import com.mahudesh.tvremote.tv.ProState
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.components.rememberHaptics
import com.mahudesh.tvremote.ui.theme.Ink
import io.github.ronjunevaldoz.shadcncompose.components.ShadcnSwitch
import java.util.UUID

interface SettingsActions {
    fun back()
    fun addTv()
    fun rename(tv: SavedTv, name: String)
    fun forget(tv: SavedTv)
    fun setPro(on: Boolean)
    fun retryPro()
    fun openProTools()
    fun pairWireless(pairingPort: Int, code: String, connectPort: Int)
    fun setHaptics(on: Boolean)
    fun saveMacros(list: List<Macro>)
}

@OptIn(ExperimentalFoundationStyleApi::class)
@Composable
fun SettingsScreen(
    tvs: List<SavedTv>,
    activeFingerprint: String?,
    proOn: Boolean,
    pro: ProState,
    haptics: Boolean,
    macros: List<Macro>,
    actions: SettingsActions,
) {
    var editing by remember { mutableStateOf<Macro?>(null) }
    BackHandler { if (editing != null) editing = null else actions.back() }
    val h = rememberHaptics()

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Lu.NavBack, "Back") { actions.back() }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Ink.text)
                    Text("Version ${com.mahudesh.tvremote.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = Ink.faint)
                }
            }
            Spacer(Modifier.height(22.dp))

            // ---------------- TVs
            Section("TVS") {
                tvs.forEach { tv -> TvSettingRow(tv, tv.fingerprint == activeFingerprint, actions) }
                ListRow(Lu.Plus, "Add a TV", "Pair a new Android TV", accent = true) { h.tap(); actions.addTv() }
            }

            // ---------------- Pro mode
            Section("PRO MODE") {
                val connected = pro is ProState.Connected
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Lu.Zap, if (connected) Ink.green else Ink.amber)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pro mode", style = MaterialTheme.typography.titleMedium, color = Ink.text)
                        Text(
                            when {
                                !proOn -> "Live TV screen, all apps, real volume"
                                pro is ProState.Connecting -> "Connecting… check the TV for \"Allow debugging?\""
                                connected -> "Connected"
                                else -> "Not connected"
                            },
                            style = MaterialTheme.typography.labelMedium, color = if (connected) Ink.green else Ink.muted,
                        )
                    }
                    ShadcnSwitch(checked = proOn, onCheckedChange = { h.tap(); actions.setPro(it) })
                }
                AnimatedVisibility(proOn && connected) {
                    Box(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)).background(Ink.amber)
                            .clickable { h.tap(); actions.openProTools() }.padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Open Pro tools", style = MaterialTheme.typography.labelLarge, color = Ink.bg) }
                }
                AnimatedVisibility(proOn && !connected) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (pro as? ProState.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = Ink.amber) }
                        Text("ON THE TV, ONCE", style = MaterialTheme.typography.labelSmall, color = Ink.faint)
                        listOf(
                            "Settings → About (Device preferences → About) → click Build 7 times",
                            "Developer options → turn on USB / ADB debugging (and Network debugging if listed)",
                            "Come back here and tap Try again; accept \"Allow debugging?\" on the TV with Always allow",
                        ).forEachIndexed { i, step ->
                            Row {
                                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Ink.amber, modifier = Modifier.width(18.dp))
                                Text(step, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                            }
                        }
                        RetryButton(connecting = pro is ProState.Connecting) { h.tap(); actions.retryPro() }
                        WirelessPairing(onPair = actions::pairWireless)
                        Text("The normal remote keeps working without Pro mode.", style = MaterialTheme.typography.labelMedium, color = Ink.faint)
                    }
                }
            }

            // ---------------- Macros
            Section("MACROS") {
                macros.forEach { m ->
                    ListRow(null, m.name, "${m.steps.size} steps", emoji = m.emoji) { h.tap(); editing = m }
                }
                ListRow(Lu.Plus, "New macro", "Chain buttons, apps and waits", accent = true) {
                    h.tap(); editing = Macro(UUID.randomUUID().toString(), "", "✨", emptyList())
                }
            }

            // ---------------- Feel
            Section("FEEL") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Lu.Touch, Ink.muted)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Vibration", style = MaterialTheme.typography.titleMedium, color = Ink.text)
                        Text("Buzz on presses, ticks on the touchpad", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    }
                    ShadcnSwitch(checked = haptics, onCheckedChange = { actions.setHaptics(it) })
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // Macro editor sheet
        AnimatedVisibility(editing != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .clickable(remember { MutableInteractionSource() }, null) { editing = null },
            )
        }
        AnimatedVisibility(
            editing != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            editing?.let { m ->
                MacroEditor(
                    m,
                    isNew = macros.none { it.id == m.id },
                    onSave = { saved -> actions.saveMacros(macros.filterNot { it.id == saved.id } + saved); editing = null },
                    onDelete = { actions.saveMacros(macros.filterNot { it.id == m.id }); editing = null },
                    onClose = { editing = null },
                )
            }
        }
    }
}

/** Newer TVs (Android 11+) only offer "Wireless debugging", which pairs with a code. */
@Composable
private fun WirelessPairing(onPair: (Int, String, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    val h = rememberHaptics()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.bg).padding(14.dp).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("TV only has Wireless debugging?", style = MaterialTheme.typography.titleMedium, color = Ink.text, modifier = Modifier.weight(1f))
            Icon(Lu.ChevronDown, null, tint = Ink.muted, modifier = Modifier.size(16.dp))
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(
                "On the TV: Developer options → Wireless debugging → Pair device with pairing code. Enter what it shows. " +
                    "The connect port is on the Wireless debugging screen (IP address & Port).",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(code, { code = it.filter(Char::isDigit).take(6) }, "Code", Modifier.weight(1.2f))
                NumField(pairPort, { pairPort = it.filter(Char::isDigit).take(5) }, "Pair port", Modifier.weight(1f))
                NumField(connectPort, { connectPort = it.filter(Char::isDigit).take(5) }, "Connect port", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            val ok = code.length == 6 && pairPort.isNotEmpty() && connectPort.isNotEmpty()
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (ok) Ink.amber else Ink.raised)
                    .clickable(enabled = ok) { h.success(); onPair(pairPort.toInt(), code, connectPort.toInt()) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Pair & connect", style = MaterialTheme.typography.labelLarge, color = if (ok) Ink.bg else Ink.faint) }
        }
    }
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(Ink.surface).border(1.dp, Ink.line, shape).padding(horizontal = 10.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.labelMedium, color = Ink.faint, maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.text), cursorBrush = SolidColor(Ink.amber),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TvSettingRow(tv: SavedTv, active: Boolean, actions: SettingsActions) {
    var renaming by remember { mutableStateOf(false) }
    var name by remember(tv.name) { mutableStateOf(tv.name) }
    var confirm by remember { mutableStateOf(false) }
    val h = rememberHaptics()
    Column(Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Lu.Tv, if (active) Ink.amber else Ink.muted)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (renaming) {
                    BasicTextField(
                        name, { name = it.take(32) }, singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = Ink.text),
                        cursorBrush = SolidColor(Ink.amber),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(tv.name, style = MaterialTheme.typography.titleMedium, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (active) "${tv.lastHost} · in use" else tv.lastHost, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            }
            if (renaming) {
                SmallAction("Save") { h.success(); actions.rename(tv, name); renaming = false }
            } else {
                SmallAction("Rename") { h.tap(); renaming = true }
                Spacer(Modifier.width(6.dp))
                SmallAction(if (confirm) "Sure?" else "Forget", danger = true) {
                    if (confirm) { h.heavy(); actions.forget(tv) } else { h.tap(); confirm = true }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Macro editor

private data class StepOption(val label: String, val step: MacroStep)

@Composable
private fun MacroEditor(macro: Macro, isNew: Boolean, onSave: (Macro) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(macro.name) }
    var emoji by remember { mutableStateOf(macro.emoji) }
    val steps = remember { mutableStateListOf<MacroStep>().apply { addAll(macro.steps) } }
    var text by remember { mutableStateOf("") }
    val h = rememberHaptics()
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    val keyOptions = listOf(
        StepOption("Home", MacroStep.Key(Keys.HOME)), StepOption("Back", MacroStep.Key(Keys.BACK)),
        StepOption("OK", MacroStep.Key(Keys.CENTER)), StepOption("Up", MacroStep.Key(Keys.UP)),
        StepOption("Down", MacroStep.Key(Keys.DOWN)), StepOption("Left", MacroStep.Key(Keys.LEFT)),
        StepOption("Right", MacroStep.Key(Keys.RIGHT)), StepOption("Vol +", MacroStep.Key(Keys.VOLUME_UP)),
        StepOption("Vol −", MacroStep.Key(Keys.VOLUME_DOWN)), StepOption("Mute", MacroStep.Key(Keys.MUTE)),
        StepOption("Play/Pause", MacroStep.Key(Keys.PLAY_PAUSE)), StepOption("Power", MacroStep.Key(Keys.POWER)),
    )
    val appOptions = AppShortcuts.map { StepOption("Open ${it.name}", MacroStep.Link(it.link)) }
    val waitOptions = listOf(500L, 1000L, 2000L, 3000L).map { StepOption("Wait ${it / 1000.0}s".replace(".0s", "s"), MacroStep.Wait(it)) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (isNew) "New macro" else "Edit macro", style = MaterialTheme.typography.titleLarge, color = Ink.text, modifier = Modifier.weight(1f))
            RoundIcon(Lu.Close, "Close", onClick = onClose)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(emoji, { emoji = it.take(2) }, "✨", Modifier.width(64.dp), big = true)
            Spacer(Modifier.width(10.dp))
            Field(name, { name = it.take(24) }, "Name, e.g. Movie night", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

        Text("STEPS", style = MaterialTheme.typography.labelSmall, color = Ink.faint)
        Spacer(Modifier.height(8.dp))
        if (steps.isEmpty()) {
            Text("Tap the chips below to add steps. They run in order.", style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(steps.size) { i ->
                    val label = describe(steps[i], keyOptions + appOptions + waitOptions)
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Ink.raised)
                            .clickable { h.tick(); steps.removeAt(i) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}. $label", style = MaterialTheme.typography.labelMedium, color = Ink.text)
                        Spacer(Modifier.width(6.dp))
                        Icon(Lu.Close, "Remove", tint = Ink.faint, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        ChipRow("Buttons", keyOptions) { h.tap(); steps += it }
        ChipRow("Apps", appOptions) { h.tap(); steps += it }
        ChipRow("Wait", waitOptions) { h.tap(); steps += it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(text, { text = it }, "Type text…", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SmallAction("Add") { if (text.isNotBlank()) { h.tap(); steps += MacroStep.Text(text); text = "" } }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Ink.red.copy(alpha = 0.15f))
                        .clickable { h.heavy(); onDelete() }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Delete", style = MaterialTheme.typography.labelLarge, color = Ink.red) }
            }
            val canSave = name.isNotBlank() && steps.isNotEmpty()
            Box(
                Modifier.weight(2f).clip(RoundedCornerShape(16.dp)).background(if (canSave) Ink.amber else Ink.raised)
                    .clickable(enabled = canSave) { h.success(); onSave(macro.copy(name = name.trim(), emoji = emoji.ifBlank { "✨" }, steps = steps.toList())) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Save macro", style = MaterialTheme.typography.labelLarge, color = if (canSave) Ink.bg else Ink.faint) }
        }
    }
}

private fun describe(step: MacroStep, options: List<StepOption>): String =
    options.firstOrNull { it.step == step }?.label ?: when (step) {
        is MacroStep.Text -> "Type \"${step.text.take(12)}\""
        is MacroStep.Wait -> "Wait ${step.millis}ms"
        is MacroStep.Key -> "Key ${step.code}"
        is MacroStep.Link -> "Open link"
    }

@Composable
private fun ChipRow(title: String, options: List<StepOption>, onPick: (MacroStep) -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.faint)
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { o ->
            Text(
                o.label, style = MaterialTheme.typography.labelMedium, color = Ink.text,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Ink.line, RoundedCornerShape(12.dp))
                    .clickable { onPick(o.step) }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

// ---------------------------------------------------------------- Building blocks

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = Ink.faint, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ink.surface), content = content)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun ListRow(icon: ImageVector?, title: String, subtitle: String, accent: Boolean = false, emoji: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (emoji != null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ink.raised), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 18.sp) }
        } else if (icon != null) IconBadge(icon, if (accent) Ink.amber else Ink.muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (accent) Ink.amber else Ink.text)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Full-width retry; spins and says "Reconnecting…" while an attempt is running. */
@Composable
private fun RetryButton(connecting: Boolean, onClick: () -> Unit) {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (connecting) Ink.raised else Ink.amber)
            .clickable(enabled = !connecting, onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lu.Refresh, null, tint = if (connecting) Ink.amber else Ink.bg,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (connecting) angle else 0f },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (connecting) "Reconnecting… accept on the TV" else "Try again",
            style = MaterialTheme.typography.labelLarge, color = if (connecting) Ink.amber else Ink.bg,
        )
    }
}

@Composable
private fun SmallAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (danger) Ink.red else Ink.amber,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier, big: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    val style: TextStyle = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
    Box(modifier.clip(shape).background(Ink.bg).border(1.dp, Ink.line, shape).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(placeholder, style = style, color = Ink.faint, maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = style.copy(color = Ink.text), cursorBrush = SolidColor(Ink.amber),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
