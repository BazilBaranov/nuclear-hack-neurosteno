package dev.neurostenografist.audio.decoder

import java.io.IOException
import kotlin.math.roundToLong

/** Downmixes and resamples with state spanning input buffers; only 16 kHz output is retained. */
internal class StreamingPcm16k(
    private val sourceRate: Int,
    private val channels: Int,
    private val maxSamples: Int = audioSampleLimit(),
) {
    init {
        require(sourceRate in 1..384_000) { "Некорректная частота аудио: $sourceRate Гц" }
        require(channels in 1..32) { "Некорректное число каналов: $channels" }
        require(maxSamples > 0)
    }

    private val blocks = ArrayList<FloatArray>()
    private val factor = if (sourceRate % RATE == 0) sourceRate / RATE else 0
    private val ratio = sourceRate.toDouble() / RATE
    private var channelSum = 0.0
    private var channelCount = 0
    private var groupSum = 0f
    private var groupCount = 0
    private var inputFrames = 0L
    private var outputSize = 0
    private var previous = 0f
    private var finished = false

    fun append(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        check(!finished)
        require(offset >= 0 && length >= 0 && offset <= samples.size - length)
        for (i in offset until offset + length) {
            val value = samples[i]
            if (!value.isFinite()) throw IOException("Аудио содержит некорректные значения PCM")
            channelSum += value
            if (++channelCount == channels) {
                appendMono((channelSum / channels).toFloat())
                channelCount = 0
                channelSum = 0.0
            }
        }
    }

    private fun appendMono(sample: Float) {
        if (factor > 0) {
            groupSum += sample
            if (++groupCount == factor) {
                emit(groupSum / factor)
                groupSum = 0f
                groupCount = 0
            }
        } else {
            while (outputSize * ratio <= inputFrames) {
                val position = outputSize * ratio
                val fraction = (position - (inputFrames - 1)).toFloat()
                emit(if (inputFrames == 0L) sample else previous + (sample - previous) * fraction)
            }
        }
        previous = sample
        inputFrames++
    }

    private fun emit(value: Float) {
        if (outputSize >= maxSamples) throw audioLimitException(maxSamples)
        if (!value.isFinite()) throw IOException("Аудио содержит некорректные значения PCM")
        if (outputSize % BLOCK == 0) blocks += FloatArray(minOf(BLOCK, maxSamples - outputSize))
        blocks[outputSize / BLOCK][outputSize % BLOCK] = value
        outputSize++
    }

    fun finish(): FloatArray {
        check(!finished)
        // Integer decimation keeps only complete groups, matching the WAV contract.
        val expected = if (factor > 0) inputFrames / factor else (inputFrames / ratio).roundToLong()
        if (expected > maxSamples) throw audioLimitException(maxSamples)
        while (outputSize < expected) emit(previous)
        val result = FloatArray(expected.toInt())
        var offset = 0
        for (block in blocks) {
            val count = minOf(block.size, result.size - offset)
            if (count <= 0) break
            block.copyInto(result, offset, 0, count)
            offset += count
        }
        blocks.clear()
        finished = true
        return result
    }

    companion object {
        const val RATE = 16_000
        private const val BLOCK = 16_384
        private const val MAX_SAMPLES = RATE * 60 * 30

        // shortcut: inference still consumes one FloatArray; use file-backed engines for longer recordings.
        fun audioSampleLimit(): Int = minOf(MAX_SAMPLES.toLong(), Runtime.getRuntime().maxMemory() / 16).coerceAtLeast(1).toInt()

        fun audioLimitException(limit: Int): IOException =
            IOException("Запись слишком длинная: максимум 30 минут, на этом устройстве доступно ${limit / RATE} с. Выберите более короткий фрагмент.")
    }
}
