package com.mahudesh.tvremote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.mahudesh.tvremote.R
import com.mahudesh.tvremote.tv.FoundTv
import com.mahudesh.tvremote.tv.Pairing
import com.mahudesh.tvremote.tv.TvDiscovery
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.components.rememberHaptics
import com.mahudesh.tvremote.ui.theme.Ink
import com.mahudesh.tvremote.ui.theme.Manrope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

interface AddTvActions {
    fun pair(tv: FoundTv)
    fun submitCode(code: String)
    fun cancel()
    fun done()
}

/** Find a TV on the network, then pair it with the code it shows. One screen, four moods. */
@Composable
fun AddTvScreen(discovery: TvDiscovery, pairing: Pairing, pairedHosts: Set<String>, actions: AddTvActions) {
    BackHandler { actions.cancel() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Lu.NavBack, "Back") { actions.cancel() }
            Spacer(Modifier.width(14.dp))
            Text(
                when (pairing) {
                    is Pairing.EnterCode, is Pairing.Verifying -> "Enter the code"
                    is Pairing.Done -> "Paired"
                    else -> "Add a TV"
                },
                style = MaterialTheme.typography.headlineSmall, color = Ink.text,
            )
        }
        AnimatedContent(
            targetState = when (pairing) {
                Pairing.Idle -> 0
                is Pairing.Requesting -> 1
                is Pairing.EnterCode, is Pairing.Verifying -> 2
                is Pairing.Done -> 3
                is Pairing.Failed -> 4
            },
            transitionSpec = {
                (fadeIn(tween(240)) + slideInHorizontally { it / 6 }) togetherWith (fadeOut(tween(160)) + slideOutHorizontally { -it / 6 })
            },
            modifier = Modifier.weight(1f),
            label = "flow",
        ) { step ->
            when (step) {
                0 -> Discover(discovery, pairedHosts, actions)
                1 -> Waiting((pairing as? Pairing.Requesting)?.tv?.name ?: "TV")
                2 -> CodeEntry(pairing, actions)
                3 -> Success((pairing as? Pairing.Done)?.tv?.name ?: "TV", actions)
                else -> Failed((pairing as? Pairing.Failed)?.message ?: "Something went wrong", actions)
            }
        }
    }
}

// ---------------------------------------------------------------- Discover

@Composable
private fun Discover(discovery: TvDiscovery, pairedHosts: Set<String>, actions: AddTvActions) {
    val found = remember { mutableStateListOf<FoundTv>() }
    var sweeping by remember { mutableStateOf(true) }
    var round by remember { mutableStateOf(0) }
    val haptics = rememberHaptics()

    fun add(tv: FoundTv) {
        val i = found.indexOfFirst { it.host == tv.host }
        when {
            i < 0 -> { found += tv; haptics.tick() }
            // mDNS names beat the sweep's generic one.
            found[i].name == FoundTv.UNNAMED && tv.name != FoundTv.UNNAMED -> found[i] = tv
        }
    }
    LaunchedEffect(round) { discovery.mdns().collect { add(it) } }
    LaunchedEffect(round) {
        sweeping = true
        discovery.sweep().forEach { add(it) }
        sweeping = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { Radar(active = sweeping || found.isEmpty()) }
        Text(
            when {
                found.isNotEmpty() -> "Found ${found.size} TV${if (found.size > 1) "s" else ""}"
                sweeping -> "Looking for TVs…"
                else -> "No TVs found yet"
            },
            style = MaterialTheme.typography.titleLarge, color = Ink.text,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Your phone and TV need to be on the same Wi-Fi, or the TV connected to this phone's hotspot.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(20.dp))

        found.forEachIndexed { i, tv ->
            AnimatedVisibility(true, enter = fadeIn() + expandVertically() + slideInVertically { it / 2 }) {
                TvRow(tv, paired = tv.host in pairedHosts) { haptics.tap(); actions.pair(tv) }
            }
            if (i < found.lastIndex) Spacer(Modifier.height(10.dp))
        }

        if (!sweeping && found.isEmpty()) {
            Tips()
            Spacer(Modifier.height(12.dp))
            TextButton("Search again") { haptics.tap(); found.clear(); round++ }
        }

        Spacer(Modifier.height(24.dp))
        ManualIp { ip -> actions.pair(FoundTv(FoundTv.UNNAMED, ip)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Radar(active: Boolean) {
    val t by rememberInfiniteTransition(label = "radar").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(210.dp)) {
            val max = size.minDimension / 2
            // Static guide rings.
            listOf(0.38f, 0.66f, 0.94f).forEach { drawCircle(Ink.line.copy(alpha = 0.5f), max * it, style = Stroke(1.dp.toPx())) }
            if (active) repeat(3) { i ->
                val p = (t + i / 3f) % 1f
                drawCircle(Ink.amber.copy(alpha = 0.45f * (1 - p)), max * (0.22f + 0.78f * p), style = Stroke(2.dp.toPx()))
            }
        }
        Box(Modifier.size(64.dp).clip(CircleShape).background(Ink.amber), contentAlignment = Alignment.Center) {
            Icon(Lu.Tv, null, tint = Ink.bg, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun TvRow(tv: FoundTv, paired: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Ink.raised), contentAlignment = Alignment.Center) {
            Icon(Lu.Tv, null, tint = Ink.amber, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tv.name, style = MaterialTheme.typography.titleMedium, color = Ink.text)
            Text(if (paired) "${tv.host} · already paired" else tv.host, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        }
        Text(if (paired) "Re-pair" else "Pair", style = MaterialTheme.typography.labelLarge, color = Ink.amber)
    }
}

@Composable
private fun Tips() {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ink.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Quick checks", style = MaterialTheme.typography.titleMedium, color = Ink.text)
        listOf(
            "TV is switched on (not just standby on some models)",
            "Phone isn't on mobile data only — Wi-Fi on, or hotspot on",
            "Some routers block devices from seeing each other (\"AP isolation\")",
            "Still nothing? Enter the TV's IP below. Find it on the TV: Settings → Network → About",
        ).forEach {
            Row {
                Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(Ink.amber))
                Spacer(Modifier.width(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            }
        }
    }
}

@Composable
private fun ManualIp(onSubmit: (String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val valid = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

    fun go() {
        if (!valid.matches(ip.trim())) { error = "That doesn't look like an IP, e.g. 192.168.1.50"; haptics.error(); return }
        checking = true; error = null
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TvDiscovery.isOpen(ip.trim(), com.mahudesh.tvremote.tv.PairingClient.PAIRING_PORT, 2000)
            }
            checking = false
            if (ok) onSubmit(ip.trim()) else { error = "No Android TV answered at that address"; haptics.error() }
        }
    }

    Text("OR ENTER IP ADDRESS", style = MaterialTheme.typography.labelSmall, color = Ink.faint)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .weight(1f)
                .clip(shape)
                .background(Ink.surface)
                .border(1.dp, if (error != null) Ink.red.copy(alpha = 0.6f) else Ink.line, shape)
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            if (ip.isEmpty()) Text("192.168.1.50", color = Ink.faint, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = ip,
                onValueChange = { v -> ip = v.filter { it.isDigit() || it == '.' }.take(15); error = null },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.text),
                cursorBrush = SolidColor(Ink.amber),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(10.dp))
        RoundIcon(if (checking) Lu.Refresh else Lu.Send, "Connect", accent = true) { if (!checking) go() }
    }
    AnimatedVisibility(error != null) {
        Text(error.orEmpty(), style = MaterialTheme.typography.labelMedium, color = Ink.red, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------------------------------------------------------- Waiting / code

@Composable
private fun Waiting(name: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Radar(active = true)
        Spacer(Modifier.height(18.dp))
        Text("Asking $name for a code…", style = MaterialTheme.typography.titleLarge, color = Ink.text)
        Spacer(Modifier.height(6.dp))
        Text("A code will appear on your TV screen.", style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
    }
}

@Composable
private fun CodeEntry(pairing: Pairing, actions: AddTvActions) {
    val tv = when (pairing) { is Pairing.EnterCode -> pairing.tv; is Pairing.Verifying -> pairing.tv; else -> null }
    val length = when (pairing) { is Pairing.EnterCode -> pairing.codeLength; is Pairing.Verifying -> pairing.codeLength; else -> 6 }
    val error = (pairing as? Pairing.EnterCode)?.error
    val verifying = pairing is Pairing.Verifying
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val haptics = rememberHaptics()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(error) {
        if (error != null) {
            haptics.error()
            code = ""
            repeat(4) { shake.animateTo(if (it % 2 == 0) 14f else -14f, tween(45)) }
            shake.animateTo(0f, tween(45))
            focus.requestFocus()
            keyboard?.show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(72.dp))
        TvIllustration(length)
        Spacer(Modifier.height(22.dp))
        Text("Look at ${tv?.name ?: "your TV"}", style = MaterialTheme.typography.titleLarge, color = Ink.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Type the $length characters shown on the screen. They can be letters and numbers.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(28.dp))

        Box(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(length) { i ->
                    val ch = code.getOrNull(i)
                    val active = i == code.length && !verifying
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier
                            .size(width = 46.dp, height = 58.dp)
                            .clip(shape)
                            .background(Ink.surface)
                            .border(
                                if (active) 2.dp else 1.dp,
                                when { error != null -> Ink.red.copy(alpha = 0.6f); active -> Ink.amber; else -> Ink.line },
                                shape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(ch, transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut() }, label = "c") { c ->
                            Text(c?.toString() ?: "", fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 24.sp, color = Ink.text)
                        }
                    }
                    if (i == length / 2 - 1) Spacer(Modifier.width(6.dp))
                }
            }
            // Invisible field that owns the keyboard.
            BasicTextField(
                value = code,
                onValueChange = { v ->
                    // Users often read 0 as O on TV fonts; fix it for them.
                    val clean = v.uppercase().replace('O', '0').filter { it in "0123456789ABCDEF" }.take(length)
                    if (clean.length > code.length) haptics.tick()
                    code = clean
                    if (clean.length == length) actions.submitCode(clean)
                },
                enabled = !verifying,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = androidx.compose.ui.graphics.Color.Transparent),
                cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false, imeAction = ImeAction.Done,
                ),
                modifier = Modifier.matchParentSize().focusRequester(focus),
            )
        }
        Spacer(Modifier.height(18.dp))
        AnimatedContent(
            when { verifying -> "Checking…"; error != null -> error; else -> " " },
            transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "msg",
        ) {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (error != null && !verifying) Ink.red else Ink.amber, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        TextButton("Code disappeared? Cancel and try again") { actions.cancel() }
    }
}

/** A tiny TV with a code on it, so it's obvious where to look. */
@Composable
private fun TvIllustration(length: Int) {
    val blink by rememberInfiniteTransition(label = "b").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(160.dp, 96.dp).clip(RoundedCornerShape(14.dp)).background(Ink.surface).border(2.dp, Ink.line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(List(length) { "•" }.chunked(2).joinToString("  ") { it.joinToString(" ") }, fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 20.sp, color = Ink.amber.copy(alpha = blink))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.size(56.dp, 5.dp).clip(CircleShape).background(Ink.line))
    }
}

// ---------------------------------------------------------------- Done / failed

@Composable
private fun Success(name: String, actions: AddTvActions) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.paired_success))
    val progress by animateLottieCompositionAsState(composition)
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) { haptics.success(); delay(1700); actions.done() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LottieAnimation(composition, { progress }, modifier = Modifier.size(180.dp))
        Text("Paired with $name", style = MaterialTheme.typography.titleLarge, color = Ink.text)
        Spacer(Modifier.height(6.dp))
        Text("It'll reconnect by itself next time.", style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
    }
}

@Composable
private fun Failed(message: String, actions: AddTvActions) {
    LaunchedEffect(Unit) { /* haptic handled by caller state change */ }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Ink.red.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Lu.WifiOff, null, tint = Ink.red, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Couldn't pair", style = MaterialTheme.typography.titleLarge, color = Ink.text)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Ink.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(22.dp))
        TextButton("Back to search") { actions.cancel() }
    }
}

@Composable
private fun TextButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = Ink.amber,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
