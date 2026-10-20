package com.mahudesh.tvremote.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.composables.icons.lucide.R as L

/** Lucide icons (thin, consistent 2px strokes), shipped as vector drawables. */
object Lu {
    val Power @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_power)
    val Home @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_house)
    /** Remote's Back key. */
    val Back @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_arrow_left)
    /** Screen navigation (top-left). */
    val NavBack @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_chevron_left)
    val Plus @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_plus)
    val Minus @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_minus)
    val Mute @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_volume_x)
    val Volume @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_volume_2)
    val Keyboard @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_keyboard)
    val Mic @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_mic)
    val Play @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_play)
    val Pause @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_pause)
    val Rewind @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_rewind)
    val FastForward @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_fast_forward)
    val Settings @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_settings)
    val Tv @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_tv)
    val Search @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_search)
    val Menu @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_menu)
    val Touch @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_hand)
    val Pad @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_circle_dot)
    val Grid @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_layout_grid)
    val Sparkles @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_sparkles)
    val Close @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_x)
    val Check @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_check)
    val Refresh @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_refresh_cw)
    val Wifi @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_wifi)
    val WifiOff @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_wifi_off)
    val Send @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_send)
    val Delete @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_delete)
    val Enter @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_corner_down_left)
    val Zap @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_zap)
    val More @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_ellipsis)
    val ChevronDown @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_chevron_down)
    val ChevronRight @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_chevron_right)
    val Signal @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_signal)
    val Activity @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_activity)
    val Unplug @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_unplug)
    val Plug @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_plug)
    val Trash @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_trash_2)
    val Moon @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_moon)
    val Expand @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_maximize_2)
    val Shrink @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_minimize_2)
    val Timer @Composable get() = ImageVector.vectorResource(L.drawable.lucide_ic_timer)
}
