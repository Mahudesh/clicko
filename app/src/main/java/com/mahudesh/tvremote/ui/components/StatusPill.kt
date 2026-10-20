package com.mahudesh.tvremote.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mahudesh.tvremote.tv.Link
import com.mahudesh.tvremote.ui.theme.Ink

fun Link.statusColor(): Color = when (this) {
    Link.NoTv -> Ink.faint
    is Link.Connecting -> Ink.amber
    is Link.Connected -> Ink.green
    is Link.Offline -> Ink.red
}

/**
 * TV identity + live connection state: a TV glyph in a tinted badge with a breathing
 * status dot, the pill faintly washed in the status colour. Tap opens the TV sheet.
 */
@Composable
fun StatusPill(link: Link, pro: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (title, subtitle) = when (link) {
        Link.NoTv -> "No TV" to "Tap + to add one"
        is Link.Connecting -> link.tv.name to "Connecting…"
        is Link.Connected -> link.tv.name to if (pro) "Connected · Pro" else "Connected"
        is Link.Offline -> link.tv.name to if (link.reason == "Disconnected") "Disconnected" else "Offline · tap for options"
    }
    val color by animateColorAsState(link.statusColor(), label = "status")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "p",
    )
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier
            .clip(shape)
            .background(Ink.surface)
            .background(color.copy(alpha = 0.07f))
            .clickable(onClickLabel = "TV options", onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Lu.Tv, null, tint = if (link is Link.NoTv) Ink.muted else color, modifier = Modifier.size(18.dp)) }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(1.dp, 1.dp)
                    .size(12.dp)
                    .drawBehind {
                        if (link is Link.Connected || link is Link.Connecting) {
                            drawCircle(color.copy(alpha = 0.4f * (1 - pulse)), radius = size.minDimension / 2 * (1 + 1.3f * pulse))
                        }
                    }
                    .clip(CircleShape)
                    .background(Ink.bg)
                    .padding(2.5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedContent(subtitle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sub") {
                Text(it, style = MaterialTheme.typography.labelMedium, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Lu.ChevronDown, null, tint = Ink.faint, modifier = Modifier.size(16.dp))
    }
}
