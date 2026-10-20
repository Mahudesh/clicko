package com.mahudesh.tvremote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.data.Voice
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.theme.Ink
import kotlinx.coroutines.delay

/** "Listening…" sheet for phones without a system voice prompt; also shows voice errors briefly. */
@Composable
fun VoiceSheet(state: Voice.State, onCancel: () -> Unit, onErrorShown: () -> Unit) {
    val visible = state !is Voice.State.Idle
    if (state is Voice.State.Error) LaunchedEffect(state) { delay(2500); onErrorShown() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onCancel(); onErrorShown() },
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Ink.raised).border(1.dp, Ink.line, shape)
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val listening = state as? Voice.State.Listening
                val pulse by animateFloatAsState(1f + (listening?.level ?: 0f) * 0.35f, label = "pulse")
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(80.dp).scale(pulse).clip(CircleShape).background(Ink.amber.copy(alpha = 0.18f)))
                    Box(
                        Modifier.size(60.dp).clip(CircleShape).background(if (listening != null) Ink.amber else Ink.surface),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lu.Mic, null, tint = if (listening != null) Ink.bg else Ink.muted, modifier = Modifier.size(26.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    when (state) {
                        is Voice.State.Listening -> state.partial.ifBlank { "Listening…" }
                        is Voice.State.Error -> state.message
                        Voice.State.Idle -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state is Voice.State.Error) Ink.amber else Ink.text,
                    textAlign = TextAlign.Center,
                )
                if (listening != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Say what to search on your TV", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Cancel", style = MaterialTheme.typography.labelLarge, color = Ink.text,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onCancel() }.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
