package dev.neurostenografist.audio

import java.io.IOException
import kotlin.math.PI

/** Removes DC and sub-speech rumble without a noise gate, gain boost or time compression. */
object AudioPreprocessor {
    fun processInPlace(samples: FloatArray) {
        val rc = 1.0 / (2.0 * PI * 60.0)
        val alpha = (rc / (rc + 1.0 / AudioDecoder.TARGET_SAMPLE_RATE)).toFloat()
        var previousInput = 0f
        var previousOutput = 0f
        for (i in samples.indices) {
            val input = samples[i]
            if (!input.isFinite()) throw IOException("Аудио содержит некорректные значения PCM")
            val output = alpha * (previousOutput + input - previousInput)
            samples[i] = output
            previousInput = input
            previousOutput = output
        }
    }
}
