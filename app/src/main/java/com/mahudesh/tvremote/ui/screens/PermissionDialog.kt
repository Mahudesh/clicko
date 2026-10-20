package com.mahudesh.tvremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mahudesh.tvremote.ui.components.Lu
import com.mahudesh.tvremote.ui.theme.Ink

/** Shown only after the user refused Android's "Nearby devices" prompt. */
@Composable
fun PermissionDialog(onOpenSettings: () -> Unit, onTryAgain: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Ink.amber.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Lu.Wifi, null, tint = Ink.amber, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Can't reach your TV yet", style = MaterialTheme.typography.titleLarge, color = Ink.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "The remote talks to your TV over Wi-Fi, so Android needs the \"Nearby devices\" permission. Nothing is sent to the internet.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.bg).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("Open Settings below", "Tap Permissions", "Nearby devices → Allow").forEachIndexed { i, step ->
                    Row {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Ink.amber, modifier = Modifier.width(20.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium, color = Ink.text)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.amber).clickable(onClick = onOpenSettings).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Open Settings", style = MaterialTheme.typography.titleMedium, color = Ink.bg) }
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask again", style = MaterialTheme.typography.labelLarge, color = Ink.muted,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onTryAgain).padding(12.dp),
            )
        }
    }
}
