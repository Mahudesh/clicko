package com.mahudesh.tvremote.tv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf wire-format encoder/decoder.
 *
 * The Android TV Remote v2 protocol only needs a handful of small messages, so
 * hand-encoding them keeps the build free of protoc/codegen plugins.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter = apply {
        tag(field, WIRE_VARINT)
        varint(value)
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, WIRE_LEN)
        varint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray())

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

/** A decoded message: field number -> list of raw values (Long for varints, ByteArray for length-delimited). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int) = fields.containsKey(field)
    fun int(field: Int, default: Long = 0): Long = fields[field]?.firstOrNull() as? Long ?: default
    fun bool(field: Int) = int(field) != 0L
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }
    fun messages(field: Int): List<ProtoMessage> =
        fields[field].orEmpty().filterIsInstance<ByteArray>().map { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val map = linkedMapOf<Int, MutableList<Any>>()
            var i = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = data[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (i < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    WIRE_VARINT -> map.getOrPut(field) { mutableListOf() }.add(readVarint())
                    WIRE_LEN -> {
                        val len = readVarint().toInt()
                        map.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(i, i + len))
                        i += len
                    }
                    WIRE_I64 -> i += 8
                    WIRE_I32 -> i += 4
                    else -> return ProtoMessage(map)
                }
            }
            return ProtoMessage(map)
        }
    }
}

private const val WIRE_VARINT = 0
private const val WIRE_I64 = 1
private const val WIRE_LEN = 2
private const val WIRE_I32 = 5

/** Both TV ports frame each protobuf message with a varint length prefix. */
object Framing {
    fun write(out: OutputStream, payload: ByteArray) {
        var len = payload.size
        val header = ByteArrayOutputStream()
        while (len >= 0x80) {
            header.write((len and 0x7F) or 0x80)
            len = len ushr 7
        }
        header.write(len)
        synchronized(out) {
            out.write(header.toByteArray())
            out.write(payload)
            out.flush()
        }
    }

    fun read(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("TV closed the connection")
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) throw EOFException("TV closed the connection")
            read += n
        }
        return buf
    }
}
