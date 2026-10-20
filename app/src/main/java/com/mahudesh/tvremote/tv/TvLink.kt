package com.mahudesh.tvremote.tv

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/** A live, paired connection to a TV. Implemented per protocol generation. */
interface TvLink : Closeable {
    val status: StateFlow<TvStatus>

    /** v2 can mirror a whole text field; v1 can only type key by key. */
    val canSyncTextField: Boolean

    /** Whether the TV reports volume/mute on this protocol (v2 does, v1 doesn't). */
    val reportsVolume: Boolean get() = canSyncTextField

    fun key(code: Int, direction: KeyDirection = KeyDirection.SHORT)

    /** Opens an app by deep link (https://…, market://…, or an intent: URI). */
    fun openLink(link: String)

    /** Replaces the focused text field (only when [canSyncTextField]). */
    fun setText(text: String)
}

/** Protocol generation a TV speaks. Saved per TV so reconnects skip detection. */
enum class Protocol(val wire: Int) {
    V2(2), V1(1);

    companion object {
        fun of(wire: Int) = entries.firstOrNull { it.wire == wire } ?: V2
    }
}
