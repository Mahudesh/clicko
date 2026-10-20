package com.mahudesh.tvremote.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mahudesh.tvremote.R
import com.mahudesh.tvremote.data.Updater
import com.mahudesh.tvremote.ui.theme.Ink
import com.mahudesh.tvremote.ui.theme.Manrope

/** Normal update: a card above the remote. Mandatory: a full screen with no way past it. */
@Composable
fun UpdatePrompt(update: Updater.Update, onUpdate: () -> Unit, onLater: () -> Unit) {
    if (update.mandatory) MandatoryUpdate(update, onUpdate) else Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Ink.raised).border(1.dp, Ink.line, shape).padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(40)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Clicko ${update.version} is ready", style = MaterialTheme.typography.titleMedium, color = Ink.text)
                    Text("Downloaded · tap Update to install", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                }
            }
            if (update.notes.isNotBlank()) Text(
                update.notes.take(240), style = MaterialTheme.typography.bodyMedium, color = Ink.muted,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PromptButton("Later", primary = false, Modifier.weight(1f), onLater)
                PromptButton("Update", primary = true, Modifier.weight(1f), onUpdate)
            }
        }
    }
}

@Composable
private fun MandatoryUpdate(update: Updater.Update, onUpdate: () -> Unit) {
    BackHandler { } // can't be skipped
    Column(
        Modifier.fillMaxSize().background(Ink.bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Logo(84)
        Spacer(Modifier.height(24.dp))
        Text("Update required", fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 26.sp, color = Ink.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Clicko ${update.version} fixes something important. Install it to keep using the app; your TVs and settings stay as they are.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.muted, textAlign = TextAlign.Center,
        )
        if (update.notes.isNotBlank()) Text(
            update.notes.take(400), style = MaterialTheme.typography.bodyMedium, color = Ink.faint, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.height(32.dp))
        PromptButton("Update now", primary = true, Modifier.fillMaxWidth(), onUpdate)
    }
}

@Composable
private fun Logo(size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(Ink.surface), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_mark), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size((size * 1.3f).dp))
    }
}

@Composable
private fun PromptButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (primary) Ink.amber else Ink.surface)
            .clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) Ink.bg else Ink.text) }
}
