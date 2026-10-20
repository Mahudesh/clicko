package com.mahudesh.tvremote.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import com.mahudesh.tvremote.ui.theme.Manrope
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.tv.ProState
import com.mahudesh.tvremote.tv.TvApp
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.components.rememberHaptics
import com.mahudesh.tvremote.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

interface ProActions {
    fun back()
    suspend fun screenshot(): ByteArray?
    suspend fun apps(): List<TvApp>
    suspend fun currentApp(): String?
    fun launch(pkg: String)
    fun forceStop(pkg: String)
    fun setVolume(level: Int)
    /** Streams the TV screen onto [surface] until cancelled; false if the TV gave no video. */
    suspend fun mirror(surface: android.view.Surface, onFrame: () -> Unit): Boolean
    fun openSettings()
}

/** Everything Pro mode (ADB) adds on top of the remote. Works the same on v1 and v2 TVs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProToolsScreen(pro: ProState, adbVolume: Pair<Int, Int>?, actions: ProActions) {
    BackHandler { actions.back() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    var live by remember { mutableStateOf(false) }
    var loadingShot by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<TvApp>?>(null) }
    var current by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    var hasVideo by remember { mutableStateOf(false) }
    /** The TV can't stream video: fall back to PNG screenshots. */
    var videoFailed by remember { mutableStateOf(false) }
    /** Bumped by Refresh: grabs one fresh frame. */
    var refreshTick by remember { mutableStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }

    suspend fun grabPng() {
        val png = actions.screenshot()
        shot = png?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } } ?: shot
    }

    // One pipeline for both modes: Live keeps the stream open; Refresh opens it just long enough
    // for a fresh frame, then freezes on it.
    LaunchedEffect(surface, live, refreshTick) {
        val sf = surface ?: return@LaunchedEffect
        loadingShot = true
        if (videoFailed) {
            do { grabPng(); loadingShot = false; if (live) delay(1000) } while (live)
            return@LaunchedEffect
        }
        val firstFrame = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch {
            val ok = actions.mirror(sf) { hasVideo = true; loadingShot = false; firstFrame.complete(Unit) }
            if (!ok) { videoFailed = true; loadingShot = false; firstFrame.complete(Unit); refreshTick++ }
        }
        if (!live) {
            kotlinx.coroutines.withTimeoutOrNull(8000) { firstFrame.await() }
            delay(600) // let the frame settle
            job.cancel()
            loadingShot = false
        }
    }
    LaunchedEffect(Unit) {
        apps = actions.apps()
        current = actions.currentApp()
    }

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
    // Soft amber glow behind the header: the "Pro" signature.
    Box(
        Modifier.fillMaxWidth().height(320.dp).background(
            Brush.radialGradient(
                listOf(Ink.amber.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(0f, 0f), radius = 900f,
            ),
        ),
    )
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
            Spacer(Modifier.weight(1f))
            val connected = pro is ProState.Connected
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Ink.surface).border(1.dp, Ink.line, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (connected) Ink.green else Ink.faint))
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "ADB connected" else "Not connected", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pro tools", fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 30.sp, color = Ink.text)
            Spacer(Modifier.width(10.dp))
            Text(
                "PRO", fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 11.sp, letterSpacing = 1.5.sp, color = Ink.bg,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFD27A), Ink.amber, Color(0xFFE8892B))))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        val info = (pro as? ProState.Connected)?.info
        Text(
            info?.let { "${it.maker.replaceFirstChar { c -> c.uppercase() }} ${it.model} · Android ${it.android}" } ?: "Full control of your TV over ADB",
            style = MaterialTheme.typography.bodyMedium, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))

        // ---- Live screen, framed like a TV
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF26262E), Color(0xFF111115))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent)), RoundedCornerShape(22.dp))
                .padding(8.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(15.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (!fullscreen) MirrorView { surface = it }
                if (videoFailed) shot?.let { Image(it, "TV screen", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color.Black)) }
                if (!hasVideo && shot == null || loadingShot && !live) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Lu.Tv, null, tint = Ink.faint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(if (loadingShot) "Getting the TV screen…" else "Tap Refresh or go Live", color = Ink.faint, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(34.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { haptics.tap(); live = true; fullscreen = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Lu.Expand, "Full screen", tint = Color.White, modifier = Modifier.size(16.dp)) }
                if (live) Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
                    val a by pulse.animateFloat(1f, 0.25f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.red.copy(alpha = a)))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 10.sp, letterSpacing = 1.2.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Segmented control: Refresh | Live
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surface).border(1.dp, Ink.line, RoundedCornerShape(16.dp)).padding(4.dp),
        ) {
            Segment(Lu.Refresh, if (loadingShot && !live) "Refreshing…" else "Refresh", selected = false, enabled = !live, modifier = Modifier.weight(1f)) {
                haptics.tap(); refreshTick++
            }
            Segment(Lu.Activity, if (live) "Live" else "Go live", selected = live, enabled = true, modifier = Modifier.weight(1f)) {
                haptics.tap(); live = !live
            }
        }
        Spacer(Modifier.height(26.dp))

        // ---- Volume
        Section("VOLUME") {
            val max = (adbVolume?.second ?: 0).coerceAtLeast(1)
            var dragging by remember { mutableStateOf(false) }
            var value by remember { mutableFloatStateOf(0f) }
            if (!dragging) value = (adbVolume?.first ?: 0).toFloat()
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (adbVolume == null) "—" else "${(value / max * 100).roundToInt()}",
                    fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 40.sp, color = Ink.text,
                )
                Text("%", fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 18.sp, color = Ink.muted, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    if (adbVolume == null) "Reading from TV…" else "Level ${value.roundToInt()} of $max",
                    style = MaterialTheme.typography.labelMedium, color = Ink.faint, modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lu.Volume, null, tint = Ink.muted, modifier = Modifier.size(18.dp))
                Slider(
                    value = value,
                    onValueChange = { dragging = true; value = it },
                    onValueChangeFinished = { dragging = false; haptics.tick(); actions.setVolume(value.roundToInt()) },
                    valueRange = 0f..max.toFloat(),
                    enabled = adbVolume != null,
                    colors = SliderDefaults.colors(thumbColor = Ink.amber, activeTrackColor = Ink.amber, inactiveTrackColor = Ink.line, disabledInactiveTrackColor = Ink.line),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
            }
        }

        // ---- Quick actions
        Section("QUICK ACTIONS", padded = false) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(Lu.Settings, "TV settings", "Open on the TV", Modifier.weight(1f)) { haptics.tap(); actions.openSettings() }
                val currentLabel = current?.let { c -> apps?.firstOrNull { it.pkg == c }?.label ?: c.substringAfterLast('.') }
                ActionTile(Lu.Close, "Close app", currentLabel ?: "Nothing open", Modifier.weight(1f), danger = true) {
                    val pkg = current ?: return@ActionTile
                    haptics.heavy(); actions.forceStop(pkg); note = "Closed ${currentLabel ?: pkg}"
                    scope.launch { delay(800); current = actions.currentApp() }
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Ink.muted, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) }
        }

        // ---- Apps
        Section("APPS", hint = "Tap to open · hold to close", padded = false) {
            when (val list = apps) {
                null -> Text("Loading apps…", color = Ink.faint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                else -> if (list.isEmpty()) Text("No apps found", color = Ink.faint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                else Column(Modifier.padding(vertical = 6.dp)) {
                    list.forEachIndexed { i, app ->
                        if (i > 0) Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(1.dp).background(Ink.line.copy(alpha = 0.5f)))
                        val brand = com.mahudesh.tvremote.data.AppShortcuts.firstOrNull { it.pkg == app.pkg }
                        val isCurrent = app.pkg == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { haptics.tap(); actions.launch(app.pkg); current = app.pkg; note = "Opening ${app.label}" },
                                    onLongClick = { haptics.heavy(); actions.forceStop(app.pkg); note = "Closed ${app.label}" },
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                                    .background(brand?.color ?: appColor(app.pkg)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (brand?.logo != null) Icon(
                                    androidx.compose.ui.res.painterResource(brand.logo), null,
                                    tint = brand.logoTint ?: Color.Unspecified,
                                    modifier = Modifier.fillMaxWidth(brand.logoScale + 0.1f),
                                ) else Text(app.label.take(1).uppercase(), fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 16.sp, color = Color.White)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isCurrent) Text("On screen now", style = MaterialTheme.typography.labelSmall, color = Ink.amber)
                            }
                            Icon(Lu.ChevronRight, null, tint = Ink.faint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // ---- Info
        info?.let {
            Section("ABOUT THIS TV", padded = false) {
                val tiles = listOf(
                    "Brand" to it.maker.replaceFirstChar { c -> c.uppercase() },
                    "Model" to it.model,
                    "Android" to "${it.android} · API ${it.sdk}",
                    "Free storage" to listOfNotNull(it.storageFree, it.storageTotal?.let { t -> "of $t" }).joinToString(" "),
                )
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (k, v) ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Ink.raised).padding(14.dp)) {
                                    Text(k.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.faint)
                                    Spacer(Modifier.height(4.dp))
                                    Text(v.ifBlank { "—" }, style = MaterialTheme.typography.titleSmall, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (fullscreen) FullscreenMirror(
        onSurface = { surface = it },
        shot = if (videoFailed) shot else null,
        onClose = { fullscreen = false },
    )
    }
}

/** Landscape, edge-to-edge live view: watch the TV on the phone. Back or tap ✕ to leave. */
@Composable
private fun FullscreenMirror(onSurface: (android.view.Surface?) -> Unit, shot: ImageBitmap?, onClose: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    BackHandler { onClose() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val window = activity?.window
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = before ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    var chrome by remember { mutableStateOf(true) }
    LaunchedEffect(chrome) { if (chrome) { delay(3000); chrome = false } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { chrome = !chrome },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)) {
            MirrorView(onSurface)
            shot?.let { Image(it, "TV screen", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color.Black)) }
        }
        androidx.compose.animation.AnimatedVisibility(chrome, Modifier.align(Alignment.TopEnd), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(
                Modifier.padding(20.dp).size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Lu.Shrink, "Exit full screen", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Hardware-decoded video lands on this TextureView; it keeps the last frame when paused. */
@Composable
private fun MirrorView(onSurface: (android.view.Surface?) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    var current: android.view.Surface? = null
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { current = android.view.Surface(st); onSurface(current) }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { onSurface(null); current?.release(); return true }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** A stable, muted colour per app for its letter tile. */
private fun appColor(pkg: String): Color {
    val palette = listOf(0xFF3B4A6B, 0xFF5B3B6B, 0xFF3B6B5A, 0xFF6B4F3B, 0xFF3B5E6B, 0xFF6B3B4A)
    return Color(palette[(pkg.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
private fun Section(title: String, hint: String? = null, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, color = Ink.faint)
        hint?.let { Spacer(Modifier.weight(1f)); Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.faint) }
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ink.surface)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))), shape)
            .then(if (padded) Modifier.padding(18.dp) else Modifier),
        content = content,
    )
    Spacer(Modifier.height(26.dp))
}

@Composable
private fun Segment(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Ink.amber else Color.Transparent, label = "seg")
    val fg = when { selected -> Ink.bg; enabled -> Ink.text; else -> Ink.faint }
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) Ink.red else Ink.amber
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Ink.raised).clickable(onClick = onClick).padding(14.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ink.text)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
