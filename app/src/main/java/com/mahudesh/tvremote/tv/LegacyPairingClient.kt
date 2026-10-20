package com.mahudesh.tvremote.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

/**
 * Pairing for TVs running the older Android TV Remote Service (< v5), "protocol v1".
 *
 * Same Polo handshake as v2, but messages are JSON with a 4-byte big-endian length prefix,
 * TLS 1.2 only, and the on-screen code is usually 4 hex characters. Verified on a Xiaomi
 * Mi TV (Android 9, "dangal"). Reference: AOSP external/google-tv-pairing-protocol
 * (JsonMessageBuilder, PoloChallengeResponse).
 */
class LegacyPairingClient(private val host: String, private val clientName: String) : Pairer {

    private var socket: SSLSocket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    override var codeLength: Int = 4
        private set

    override fun start(): String? {
        val s = step(Stage.CONNECT) { TlsIdentity.connect(host, PairingClient.PAIRING_PORT, mode = TlsMode.TLS12) }
        socket = s
        input = DataInputStream(s.inputStream)
        output = DataOutputStream(s.outputStream)

        send(TYPE_PAIRING_REQUEST, JSONObject().put("service_name", SERVICE_NAME).put("client_name", clientName))
        step(Stage.REQUEST) { expect(TYPE_PAIRING_REQUEST_ACK) }

        // Offer hex codes in the lengths TVs use; take whichever the TV picks.
        val offered = JSONArray(listOf(4, 6).map { JSONObject().put("type", ENCODING_HEX).put("symbol_length", it) })
        send(
            TYPE_OPTIONS,
            JSONObject().put("input_encodings", offered).put("output_encodings", offered).put("preferred_role", ROLE_INPUT),
        )
        val options = step(Stage.OPTIONS) { expect(TYPE_OPTIONS) }
        codeLength = options.optJSONObject("payload")?.let { pickLength(it) } ?: 4

        send(
            TYPE_CONFIGURATION,
            JSONObject()
                .put("encoding", JSONObject().put("type", ENCODING_HEX).put("symbol_length", codeLength))
                .put("client_role", ROLE_INPUT),
        )
        step(Stage.CONFIGURATION) { expect(TYPE_CONFIGURATION_ACK) }
        // v1 doesn't send the TV's name during pairing; the caller keeps the discovered one.
        return null
    }

    override fun finish(code: String) {
        val s = socket ?: throw PairingException("Pairing was not started")
        val clean = code.trim().uppercase()
        if (clean.length != codeLength || clean.any { it !in "0123456789ABCDEF" }) {
            throw PairingException("Enter the $codeLength characters shown on the TV")
        }
        val (cm, ce) = TlsIdentity.certificate.rsaParts()
        val (sm, se) = s.peerCertificate().rsaParts()
        val nonce = clean.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").run {
            update(cm); update(ce); update(sm); update(se); update(nonce)
            digest()
        }
        if (hash[0] != clean.substring(0, 2).toInt(16).toByte()) {
            throw PairingException("That code doesn't match. Check the TV and try again")
        }
        send(TYPE_SECRET, JSONObject().put("secret", android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)))
        step(Stage.SECRET) { expect(TYPE_SECRET_ACK) }
    }

    private fun pickLength(payload: JSONObject): Int? {
        val encodings = payload.optJSONArray("input_encodings") ?: return null
        val lengths = (0 until encodings.length()).mapNotNull { i ->
            encodings.optJSONObject(i)?.takeIf { it.optInt("type") == ENCODING_HEX }?.optInt("symbol_length")
        }
        return lengths.firstOrNull { it in listOf(4, 6) } ?: lengths.firstOrNull()
    }

    private fun send(type: Int, payload: JSONObject) {
        val body = JSONObject()
            .put("protocol_version", 1)
            .put("status", STATUS_OK)
            .put("type", type)
            .put("payload", payload)
            .toString().toByteArray()
        synchronized(output) {
            output.writeInt(body.size)
            output.write(body)
            output.flush()
        }
    }

    private fun expect(type: Int): JSONObject {
        val len = input.readInt()
        if (len !in 1..65536) throw PairingException("Unexpected reply from TV")
        val body = ByteArray(len).also { input.readFully(it) }
        val msg = JSONObject(String(body))
        when (val status = msg.optInt("status")) {
            STATUS_OK -> Unit
            STATUS_BAD_SECRET -> throw PairingException("Wrong code. A new one is on the TV, try again")
            STATUS_BAD_CONFIGURATION -> throw PairingException("This TV refused the pairing setup")
            else -> throw PairingException("TV rejected pairing (status $status)")
        }
        if (msg.optInt("type") != type) throw PairingException("Unexpected reply from TV")
        return msg
    }

    /** Same staged error reporting as the v2 client, codes prefixed with "L" for legacy. */
    private inline fun <T> step(stage: Stage, block: () -> T): T = try {
        block()
    } catch (e: PairingException) {
        throw e
    } catch (e: Exception) {
        throw PairingException("${stage.describe(e)} (legacy)", stage, e)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val SERVICE_NAME = "androidtvremote"
        const val STATUS_OK = 200
        const val STATUS_BAD_CONFIGURATION = 401
        const val STATUS_BAD_SECRET = 403
        const val ENCODING_HEX = 3
        const val ROLE_INPUT = 1

        const val TYPE_PAIRING_REQUEST = 10
        const val TYPE_PAIRING_REQUEST_ACK = 11
        const val TYPE_OPTIONS = 20
        const val TYPE_CONFIGURATION = 30
        const val TYPE_CONFIGURATION_ACK = 31
        const val TYPE_SECRET = 40
        const val TYPE_SECRET_ACK = 41
    }
}
