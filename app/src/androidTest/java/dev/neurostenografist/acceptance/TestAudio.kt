package dev.neurostenografist.acceptance

import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic synthetic WAV builder for instrumentation acceptance probes.
 * NOT the delivered corpus: these clips exist so the acceptance tests do not
 * depend on committed binary fixtures staying byte-identical; amplitude and
 * frequency encodings are simple so the fake-free real pipeline can be probed
 * end-to-end on a real device.
 */
object TestAudio {
    const val SAMPLE_RATE = 16_000

    /** RU labelled tone (1 s) the fake-free pipeline sees as our two speakers. */
    const val TONE_RU = 140.0
    const val TONE_EN = 1000.0
    const val TONE_UNSUPPORTED = 4000.0

    /** Writes 16 kHz mono 16-bit PCM WAV. Returns the file. */
    fun write(
        dir: File,
        name: String,
        samples: ShortArray,
    ): File {
        val out = File(dir, name)
        FileOutputStream(out).use { stream ->
            val data = ByteArray(44 + samples.size * 2)
            val tag = "RIFF".toCharArray()
            data[0] = tag[0].code.toByte()
            data[1] = tag[1].code.toByte()
            data[2] = tag[2].code.toByte()
            data[3] = tag[3].code.toByte()
            putInt(data, 4, 36 + samples.size * 2)
            "WAVE".toByteArray().copyInto(data, 8)
            "fmt ".toByteArray().copyInto(data, 12)
            putInt(data, 16, 16)
            data[20] = 1 // PCM
            data[22] = 1 // mono
            putInt(data, 24, SAMPLE_RATE)
            putInt(data, 28, SAMPLE_RATE * 2)
            putInt(data, 32, 2) // blockAlign: one 16-bit frame
            data[34] = 16 // bits per sample
            "data".toByteArray().copyInto(data, 36)
            putInt(data, 40, samples.size * 2)
            for (i in samples.indices) {
                data[44 + i * 2] = (samples[i].toInt() and 0xFF).toByte()
                data[45 + i * 2] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
            }
            stream.write(data)
        }
        return out
    }

    fun silence(durationMs: Long): ShortArray = ShortArray((SAMPLE_RATE * durationMs / 1000L).toInt())

    fun sine(
        freqHz: Double,
        durationMs: Long,
        amplitude: Double = 0.4,
    ): ShortArray {
        val n = (SAMPLE_RATE * durationMs / 1000L).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = amplitude * sin(2.0 * PI * freqHz * i / SAMPLE_RATE)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    fun concatenated(vararg parts: ShortArray): ShortArray = parts.reduce { acc, part -> acc + part }

    private fun putInt(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
