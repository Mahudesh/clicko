package com.mahudesh.tvremote.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Plays the TV's `screenrecord` H.264 stream onto a [Surface] with the phone's hardware decoder.
 * Latency is a few hundred ms, versus 2–4 s per PNG screenshot on a typical TV.
 *
 * The stream is Annex-B (start-code separated NAL units). The TV only emits frames when the
 * picture changes, so a NAL that is still pending when the data pauses is treated as complete;
 * otherwise the last frame of every change would be held back until the next one.
 */
class ScreenMirror(private val surface: Surface, private val width: Int, private val height: Int) {

    /**
     * Decodes one stream until it ends or the caller is cancelled. [onFrame] fires on every
     * rendered frame. Returns the number of frames shown.
     */
    suspend fun play(stream: com.flyfishxu.kadb.stream.AdbStream, onFrame: () -> Unit): Int {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val scope = CoroutineScope(currentCoroutineContext())
        val reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(64 * 1024)
            runCatching {
                while (isActive) {
                    val n = stream.source.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) chunks.send(buf.copyOf(n))
                }
            }
            chunks.close()
        }

        var codec: MediaCodec? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var frames = 0
        var pts = 0L
        val pending = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()

        fun drain() {
            val c = codec ?: return
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 0)
                if (idx < 0) break
                c.releaseOutputBuffer(idx, true)
                frames++
                onFrame()
            }
        }

        fun feed(nal: ByteArray) {
            if (nal.size < 5) return
            when (nal[startCodeLength(nal)].toInt() and 0x1F) {
                7 -> { sps = nal; return }
                8 -> { pps = nal; return }
            }
            if (codec == null) {
                val s = sps ?: return
                val p = pps ?: return
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(s))
                        setByteBuffer("csd-1", ByteBuffer.wrap(p))
                    }
                    configure(format, surface, null, 0)
                    start()
                }
            }
            val c = codec ?: return
            var idx = -1
            for (i in 0 until 50) { // up to ~0.5 s for a free input buffer
                idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) break
                drain()
            }
            if (idx < 0) return
            c.getInputBuffer(idx)?.apply { clear(); put(nal) }
            c.queueInputBuffer(idx, 0, nal.size, pts, 0)
            pts += 33_333
            drain()
        }

        /** Splits complete NALs out of [pending]; with [flushTail] the trailing one is emitted too. */
        fun split(flushTail: Boolean) {
            val data = pending.toByteArray()
            val starts = startCodes(data)
            if (starts.isEmpty()) { if (flushTail) pending.reset(); return }
            for (i in 0 until starts.size - 1) feed(data.copyOfRange(starts[i], starts[i + 1]))
            pending.reset()
            val tail = data.copyOfRange(starts.last(), data.size)
            if (flushTail) feed(tail) else pending.write(tail)
        }

        try {
            while (currentCoroutineContext().isActive) {
                val chunk = withTimeoutOrNull(PAUSE_MS) { chunks.receiveCatching() }
                when {
                    chunk == null -> { if (pending.size() > 0) split(flushTail = true); drain() }
                    chunk.isClosed -> { split(flushTail = true); break }
                    else -> { pending.write(chunk.getOrThrow()); split(flushTail = false) }
                }
            }
        } finally {
            reader.cancel()
            runCatching { stream.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
        return frames
    }

    companion object {
        /** A gap this long in the data means the encoder finished writing the current frame. */
        private const val PAUSE_MS = 40L

        private fun startCodeLength(b: ByteArray) = if (b.size > 3 && b[2].toInt() == 0) 4 else 3

        /** Offsets of 00 00 01 / 00 00 00 01 start codes (pointing at the first 00). */
        private fun startCodes(b: ByteArray): List<Int> {
            val out = ArrayList<Int>()
            var i = 0
            while (i + 2 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                    if (b[i + 2].toInt() == 1) { out += if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i; i += 3; continue }
                }
                i++
            }
            return out
        }
    }
}
