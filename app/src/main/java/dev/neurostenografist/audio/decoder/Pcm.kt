package dev.neurostenografist.audio.decoder

import android.media.AudioFormat
import dev.neurostenografist.audio.UnsupportedAudioException

/**
 * Pure little-endian PCM conversion helpers shared by the WAV and MediaCodec decode
 * paths. MediaCodec PCM output (16-bit and float) is little-endian on all Android
 * platforms, while a MediaCodec output ByteBuffer's default order is big-endian, so
 * conversions must not rely on the buffer's own order.
 */
internal object Pcm {
    /** Signed 16-bit little-endian PCM to floats in [-1, 0.99997]. Drops an odd trailing byte. */
    fun pcm16Le(
        data: ByteArray,
        offset: Int,
        bytes: Int,
    ): FloatArray {
        val sampleCount = bytes / 2
        val out = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = data[offset + 2 * i].toInt() and 0xff
            val hi = data[offset + 2 * i + 1].toInt() and 0xff
            out[i] = ((lo or (hi shl 8)).toShort()).toInt() / 32_768f
        }
        return out
    }

    /** 32-bit little-endian float PCM; values beyond ±1 are preserved, never wrapped into shorts. */
    fun pcmFloatLe(
        data: ByteArray,
        offset: Int,
        bytes: Int,
    ): FloatArray {
        require(bytes % 4 == 0) { "float PCM payload of $bytes bytes is not word aligned" }
        val out = FloatArray(bytes / 4)
        for (i in out.indices) {
            val b = offset + 4 * i
            val bits =
                (data[b].toInt() and 0xff) or
                    ((data[b + 1].toInt() and 0xff) shl 8) or
                    ((data[b + 2].toInt() and 0xff) shl 16) or
                    ((data[b + 3].toInt() and 0xff) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }

    /** Moves unsupported decoder output encodings to an explicit actionable failure. */
    fun requireSupported(pcmEncoding: Int) {
        if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT && pcmEncoding != AudioFormat.ENCODING_PCM_FLOAT) {
            throw UnsupportedAudioException("unsupported MediaCodec PCM encoding: $pcmEncoding")
        }
    }

    /** Averages interleaved frames; a trailing partial frame (fewer than `channels` samples) is dropped. */
    fun toMono(
        interleaved: FloatArray,
        channels: Int,
    ): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            sum / channels
        }
    }
}
