package com.mahudesh.tvremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.components.rememberHaptics
import com.mahudesh.tvremote.ui.theme.Ink
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** Frosted-glass typing sheet that floats above the phone keyboard. */
@Composable
fun KeyboardSheet(
    haze: HazeState,
    initial: String,
    onChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
    onSearchYouTube: (String) -> Unit = {},
    /** Opened from the Voice button: nothing is typed live; the only action is a YouTube search. */
    voiceMode: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .hazeBlur(
                input = HazeInput.Sources(haze),
                style = HazeBlurStyle {
                    blurRadius(28.dp)
                    backgroundColor(Ink.bg)
                    colorEffects(listOf(HazeColorEffect.tint(Ink.surface.copy(alpha = 0.72f))))
                    noiseFactor(0.06f)
                },
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(Ink.line))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (voiceMode) "Voice search" else "Type on TV", style = MaterialTheme.typography.titleLarge, color = Ink.text, modifier = Modifier.weight(1f))
            SheetIcon(Lu.Close, "Close keyboard") { onClose() }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fieldShape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(fieldShape)
                    .background(Ink.bg.copy(alpha = 0.6f))
                    .border(1.dp, Ink.line, fieldShape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (text.isEmpty()) Text("Search or type…", color = Ink.faint, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        haptics.tick()
                        if (!voiceMode) onChange(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.text),
                    cursorBrush = SolidColor(Ink.amber),
                    keyboardOptions = KeyboardOptions(imeAction = if (voiceMode) ImeAction.Search else ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { haptics.success(); onSubmit(text) },
                        onSearch = { if (text.isNotBlank()) { haptics.success(); onSearchYouTube(text) } },
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Spacer(Modifier.width(10.dp))
            if (voiceMode) YouTubeButton(enabled = text.isNotBlank()) { haptics.success(); onSearchYouTube(text) }
            else SheetIcon(Lu.Send, "Send", accent = true) { haptics.success(); onSubmit(text) }
        }
        Spacer(Modifier.height(12.dp))
        if (voiceMode) Text(
            "Fix the words if needed, then tap YouTube to search on your TV.",
            style = MaterialTheme.typography.labelMedium, color = Ink.muted,
        ) else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Open a search box on the TV first; letters appear there as you type. " +
                    "For YouTube, just type here and tap Search YouTube.",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Search YouTube",
                style = MaterialTheme.typography.labelLarge,
                color = if (text.isBlank()) Ink.faint else Ink.amber,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = text.isNotBlank()) { haptics.success(); onSearchYouTube(text) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** Red YouTube mark: searches YouTube on the TV. */
@Composable
private fun YouTubeButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (enabled) androidx.compose.ui.graphics.Color(0xFFFF0033) else Ink.raised)
            .clickable(enabled = enabled, onClickLabel = "Search on YouTube", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            androidx.compose.ui.res.painterResource(com.mahudesh.tvremote.R.drawable.brand_youtube), "Search on YouTube",
            tint = if (enabled) androidx.compose.ui.graphics.Color.White else Ink.faint, modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SheetIcon(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (accent) Ink.amber else Ink.raised)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (accent) Ink.bg else Ink.text, modifier = Modifier.size(18.dp)) }
}
