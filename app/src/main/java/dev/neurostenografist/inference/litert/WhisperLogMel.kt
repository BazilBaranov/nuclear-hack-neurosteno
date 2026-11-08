package dev.neurostenografist.inference.litert

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Whisper's centered 400-point STFT, 160-sample hop and 80-bin log-mel features. */
internal class WhisperLogMel(private val filters: FloatArray) {
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE)).toFloat() }
    private val cosine = FloatArray(FFT_SIZE) { cos(2 * PI * it / FFT_SIZE).toFloat() }
    private val sine = FloatArray(FFT_SIZE) { -sin(2 * PI * it / FFT_SIZE).toFloat() }
    private val nonzero = Array(MELS) { mel -> (0 until BINS).filter { filters[mel * BINS + it] != 0f }.toIntArray() }

    fun compute(samples: FloatArray): FloatArray {
        require(samples.size <= FRAMES * HOP) { "Whisper expects at most 30 seconds" }
        val mel = FloatArray(MELS * FRAMES) { -10f }
        val frame = FloatArray(FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val power = FloatArray(BINS)
        val lastFrame = minOf(FRAMES, (samples.size + FFT_SIZE / 2 + HOP - 1) / HOP)
        var maxLog = -10f
        for (t in 0 until lastFrame) {
            for (i in frame.indices) {
                val position = t * HOP - FFT_SIZE / 2 + i
                val index = when {
                    position < 0 -> -position
                    position >= FRAMES * HOP -> 2 * (FRAMES * HOP - 1) - position
                    else -> position
                }
                frame[i] = (samples.getOrNull(index) ?: 0f) * window[i]
            }
            fft(frame, real, imag, 0, 1, FFT_SIZE, 0)
            for (bin in power.indices) power[bin] = real[bin] * real[bin] + imag[bin] * imag[bin]
            for (m in 0 until MELS) {
                var sum = 0f
                for (bin in nonzero[m]) sum += filters[m * BINS + bin] * power[bin]
                val value = log10(maxOf(sum, 1e-10f))
                mel[m * FRAMES + t] = value
                maxLog = maxOf(maxLog, value)
            }
        }
        for (i in mel.indices) mel[i] = (maxOf(mel[i], maxLog - 8f) + 4f) / 4f
        return mel
    }

    private fun fft(input: FloatArray, real: FloatArray, imag: FloatArray, start: Int, stride: Int, size: Int, out: Int) {
        val factor = FFT_SIZE / size
        if (size == 25) {
            for (k in 0 until size) {
                var re = 0f
                var im = 0f
                for (i in 0 until size) {
                    val angle = (i * k * factor) % FFT_SIZE
                    val value = input[start + i * stride]
                    re += value * cosine[angle]
                    im += value * sine[angle]
                }
                real[out + k] = re
                imag[out + k] = im
            }
            return
        }
        val half = size / 2
        fft(input, real, imag, start, stride * 2, half, out)
        fft(input, real, imag, start + stride, stride * 2, half, out + half)
        for (k in 0 until half) {
            val even = out + k
            val odd = even + half
            val c = cosine[k * factor]
            val s = sine[k * factor]
            val re = real[odd] * c - imag[odd] * s
            val im = real[odd] * s + imag[odd] * c
            real[odd] = real[even] - re
            imag[odd] = imag[even] - im
            real[even] += re
            imag[even] += im
        }
    }

    private companion object {
        const val FFT_SIZE = 400
        const val HOP = 160
        const val BINS = FFT_SIZE / 2 + 1
        const val FRAMES = 3000
        const val MELS = 80
    }
}
