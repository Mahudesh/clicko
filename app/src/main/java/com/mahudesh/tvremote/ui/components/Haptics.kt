package com.mahudesh.tvremote.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Global switch, flipped from Settings. */
object HapticsSwitch { @Volatile var enabled = true }

/** Distinct haptic "feels" so buttons, ticks and confirmations are recognisable without looking. */
class Haptics(private val view: View) {
    private fun buzz(type: Int): Boolean = HapticsSwitch.enabled && view.performHapticFeedback(type)

    /** A normal button press. */
    fun tap() = buzz(HapticFeedbackConstants.KEYBOARD_TAP)

    /** Light tick: touchpad steps, held-key repeats. */
    fun tick() = buzz(
        if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** Heavier thump: OK/select, power, long-press start. */
    fun heavy() = buzz(HapticFeedbackConstants.LONG_PRESS)

    fun success() = buzz(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
    )

    fun error() = buzz(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
