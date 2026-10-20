package com.mahudesh.tvremote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mahudesh.tvremote.R
import io.github.ronjunevaldoz.shadcncompose.theme.ShadcnTheme
import io.github.ronjunevaldoz.shadcncompose.tokens.ShadcnAccent
import io.github.ronjunevaldoz.shadcncompose.tokens.ShadcnBaseColor

/** Palette: near-black ink, warm off-white text, one amber accent. No gradients. */
object Ink {
    val bg = Color(0xFF0B0B0E)
    val surface = Color(0xFF141418)
    val raised = Color(0xFF1C1C22)
    val line = Color(0xFF2A2A31)
    val text = Color(0xFFF4F2EE)
    val muted = Color(0xFF8E8C93)
    val faint = Color(0xFF55545B)
    val amber = Color(0xFFFFB547)
    val amberDim = Color(0x33FFB547)
    val green = Color(0xFF4ADE80)
    val red = Color(0xFFFF6B6B)
}

private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun manrope(weight: Int) = Font(
    R.font.manrope_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))
val Manrope = FontFamily(manrope(500), manrope(600), manrope(700), manrope(800))

private val type = Typography(
    displaySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 34.sp, letterSpacing = (-0.02).em, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 24.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 20.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 12.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 11.sp, letterSpacing = 0.08.em),
)

@Composable
fun TvRemoteTheme(content: @Composable () -> Unit) {
    ShadcnTheme(isDark = true, baseColor = ShadcnBaseColor.Zinc, accent = ShadcnAccent.Amber) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Ink.amber, onPrimary = Ink.bg,
                background = Ink.bg, onBackground = Ink.text,
                surface = Ink.surface, onSurface = Ink.text,
                surfaceVariant = Ink.raised, onSurfaceVariant = Ink.muted,
                outline = Ink.line, error = Ink.red,
            ),
            typography = type,
            content = content,
        )
    }
}
