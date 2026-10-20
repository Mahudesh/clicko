package com.mahudesh.tvremote.data

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.mahudesh.tvremote.R
import com.mahudesh.tvremote.tv.Keys

/**
 * A one-tap app shortcut. [link] works in normal mode; [pkg] is used by ADB Pro mode.
 * [logo] is the brand mark (Simple Icons / dashboard-icons); [monogram] is the fallback when there's none.
 */
data class AppShortcut(
    val name: String,
    val link: String,
    val pkg: String,
    val color: Color,
    @DrawableRes val logo: Int? = null,
    val monogram: String = name.take(1),
    val logoTint: Color? = Color.White,
    /** Logo width as a fraction of the tile; wordmarks need more room than square marks. */
    val logoScale: Float = 0.46f,
)

val AppShortcuts = listOf(
    AppShortcut("YouTube", "https://www.youtube.com", "com.google.android.youtube.tv", Color(0xFFFF0033), R.drawable.brand_youtube),
    AppShortcut("Netflix", "https://www.netflix.com/title", "com.netflix.ninja", Color(0xFF25252B), R.drawable.brand_netflix, logoTint = Color(0xFFE50914)),
    AppShortcut("Prime", "https://app.primevideo.com", "com.amazon.amazonvideo.livingroom", Color(0xFF00A8E1), R.drawable.brand_primevideo, logoScale = 0.80f),
    AppShortcut("Hotstar", "https://www.hotstar.com", "in.startv.hotstar", Color(0xFF0B1033), R.drawable.brand_jiohotstar, logoTint = null, logoScale = 0.66f),
    AppShortcut("Spotify", "spotify://", "com.spotify.tv.android", Color(0xFF1DB954), R.drawable.brand_spotify),
    AppShortcut("Play Store", "market://details?id=com.android.vending", "com.android.vending", Color(0xFF1F1F24), R.drawable.brand_googleplay),
)

val DefaultMacros = listOf(
    Macro(
        id = "movie", name = "Movie night", emoji = "🍿",
        steps = listOf(
            MacroStep.Link("https://www.netflix.com/title"),
            MacroStep.Wait(2500),
        ) + List(3) { MacroStep.Key(Keys.VOLUME_UP) },
    ),
    Macro(
        id = "music", name = "Music", emoji = "🎧",
        steps = listOf(MacroStep.Link("https://www.youtube.com"), MacroStep.Wait(2000), MacroStep.Key(Keys.SEARCH)),
    ),
    Macro(
        id = "quiet", name = "Quiet", emoji = "🤫",
        steps = List(5) { MacroStep.Key(Keys.VOLUME_DOWN) },
    ),
    Macro(
        id = "sleep", name = "Sleep", emoji = "🌙",
        steps = listOf(MacroStep.Key(Keys.HOME), MacroStep.Wait(600), MacroStep.Key(Keys.POWER)),
    ),
)
