package com.mahudesh.tvremote.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * Remote session for v1 TVs (older Android TV Remote Service) on port 6466.
 *
 * Frames: [0x01][type][u16 big-endian length][payload]. Verified on a Xiaomi Mi TV:
 * configure (0) → TV acks (7, 0x1b, 0x26); key events (2) as press+release with a running
 * counter; intent/deep-link launch (16) for https:// and intent: URIs; ping 0x14 → pong 0x15.
 * References: Aymkdn "Google TV Remote Control" wiki, drosoCode/atvremote pkg/v1.
 */
class LegacySession private constructor(
    private val socket: SSLSocket,
    private val onClosed: (Throwable?) -> Unit,
) : TvLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)
    private val configured = CountDownLatch(1)
    private val _status = MutableStateFlow(TvStatus())
    override val status: StateFlow<TvStatus> = _status.asStateFlow()
    override val canSyncTextField = false

    @Volatile private var counter = 0
    @Volatile private var closed = false

    private fun start() {
        // v1 TVs stay silent while idle (no pings, verified on a Mi TV), so a read timeout would
        // drop healthy links. Instead we probe the TV every few seconds; two misses = it's gone.
        socket.soTimeout = 0
        scope.launch {
            val error = runCatching { while (!closed) readFrame() }.exceptionOrNull()
            if (!closed) { android.util.Log.w("TvRemote", "v1 session ended: $error"); close(); onClosed(error) }
        }
        scope.launch {
            val host = socket.inetAddress.hostAddress ?: return@launch
            var misses = 0
            while (!closed) {
                delay(HEARTBEAT_MS)
                misses = if (TvDiscovery.isAlive(host)) 0 else misses + 1
                if (misses >= 2 && !closed) {
                    android.util.Log.w("TvRemote", "v1 heartbeat lost")
                    close(); onClosed(java.net.SocketTimeoutException("TV stopped answering"))
                }
            }
        }
        send(TYPE_CONFIGURE, CONFIGURE_PAYLOAD)
    }

    private fun readFrame() {
        if (input.readUnsignedByte() != VERSION) throw EOFException("Unexpected data from TV")
        val type = input.readUnsignedByte()
        val payload = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
        when (type) {
            TYPE_PING -> send(TYPE_PONG, ByteArray(0))
            TYPE_CONFIGURE_ACK -> configured.countDown()
            else -> Unit // 0x1b / 0x24 / 0x26 state messages: meaning unverified, ignored.
        }
    }

    /** Keys go out strictly in order (fair mutex), so fast typing never interleaves press/release pairs. */
    private val keyOrder = Mutex()

    override fun key(code: Int, direction: KeyDirection) {
        scope.launch {
            keyOrder.withLock {
                when (direction) {
                    KeyDirection.SHORT -> {
                        keyEvent(code, ACTION_DOWN)
                        delay(PRESS_MS)
                        keyEvent(code, ACTION_UP)
                        delay(GAP_MS)
                    }
                    KeyDirection.START_LONG -> keyEvent(code, ACTION_DOWN)
                    KeyDirection.END_LONG -> keyEvent(code, ACTION_UP)
                }
            }
        }
    }

    private fun keyEvent(code: Int, action: Int) {
        android.util.Log.d("TvRemote", "v1 key $code ${if (action == ACTION_DOWN) "down" else "up"}")
        val payload = ByteBuffer.allocate(16).putInt(0).putInt(counter++).putInt(action).putInt(code).array()
        send(TYPE_KEY, payload)
    }

    // Never touch the socket from the caller's (UI) thread: Android forbids network I/O there and the
    // failure would look like a dropped connection.
    override fun openLink(link: String) {
        scope.launch {
            keyOrder.withLock {
                android.util.Log.d("TvRemote", "v1 open $link")
                send(TYPE_INTENT, link.toByteArray())
            }
        }
    }

    override fun setText(text: String) = Unit // Not supported on v1; callers type key by key.

    private fun send(type: Int, payload: ByteArray) {
        if (closed) return
        runCatching {
            synchronized(output) {
                output.writeByte(VERSION)
                output.writeByte(type)
                output.writeShort(payload.size)
                output.write(payload)
                output.flush()
            }
        }.onFailure { if (!closed) { close(); onClosed(it) } }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        scope.cancel()
    }

    companion object {
        private const val VERSION = 1
        private const val TYPE_CONFIGURE = 0
        private const val TYPE_KEY = 2
        private const val TYPE_CONFIGURE_ACK = 7
        private const val TYPE_INTENT = 16
        private const val TYPE_PING = 0x14
        private const val TYPE_PONG = 0x15
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
        private const val PRESS_MS = 80L
        private const val HEARTBEAT_MS = 8_000L
        private const val GAP_MS = 40L

        // int 1, int 1, byte 32, byte 3, six zero bytes, then a length-prefixed client name.
        private val CONFIGURE_PAYLOAD = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 32, 3, 0, 0, 0, 0, 0, 0, 6) + "Remote".toByteArray()

        /** Connects with the paired certificate; throws [NotPairedException] if the TV doesn't ack. */
        fun open(host: String, onClosed: (Throwable?) -> Unit): LegacySession {
            val socket = try {
                TlsIdentity.connect(host, RemoteSession.REMOTE_PORT, mode = TlsMode.TLS12)
            } catch (e: javax.net.ssl.SSLException) {
                throw NotPairedException()
            }
            val session = LegacySession(socket, onClosed)
            session.start()
            if (!session.configured.await(5, TimeUnit.SECONDS)) {
                session.close()
                throw NotPairedException()
            }
            return session
        }
    }
}

