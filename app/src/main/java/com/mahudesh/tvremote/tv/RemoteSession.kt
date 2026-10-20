package com.mahudesh.tvremote.tv

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import javax.net.ssl.SSLSocket

data class TvStatus(
    val powered: Boolean? = null,
    val volume: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean = false,
    val currentApp: String? = null,
    val textFieldActive: Boolean = false,
    val textFieldValue: String = "",
)

/**
 * A live remote-control session on port 6466, opened with our already-paired certificate.
 * Answers the TV's configure/ping messages to stay alive and exposes what the TV reports.
 */
class RemoteSession private constructor(
    private val socket: SSLSocket,
    private val onClosed: (Throwable?) -> Unit,
) : TvLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(TvStatus())
    override val status: StateFlow<TvStatus> = _status.asStateFlow()
    override val canSyncTextField = true

    private var imeCounter = 0
    private var fieldCounter = 0
    @Volatile private var closed = false

    private fun startReading() = scope.launch {
        val error = runCatching {
            // The TV pings every ~5s; silence for longer means it was switched off or left the network.
            socket.soTimeout = SILENCE_MS
            while (!closed) handle(ProtoMessage.parse(Framing.read(socket.inputStream)))
        }.exceptionOrNull()
        if (!closed) {
            close()
            onClosed(error)
        }
    }

    private fun handle(msg: ProtoMessage) {
        when {
            msg.has(F_CONFIGURE) -> send {
                message(F_CONFIGURE) {
                    int(1, FEATURES)
                    message(2) {
                        string(1, Build.MODEL)
                        string(2, Build.MANUFACTURER)
                        int(3, 1)
                        string(4, "1")
                        string(5, "atvremote")
                        string(6, "1.0.0")
                    }
                }
            }
            msg.has(F_SET_ACTIVE) -> send { message(F_SET_ACTIVE) { int(1, FEATURES) } }
            msg.has(F_PING_REQUEST) -> {
                val v = msg.message(F_PING_REQUEST)?.int(1) ?: 0
                send { message(F_PING_RESPONSE) { int(1, v) } }
            }
            msg.has(F_START) -> _status.update { it.copy(powered = msg.message(F_START)?.bool(1)) }
            msg.has(F_SET_VOLUME) -> msg.message(F_SET_VOLUME)?.let { v ->
                _status.update {
                    it.copy(volumeMax = v.int(6).toInt(), volume = v.int(7).toInt(), muted = v.bool(8))
                }
            }
            msg.has(F_IME_KEY_INJECT) -> msg.message(F_IME_KEY_INJECT)?.let { ime ->
                val app = ime.message(1)?.string(12)
                val field = ime.message(2)
                field?.let { fieldCounter = it.int(1).toInt() }
                _status.update {
                    it.copy(
                        currentApp = app ?: it.currentApp,
                        textFieldActive = field != null,
                        textFieldValue = field?.string(2).orEmpty(),
                    )
                }
            }
            msg.has(F_IME_BATCH_EDIT) -> msg.message(F_IME_BATCH_EDIT)?.let {
                imeCounter = it.int(1).toInt()
                fieldCounter = it.int(2).toInt()
            }
            msg.has(F_IME_SHOW_REQUEST) -> _status.update { it.copy(textFieldActive = true) }
        }
    }

    override fun key(code: Int, direction: KeyDirection) = send {
        message(F_KEY_INJECT) { int(1, code); int(2, direction.wire) }
    }

    /** Opens an app via deep link, e.g. "https://www.youtube.com" or "market://launch?id=<package>". */
    override fun openLink(link: String) = send { message(F_APP_LINK) { string(1, link) } }

    /** Replaces the TV's focused text field with [text]. */
    override fun setText(text: String) = send {
        message(F_IME_BATCH_EDIT) {
            int(1, imeCounter)
            int(2, fieldCounter)
            message(3) {
                int(1, 1)
                message(2) {
                    int(1, (text.length - 1).coerceAtLeast(0))
                    int(2, (text.length - 1).coerceAtLeast(0))
                    string(3, text)
                }
            }
        }
    }

    private fun send(body: ProtoWriter.() -> Unit) {
        if (closed) return
        val bytes = ProtoWriter().apply(body).toByteArray()
        scope.launch {
            runCatching { Framing.write(socket.outputStream, bytes) }
                .onFailure { if (!closed) { close(); onClosed(it) } }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        scope.cancel()
    }

    companion object {
        /** No message from the TV for this long = it's gone. */
        const val SILENCE_MS = 20_000
        const val REMOTE_PORT = 6466
        // ping | key | ime | power | volume | app-link bitmask, as the Google TV app sends.
        private const val FEATURES = 622

        private const val F_CONFIGURE = 1
        private const val F_SET_ACTIVE = 2
        private const val F_PING_REQUEST = 8
        private const val F_PING_RESPONSE = 9
        private const val F_KEY_INJECT = 10
        private const val F_IME_KEY_INJECT = 20
        private const val F_IME_BATCH_EDIT = 21
        private const val F_IME_SHOW_REQUEST = 22
        private const val F_START = 40
        private const val F_SET_VOLUME = 50
        private const val F_APP_LINK = 90

        /** Connects with the paired certificate. Throws [NotPairedException] if the TV doesn't know us. */
        fun open(host: String, mode: TlsMode = TlsMode.AUTO, onClosed: (Throwable?) -> Unit): RemoteSession {
            val socket = try {
                TlsIdentity.connect(host, REMOTE_PORT, mode = mode)
            } catch (e: javax.net.ssl.SSLException) {
                // A TV that hasn't paired with us aborts the handshake on the client certificate.
                throw NotPairedException()
            }
            // A paired TV opens with a configure message; an unpaired one just hangs up.
            val first = try {
                ProtoMessage.parse(Framing.read(socket.inputStream))
            } catch (e: java.io.IOException) {
                runCatching { socket.close() }
                throw NotPairedException()
            }
            return RemoteSession(socket, onClosed).also {
                it.handle(first)
                it.startReading()
            }
        }
    }
}

class NotPairedException : Exception("This TV needs to be paired")

enum class KeyDirection(val wire: Int) { START_LONG(1), END_LONG(2), SHORT(3) }

/** Android KeyEvent codes, which the protocol uses directly. */
object Keys {
    const val HOME = 3
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val DEL = 67
    const val ENTER = 66
    const val MENU = 82
    const val SEARCH = 84
    const val PLAY_PAUSE = 85
    const val NEXT = 87
    const val PREVIOUS = 88
    const val REWIND = 89
    const val FAST_FORWARD = 90
    const val MUTE = 164
    const val SETTINGS = 176
    const val INPUT = 178
    const val GUIDE = 172
    const val ASSIST = 219
}
