package dev.neurostenografist

import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.audio.UnsupportedAudioException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.FilterInputStream

class AudioDecoderTest {
    private fun wavBytes(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        audioFormat: Int = 1,
        frames: IntArray,
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val dataSize = frames.size * channels * bytesPerSample
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF")
            writeIntLE(36 + dataSize)
            writeBytes("WAVE")
            writeBytes("fmt ")
            writeIntLE(16)
            writeShortLE(audioFormat)
            writeShortLE(channels)
            writeIntLE(sampleRate)
            writeIntLE(sampleRate * channels * bytesPerSample)
            writeShortLE(channels * bytesPerSample)
            writeShortLE(bitsPerSample)
            writeBytes("data")
            writeIntLE(dataSize)
            for (frame in frames) {
                repeat(channels) {
                    writeShortLE(frame)
                    repeat((bytesPerSample - 2).coerceAtLeast(0)) { write(0) }
                }
            }
        }
        return out.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    /** PCM16-only builder with genuinely distinct interleaved left/right channels. */
    private fun stereoWav(
        sampleRate: Int,
        left: IntArray,
        right: IntArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF")
            writeIntLE(36 + left.size * 2 * 2)
            writeBytes("WAVE")
            writeBytes("fmt ")
            writeIntLE(16)
            writeShortLE(1) // PCM
            writeShortLE(2) // stereo
            writeIntLE(sampleRate)
            writeIntLE(sampleRate * 4)
            writeShortLE(4)
            writeShortLE(16)
            writeBytes("data")
            writeIntLE(left.size * 2 * 2)
            for (i in left.indices) {
                writeShortLE(left[i])
                writeShortLE(right[i])
            }
        }
        return out.toByteArray()
    }

    /** WAVEFORMATEXTENSIBLE (format 0xFFFE, 40-byte fmt): tests the GUID gate. */
    private fun extensibleWav(
        sampleRate: Int,
        frames: IntArray,
        subformatGuid: ByteArray,
        fmtSize: Int = 40,
    ): ByteArray {
        val dataSize = frames.size * 2
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF")
            writeIntLE(36 + dataSize)
            writeBytes("WAVE")
            writeBytes("fmt ")
            writeIntLE(fmtSize)
            writeShortLE(0xFFFE)
            writeShortLE(1) // mono
            writeIntLE(sampleRate)
            writeIntLE(sampleRate * 2)
            writeShortLE(2)
            writeShortLE(16)
            writeShortLE(22) // cbSize
            writeShortLE(16) // validBits
            writeIntLE(0) // channel mask
            write(subformatGuid)
            writeBytes("data")
            writeIntLE(dataSize)
            for (frame in frames) writeShortLE(frame)
        }
        return out.toByteArray()
    }

    private val pcmSubformatGuid = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xaa.toByte(), 0x00, 0x38, 0x9b.toByte(), 0x71,
    )

    private val floatSubformatGuid = byteArrayOf(
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xaa.toByte(), 0x00, 0x38, 0x9b.toByte(), 0x71,
    )

    private fun decode(bytes: ByteArray): FloatArray = AudioDecoder.decodeWavTo16k(ByteArrayInputStream(bytes))

    @Test
    fun decodes48kHzMonoSecondTo16000Samples() {
        val frames = IntArray(48_000) { if (it % 2 == 0) 16_000 else -16_000 }
        val out = decode(wavBytes(48_000, 1, 16, frames = frames))
        assertEquals(16_000, out.size)
        // Three alternating samples average to +/- one third of the input amplitude.
        assertTrue(out[0] > 0.0f)
        assertTrue(out[1] < 0.0f)
        assertEquals(16_000f / (3 * 32_768f), out[0], 0.001f)
    }

    @Test
    fun sixteenKhzPassesThroughUnchanged() {
        val frames = IntArray(16_000) { it % 1000 }
        val out = decode(wavBytes(16_000, 1, 16, frames = frames))
        assertEquals(16_000, out.size)
        for (i in listOf(0, 1, 999, 5000)) {
            assertEquals(frames[i] / 32_768f, out[i], 1e-6f)
        }
    }

    @Test
    fun stereoChannelsAreAveragedNotAssumedMono() {
        // Constant frames: L == R, average must equal the channel value, not twice it.
        val frames = IntArray(48_000) { 6_000 }
        val out = decode(wavBytes(48_000, 2, 16, frames = frames))
        assertEquals(16_000, out.size)
        assertEquals(6_000f / 32_768f, out[123], 1e-6f)
    }

    @Test
    fun unequalStereoChannelsAverageNotSelect() {
        // L=+amp, R=-amp: a mono-of-one-channel or interleaved-as-mono bug shows up as +/- amp.
        val left = IntArray(48_000) { 12_000 }
        val right = IntArray(48_000) { -12_000 }
        val out = decode(stereoWav(48_000, left, right))
        assertEquals(16_000, out.size)
        assertEquals(0.0f, out[0], 1e-6f)
        assertEquals(0.0f, out[7_999], 1e-6f)
        assertEquals(0.0f, out[15_999], 1e-6f)
    }

    @Test
    fun leadingSilencePreservedOnOriginalTimeline() {
        // 0.5 s of silence then a signal at 48 kHz: silence comes out first, unchanged.
        val frames = IntArray(48_000) { if (it < 24_000) 0 else 20_000 }
        val out = decode(wavBytes(48_000, 1, 16, frames = frames))
        assertEquals(16_000, out.size)
        for (i in intArrayOf(0, 1_000, 7_999)) assertEquals(0.0f, out[i], 0f)
        assertTrue(out[8_000 + 2] > 0.0f)
    }

    @Test
    fun silenceDecodesToZeros() {
        val out = decode(wavBytes(16_000, 1, 16, frames = IntArray(8_000)))
        assertEquals(8_000, out.size)
        assertTrue(out.all { it == 0.0f })
    }

    @Test(expected = UnsupportedAudioException::class)
    fun unsupportedBitDepthFailsExplicitly() {
        decode(wavBytes(16_000, 1, 24, frames = IntArray(100)))
    }

    @Test(expected = UnsupportedAudioException::class)
    fun nonPcmFormatFailsExplicitly() {
        decode(wavBytes(16_000, 1, 16, audioFormat = 3, frames = IntArray(100)))
    }

    @Test
    fun extensiblePcmSubformatDecodesLikeCanonicalPcm() {
        val frames = IntArray(8_000) { if (it % 2 == 0) 16_000 else -16_000 }
        assertEquals(
            decode(wavBytes(16_000, 1, 16, frames = frames)).toList(),
            decode(extensibleWav(16_000, frames, pcmSubformatGuid)).toList(),
        )
    }

    @Test
    fun extensibleNonPcmSubformatFailsExplicitly() {
        // IEEE-float subformat GUID behind a 0xFFFE header must not be decoded as PCM.
        try {
            decode(extensibleWav(16_000, IntArray(100), floatSubformatGuid))
            fail("expected UnsupportedAudioException")
        } catch (expected: UnsupportedAudioException) {
            assertTrue(expected.message!!.contains("subformat GUID"))
        }
    }

    @Test
    fun extensibleFmtShorterThanFortyBytesFailsExplicitly() {
        try {
            decode(extensibleWav(16_000, IntArray(100), pcmSubformatGuid, fmtSize = 24))
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("need 40"))
        }
    }

    @Test(expected = IOException::class)
    fun corruptHeaderFailsExplicitly() {
        decode(ByteArray(64) { 0x55 })
    }

    @Test(expected = IOException::class)
    fun truncatedFileFailsExplicitly() {
        val full = wavBytes(16_000, 1, 16, frames = IntArray(1_000))
        decode(full.copyOf(40))
    }

    @Test(expected = IOException::class)
    fun emptyInputStreamFailsExplicitly() {
        decode(ByteArray(0))
    }

    @Test(expected = IOException::class)
    fun oversizedDataChunkFailsExplicitly() {
        // data chunk size field sits at offset 40; declares 0x4000_0000 bytes against a tiny file.
        val full = wavBytes(16_000, 1, 16, frames = IntArray(100))
        full[40] = 0
        full[41] = 0
        full[42] = 0
        full[43] = 0x40
        decode(full)
    }

    @Test(expected = IOException::class)
    fun corruptedRiffSizeOnMetadataChunkFailsExplicitly() {
        // A "wave" marker chunk before fmt declares 0x8000_0000 (unsigned 2147483648) bytes.
        val full = wavBytes(16_000, 1, 16, frames = IntArray(100))
        val patched = ByteArray(full.size + 16)
        System.arraycopy(full, 0, patched, 0, full.size)
        System.arraycopy(full, 12, patched, 12 + 16, full.size - 12)
        patched[12] = 'w'.code.toByte()
        patched[13] = 'v'.code.toByte()
        patched[14] = 'l'.code.toByte()
        patched[15] = 't'.code.toByte()
        patched[16] = 0x00
        patched[17] = 0x00
        patched[18] = 0x00
        patched[19] = 0x80.toByte()
        decode(patched)
    }

    @Test
    fun oddSizedMetadataChunksSkipTheirPadByte() {
        // Insert an odd-sized chunk (3 bytes + 1 pad) before fmt/data; decode stays correct.
        val full = wavBytes(16_000, 1, 16, frames = IntArray(100) { 1_000 })
        val tail = full.copyOfRange(12, full.size)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF")
            writeIntLE(full.size - 12 + 4 + 3 + 1)
            writeBytes("WAVE")
            writeBytes("wvlt")
            writeIntLE(3)
            write(byteArrayOf(1, 2, 3))
            write(0) // pad
            write(tail)
        }
        val decoded = decode(out.toByteArray())
        assertEquals(100, decoded.size)
        assertEquals(1_000 / 32_768f, decoded[50], 1e-6f)
    }

    @Test(expected = IOException::class)
    fun shortFmtChunkFailsExplicitly() {
        // fmt chunk declares only 8 bytes; a real fmt needs at least 16.
        val full = wavBytes(16_000, 1, 16, frames = IntArray(100))
        // fmt size field sits at offset 16.
        full[16] = 8
        full[17] = 0
        full[18] = 0
        full[19] = 0
        decode(full)
    }

    @Test(expected = IOException::class)
    fun missingFmtChunkFailsExplicitly() {
        // RIFF/WAVE with only a data chunk.
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF")
            writeIntLE(4 + 8 + 96)
            writeBytes("WAVE")
            writeBytes("data")
            writeIntLE(96)
            repeat(96) { write(0) }
        }
        decode(out.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun resampleRejectsInvalidSampleRate() {
        AudioDecoder.resampleTo16k(FloatArray(16_000), 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resampleRejectsNegativeSampleRate() {
        // -32000 % 16000 == 0 saw the old divide path; must not produce nonsense.
        AudioDecoder.resampleTo16k(FloatArray(1_000), -32_000)
    }

    @Test
    fun resampleEmptyInputStaysEmpty() {
        assertEquals(0, AudioDecoder.resampleTo16k(FloatArray(0), 16_000).size)
        assertEquals(0, AudioDecoder.resampleTo16k(FloatArray(0), 48_000).size)
        assertEquals(0, AudioDecoder.resampleTo16k(FloatArray(0), 44_100).size)
    }

    @Test
    fun resample44k1SecondStableDuration() {
        val out = AudioDecoder.resampleTo16k(FloatArray(44_100), 44_100)
        assertEquals(16_000, out.size)
    }

    @Test
    fun resampleShort44k1InputRoundsNearest() {
        // 100 samples at 44.1 kHz = 36.28 16 kHz-frames; nearest is 36.
        val ramp = FloatArray(100) { it / 99f }
        val out = AudioDecoder.resampleTo16k(ramp, 44_100)
        assertEquals(36, out.size)
        // Ramps stay increasing: no duplicate or reversed samples.
        for (i in 1 until out.size) assertTrue("out[$i] must exceed out[${i - 1}]", out[i] > out[i - 1])
    }

    @Test
    fun truncatedLastPartialFrameIsDroppedNotFatal() {
        // data chunk declares 10 bytes: 2 complete stereo frames plus 1 stray channel sample.
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RIFF")
            writeIntLE(36 + 10)
            writeBytes("WAVE")
            writeBytes("fmt ")
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(2)
            writeIntLE(16_000)
            writeIntLE(16_000 * 4)
            writeShortLE(4)
            writeShortLE(16)
            writeBytes("data")
            writeIntLE(10)
            for (s in intArrayOf(1_000, -1_000, 1_000, -1_000, 1_000)) writeShortLE(s)
        }
        // Both complete stereo frames average to zero; the stray 5th sample is dropped.
        val decoded = decode(out.toByteArray())
        assertEquals(2, decoded.size)
        assertEquals(0.0f, decoded[0], 1e-7f)
        assertEquals(0.0f, decoded[1], 1e-7f)
    }

    @Test
    fun streamingWavSupportsOneByteReads() {
        val bytes = wavBytes(44_100, 2, 16, frames = IntArray(10_001) { it % 16_000 })
        val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertEquals(decode(bytes).toList(), AudioDecoder.decodeWavTo16k(input).toList())
    }

    @Test
    fun declaredHugeAudioRejectedBeforeReadingPayload() {
        val header = wavBytes(16_000, 1, 16, frames = IntArray(0))
        // A claimed two-hour input must fail based on its header, before allocation/read.
        val size = 16_000 * 2 * 7_200
        for (i in 0..3) header[40 + i] = (size ushr (8 * i)).toByte()
        val input = object : ByteArrayInputStream(header) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (available() == 0) throw AssertionError("oversized payload must not be read")
                return super.read(b, off, len)
            }
        }
        try {
            AudioDecoder.decodeWavTo16k(input)
            fail("expected a duration limit failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("30"))
        }
    }
}
