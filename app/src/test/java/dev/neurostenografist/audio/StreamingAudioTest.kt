package dev.neurostenografist.audio

import dev.neurostenografist.audio.decoder.StreamingPcm16k
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin

class StreamingAudioTest {
    @Test
    fun streamingResamplingMatchesHandCalculatedStereoRamp() {
        val cases = mapOf(
            8_000 to floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f, 4.5f, 5f, 5.5f, 6f, 6.5f, 7f, 7.5f, 8f, 8f),
            16_000 to floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            44_100 to floatArrayOf(0f, 2.75625f, 5.5125f),
            48_000 to floatArrayOf(1f, 4f, 7f),
        )
        val stereo = FloatArray(18) { if (it % 2 == 0) it.toFloat() else 0f }
        for ((rate, expected) in cases) {
            val decoder = StreamingPcm16k(rate, 2)
            for (offset in stereo.indices step 3) decoder.append(stereo, offset, 3)
            assertArrayEquals(expected, decoder.finish(), 1e-6f)
        }
    }

    @Test
    fun arbitraryChunkBoundariesPreserveStereoAndResampling() {
        for (rate in listOf(8_000, 16_000, 44_100, 48_000)) {
            val stereo = FloatArray(4_413) { sin(it * 0.07).toFloat() }
            val mono = FloatArray(stereo.size / 2) { (stereo[it * 2] + stereo[it * 2 + 1]) / 2 }
            val streamed = StreamingPcm16k(rate, 2)
            for (offset in stereo.indices step 37) {
                streamed.append(stereo, offset, minOf(37, stereo.size - offset))
            }
            assertArrayEquals(AudioDecoder.resampleTo16k(mono, rate), streamed.finish(), 1e-6f)
        }
    }

    @Test
    fun limitRejectsBeforeOversizedOutputAllocation() {
        val decoder = StreamingPcm16k(16_000, 1, maxSamples = 4)
        decoder.append(floatArrayOf(1f, 2f, 3f, 4f))
        assertThrows(IOException::class.java) { decoder.append(floatArrayOf(5f)) }
    }

    @Test
    fun nonFiniteInputRejectedRatherThanPoisoningModels() {
        assertThrows(IOException::class.java) {
            StreamingPcm16k(16_000, 1).append(floatArrayOf(Float.NaN))
        }
    }

    @Test
    fun emptyAndPartialFrameRemainEmpty() {
        assertEquals(0, StreamingPcm16k(44_100, 1).finish().size)
        val stereo = StreamingPcm16k(16_000, 2)
        stereo.append(floatArrayOf(0.5f))
        assertEquals(0, stereo.finish().size)
    }

    @Test
    fun preprocessingPreservesTimelineAndQuietShortSpeech() {
        val audio = FloatArray(3_200) { if (it in 800 until 1_600) (0.0002 * sin(2 * PI * 300 * it / 16_000)).toFloat() else 0f }
        val beforeSize = audio.size
        AudioPreprocessor.processInPlace(audio)
        assertEquals(beforeSize, audio.size)
        assertTrue(audio.take(800).all { it == 0f })
        assertTrue(audio.sliceArray(800 until 1_600).maxOf { kotlin.math.abs(it) } > 0.0001f)
    }

    @Test
    fun preprocessingReducesDcAndLowFrequencyRumble() {
        val audio = FloatArray(16_000) { (0.2 + 0.1 * sin(2 * PI * 20 * it / 16_000)).toFloat() }
        AudioPreprocessor.processInPlace(audio)
        val tail = audio.sliceArray(8_000 until 16_000)
        assertTrue(kotlin.math.abs(tail.average()) < 0.001)
        assertTrue(tail.maxOf { kotlin.math.abs(it) } < 0.05f)
    }
}
