package com.mahudesh.tvremote.tv

import java.security.MessageDigest
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote v2 pairing ("polo") on port 6467.
 *
 * Flow: PairingRequest -> Options -> Configuration -> (TV shows a 6-char hex code)
 * -> Secret. Once the TV accepts the secret it remembers our certificate for good.
 */
class PairingClient(
    private val host: String,
    private val clientName: String,
    private val mode: TlsMode = TlsMode.AUTO,
) : Pairer {

    override val codeLength = CODE_LENGTH

    private var socket: SSLSocket? = null

    /** Connects and asks the TV to display its pairing code. Returns the TV's own name, if it sent one. */
    override fun start(): String? {
        val s = step(Stage.CONNECT) { TlsIdentity.connect(host, PAIRING_PORT, mode = mode) }
        socket = s

        send { message(F_PAIRING_REQUEST) { string(1, SERVICE_NAME); string(2, clientName) } }
        val serverName = step(Stage.REQUEST) { expect(F_PAIRING_REQUEST_ACK) }.message(F_PAIRING_REQUEST_ACK)?.string(1)

        send {
            message(F_OPTIONS) {
                message(1) { int(1, ENCODING_HEX); int(2, CODE_LENGTH) } // input_encodings
                int(3, ROLE_INPUT)                                     // preferred_role
            }
        }
        step(Stage.OPTIONS) { expect(F_OPTIONS) }

        send {
            message(F_CONFIGURATION) {
                message(1) { int(1, ENCODING_HEX); int(2, CODE_LENGTH) }
                int(2, ROLE_INPUT)
            }
        }
        step(Stage.CONFIGURATION) { expect(F_CONFIGURATION_ACK) }

        return serverName?.takeIf { it.isNotBlank() }
    }

    /** Sends the code shown on the TV. Throws [PairingException] if it is wrong. */
    override fun finish(code: String) {
        val s = socket ?: throw PairingException("Pairing was not started")
        val clean = code.trim().uppercase()
        if (clean.length != CODE_LENGTH || clean.any { it !in "0123456789ABCDEF" }) {
            throw PairingException("Enter the 6 characters shown on the TV")
        }

        val (cm, ce) = TlsIdentity.certificate.rsaParts()
        val (sm, se) = s.peerCertificate().rsaParts()
        val nonce = clean.substring(2).hexToBytes()
        val hash = MessageDigest.getInstance("SHA-256").run {
            update(cm); update(ce); update(sm); update(se); update(nonce)
            digest()
        }
        // The first byte of the code is a checksum of the hash: catches typos before the TV does.
        if (hash[0] != clean.substring(0, 2).toInt(16).toByte()) {
            throw PairingException("That code doesn't match. Check the TV and try again")
        }

        send { message(F_SECRET) { bytes(1, hash) } }
        step(Stage.SECRET) { expect(F_SECRET_ACK) }
    }

    /** Runs one protocol step, turning network failures into a [PairingException] that says which step broke. */
    private inline fun <T> step(stage: Stage, block: () -> T): T = try {
        block()
    } catch (e: PairingException) {
        throw e
    } catch (e: Exception) {
        throw PairingException(stage.describe(e), stage, e)
    }

    private fun send(body: ProtoWriter.() -> Unit) {
        val msg = ProtoWriter().int(1, PROTOCOL_VERSION).int(2, STATUS_OK).apply(body).toByteArray()
        Framing.write(socket!!.outputStream, msg)
    }

    private fun expect(field: Int): ProtoMessage {
        val msg = ProtoMessage.parse(Framing.read(socket!!.inputStream))
        val status = msg.int(2).toInt()
        if (status != STATUS_OK) {
            throw PairingException(
                when (status) {
                    STATUS_BAD_SECRET -> "Wrong code. A new one is on the TV, try again"
                    STATUS_BAD_CONFIGURATION -> "This TV refused the pairing setup"
                    else -> "TV rejected pairing (status $status)"
                },
            )
        }
        if (!msg.has(field)) throw PairingException("Unexpected reply from TV")
        return msg
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        const val PAIRING_PORT = 6467
        private const val SERVICE_NAME = "atvremote"
        private const val PROTOCOL_VERSION = 2
        private const val STATUS_OK = 200
        private const val STATUS_BAD_CONFIGURATION = 401
        private const val STATUS_BAD_SECRET = 402
        private const val ENCODING_HEX = 3
        private const val ROLE_INPUT = 1
        private const val CODE_LENGTH = 6

        private const val F_PAIRING_REQUEST = 10
        private const val F_PAIRING_REQUEST_ACK = 11
        private const val F_OPTIONS = 20
        private const val F_CONFIGURATION = 30
        private const val F_CONFIGURATION_ACK = 31
        private const val F_SECRET = 40
        private const val F_SECRET_ACK = 41
    }
}

/** Pairing steps, numbered so a screenshot of the error tells us exactly where a TV gave up. */
enum class Stage(val code: String, private val what: String) {
    CONNECT("P1", "connecting"),
    REQUEST("P2", "after the pairing request"),
    OPTIONS("P3", "after sending options"),
    CONFIGURATION("P4", "after sending configuration"),
    SECRET("P5", "after sending the code"),
    ;

    fun describe(e: Exception): String {
        if (e is Net.RoutesFailed) return "Couldn't reach the TV $what · tried ${e.tried.joinToString(" | ")}"
        val why = when (e) {
            is java.io.EOFException -> "TV hung up"
            is java.net.SocketTimeoutException -> "TV stopped answering"
            is java.net.ConnectException -> "TV refused the connection"
            is javax.net.ssl.SSLException -> "secure handshake failed"
            else -> e.javaClass.simpleName
        }
        return "$why $what"
    }
}

class PairingException(message: String, val stage: Stage? = null, cause: Throwable? = null) : Exception(message, cause)

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
