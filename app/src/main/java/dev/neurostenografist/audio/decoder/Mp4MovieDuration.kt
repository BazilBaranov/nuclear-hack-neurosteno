package dev.neurostenografist.audio.decoder

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Only ftyp -> moov -> mvhd; caller must establish that this is single-track audio. */
internal fun readMp4MovieDurationUs(input: InputStream): Long? = try {
    Mp4MovieHeader(input).read()
} catch (_: IOException) {
    null // Optional metadata: malformed/unsupported headers fall back to MediaExtractor.
}

private class Mp4MovieHeader(input: InputStream) {
    private val stream = DataInputStream(input)
    private var boxesLeft = 4096
    private var skipCallsLeft = 4096
    private var fallbackReadBytes = 0L
    private val scratch = ByteArray(4096)
    private data class Box(val type: Int, val size: Long, val payload: Long)

    fun read(): Long? {
        var remaining = 8L * 1024 * 1024 * 1024 // Bound logical offsets, including skipped mdat.
        val first = header(remaining) ?: return null
        if (first.type != FTYP || !skip(first.payload)) return null
        remaining -= first.size
        while (remaining >= 8) {
            val box = header(remaining) ?: return null
            if (box.type == MOOV) return movie(box.payload)
            if (!skip(box.payload)) return null
            remaining -= box.size
        }
        return null
    }

    private fun movie(size: Long): Long? {
        var remaining = size
        while (remaining >= 8) {
            val box = header(remaining) ?: return null
            if (box.type == MVHD) return duration(box.payload)
            if (!skip(box.payload)) return null
            remaining -= box.size
        }
        return null
    }

    private fun header(remaining: Long): Box? {
        if (--boxesLeft < 0 || remaining < 8) return null
        var size = stream.readInt().toLong() and 0xffff_ffffL
        val type = stream.readInt()
        var headerSize = 8L
        if (size == 1L) {
            if (remaining < 16) return null
            size = stream.readLong() // Reject unsigned values outside signed offset range.
            headerSize = 16
        }
        if (size < headerSize || size > remaining) return null // Includes open-ended size=0.
        return Box(type, size, size - headerSize)
    }

    private fun duration(payload: Long): Long? {
        if (payload < 20) return null
        val version = stream.readInt() ushr 24
        val scale: Long
        val ticks: Long
        when (version) {
            0 -> {
                stream.readLong() // creation + modification timestamps
                scale = stream.readInt().toLong() and 0xffff_ffffL
                ticks = stream.readInt().toLong() and 0xffff_ffffL
                if (ticks == 0xffff_ffffL) return null // ISO BMFF unknown duration
            }
            1 -> {
                if (payload < 32) return null
                stream.readLong()
                stream.readLong()
                scale = stream.readInt().toLong() and 0xffff_ffffL
                ticks = stream.readLong()
            }
            else -> return null
        }
        if (scale == 0L || ticks <= 0) return null
        val seconds = ticks / scale
        if (seconds > Long.MAX_VALUE / 1_000_000) return null
        val micros = seconds * 1_000_000
        val fraction = (ticks % scale) * 1_000_000 / scale // scale is uint32: no overflow.
        if (micros > Long.MAX_VALUE - fraction) return null
        return micros + fraction
    }

    private fun skip(bytes: Long): Boolean {
        var left = bytes
        while (left > 0) {
            if (--skipCallsLeft < 0) return false
            val skipped = stream.skip(left)
            if (skipped in 1..left) {
                left -= skipped
            } else {
                // File/provider streams normally seek; do not read a huge mdat merely
                // to inspect optional metadata when a provider cannot skip efficiently.
                if (fallbackReadBytes >= 8L * 1024 * 1024) return false
                val count = stream.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (count <= 0) return false
                left -= count
                fallbackReadBytes += count
            }
        }
        return true
    }

    companion object {
        private const val FTYP = 0x66747970
        private const val MOOV = 0x6d6f6f76
        private const val MVHD = 0x6d766864
    }
}
