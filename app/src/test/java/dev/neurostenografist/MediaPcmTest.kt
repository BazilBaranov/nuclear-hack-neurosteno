package dev.neurostenografist

import android.media.AudioFormat
import dev.neurostenografist.audio.UnsupportedAudioException
import dev.neurostenografist.audio.decoder.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Deterministic little-endian PCM conversion tests for the MediaCodec decode path.
 * These run on the JVM because they exercise no Android runtime, only byte math.
 */
class MediaPcmTest {
    @Test
    fun pcm16LittleEndianDecodesInByteOrder() {
        // 0x0201 = 513; a big-endian read of the same bytes gives 0x0102 = 258.
        val out = Pcm.pcm16Le(byteArrayOf(1, 2), 0, 2)
        assertEquals(513 / 32_768f, out[0], 1e-7f)
    }

    @Test
    fun pcm16FullScaleDecodesWithoutWrap() {
        // 0x7FFF clamps at 32767/32768; 0x8000 is exactly -1.0, not a wrap to a positive value.
        val out = Pcm.pcm16Le(byteArrayOf(0xff.toByte(), 0x7f, 0, 0x80.toByte()), 0, 4)
        assertEquals(32_767 / 32_768f, out[0], 1e-7f)
        assertEquals(-1.0f, out[1], 1e-7f)
    }

    @Test
    fun pcm16NegativeValuesSignExtend() {
        // 0xFFF7 = -9 as a signed 16 bit little-endian value.
        val out = Pcm.pcm16Le(byteArrayOf(0xf7.toByte(), 0xff.toByte()), 0, 2)
        assertEquals(-9 / 32_768f, out[0], 1e-7f)
    }

    @Test
    fun pcm16OddTailIsDropped() {
        // 5 bytes = 2 complete samples + 1 stray byte; the stray byte is dropped, not read.
        val out = Pcm.pcm16Le(byteArrayOf(4, 0, 8, 0, 0x55), 0, 5)
        assertEquals(2, out.size)
        assertEquals(4 / 32_768f, out[0], 1e-7f)
    }

    @Test
    fun floatPcmLittleEndianDecodes() {
        // 0.5f little-endian bit pattern.
        val bits = java.lang.Float.floatToIntBits(0.5f)
        val bytes =
            byteArrayOf(
                (bits and 0xff).toByte(),
                ((bits shr 8) and 0xff).toByte(),
                ((bits shr 16) and 0xff).toByte(),
                ((bits shr 24) and 0xff).toByte(),
            )
        assertEquals(0.5f, Pcm.pcmFloatLe(bytes, 0, 4)[0], 1e-7f)
    }

    @Test
    fun floatPcmKeepsAmplitudesBeyondUnity() {
        // 2.0f must survive as 2.0f; converting through shorts would wrap it to garbage.
        val bits = java.lang.Float.floatToIntBits(2.0f)
        val bytes =
            byteArrayOf(
                (bits and 0xff).toByte(),
                ((bits shr 8) and 0xff).toByte(),
                ((bits shr 16) and 0xff).toByte(),
                ((bits shr 24) and 0xff).toByte(),
            )
        assertEquals(2.0f, Pcm.pcmFloatLe(bytes, 0, 4)[0], 1e-7f)
    }

    @Test
    fun floatPcmRequiresWordAlignedLength() {
        assertThrows(IllegalArgumentException::class.java) {
            Pcm.pcmFloatLe(byteArrayOf(1, 2, 3, 4, 5), 0, 5)
        }
    }

    @Test
    fun stereoDownmixAveragesTrueChannelPairs() {
        // Interleaved [L,R,L,R] with unequal channels: average is zero, not L, not R, not L+R.
        val interleaved =
            Pcm.pcm16Le(
                byteArrayOf(
                    0x10.toByte(),
                    0x27, // L = 10_000
                    0xf0.toByte(),
                    0xd8.toByte(), // R = -10_000
                    0xe8.toByte(),
                    0x03, // L = 1_000
                    0xfc.toByte(),
                    0xff.toByte(), // R = -4
                ),
                0,
                8,
            )
        assertEquals(0.0f, Pcm.toMono(interleaved, 2)[0], 1e-7f)
        assertEquals(1_000f / 32_768f / 2 - 4f / 32_768f / 2, Pcm.toMono(interleaved, 2)[1], 1e-6f)
    }

    @Test
    fun monoDownmixPassesThrough() {
        val samples = floatArrayOf(0.25f, -0.5f)
        val out = Pcm.toMono(samples, 1)
        assertEquals(0.25f, out[0], 1e-7f)
        assertEquals(-0.5f, out[1], 1e-7f)
    }

    @Test
    fun unsupportedPcmEncodingFailsExplicitly() {
        assertThrows(UnsupportedAudioException::class.java) { Pcm.requireSupported(AudioFormat.ENCODING_PCM_8BIT) }
        assertThrows(UnsupportedAudioException::class.java) { Pcm.requireSupported(999) }
        Pcm.requireSupported(AudioFormat.ENCODING_PCM_16BIT)
        Pcm.requireSupported(AudioFormat.ENCODING_PCM_FLOAT)
    }
}
