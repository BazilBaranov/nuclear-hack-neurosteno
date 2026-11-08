package dev.neurostenografist.audio

import dev.neurostenografist.inference.enhanceStreamInPlace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class SpeechEnhancementTest {
    @Test
    fun delayedStreamingOutputAndFlushKeepExactTimeline() {
        val input = FloatArray(32_001) { it / 40_000f }
        val expected = input.copyOf()
        var pending = FloatArray(0)
        enhanceStreamInPlace(input, run = { chunk ->
            val old = pending
            pending = chunk
            old
        }, flush = { pending })
        assertArrayEquals(expected, input, 0f)
    }

    @Test
    fun shorterOutputIsAnErrorNotSilentTimelineShift() {
        assertThrows(IllegalStateException::class.java) {
            enhanceStreamInPlace(FloatArray(8), run = { FloatArray(0) }, flush = { FloatArray(0) })
        }
    }

    @Test
    fun cancellationStopsBeforeModelCall() {
        assertThrows(CancellationException::class.java) {
            enhanceStreamInPlace(FloatArray(8), run = { error("must not run") }, flush = { error("must not flush") }, isCancelled = { true })
        }
    }

    @Test
    fun invalidNativeOutputIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            enhanceStreamInPlace(FloatArray(8), run = { FloatArray(8) { Float.NaN } }, flush = { FloatArray(0) })
        }
    }
}
