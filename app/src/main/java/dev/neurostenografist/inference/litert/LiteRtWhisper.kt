package dev.neurostenografist.inference.litert

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.neurostenografist.inference.HardwareAcceleration
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Base64
import org.json.JSONObject

/** Published multilingual Whisper tiny graph pair. The encoder uses LiteRT and the decoder ORT CPU. */
internal class LiteRtWhisper(
    dir: File,
    tokensFile: File,
    private val checkCancellation: () -> Unit,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val mel = WhisperLogMel(File(dir, "mel_filters.bin").readLittleEndianFloats(80 * 201))
    private val vocab = JSONObject(File(dir, "vocab.json").readText())
    private val tokens = tokensFile.useLines { lines ->
        lines.map { it.substringBeforeLast(' ') }.map { if (it == "=") byteArrayOf() else Base64.getDecoder().decode(it) }.toList()
    }
    private val encoder = GpuGraph(File(dir, "whisper_encoder.tflite"), listOf("args_0"), "output_0")
    private val decoder = try {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(HardwareAcceleration.cpuThreads())
            env.createSession(File(dir, "whisper_decoder.onnx").absolutePath, options)
        }
    } catch (failure: Throwable) {
        encoder.close()
        throw failure
    }

    fun transcribe(samples: FloatArray, language: String): String {
        val langToken = when (language) {
            "en" -> 50259L
            "ru" -> 50263L
            else -> error("Unsupported Whisper language: $language")
        }
        return withEncodedAudio(samples) { features ->
            val sequence = mutableListOf(START, langToken, 50359L, 50363L)
            val text = ByteArrayOutputStream()
            repeat(224) {
                checkCancellation()
                val next = predict(features, sequence.toLongArray(), 0..END.toInt())
                if (next == END.toInt()) return@withEncodedAudio text.toString(Charsets.UTF_8.name()).trim()
                text.write(tokens[next])
                sequence += next.toLong()
            }
            error("Whisper LiteRT decoder did not reach the end token")
        }
    }

    fun detect(samples: FloatArray): String = withEncodedAudio(samples) { features ->
        vocab.getString(predict(features, longArrayOf(START), 50259..50357).toString())
    }

    private fun <T> withEncodedAudio(samples: FloatArray, operation: (OnnxTensor) -> T): T {
        checkCancellation()
        encoder.write("args_0", mel.compute(samples))
        val features = encoder.run()
        check(features.size == 1500 * 384 && features.all(Float::isFinite)) { "Invalid Whisper encoder output" }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, 1500, 384)).use(operation)
    }

    private fun predict(features: OnnxTensor, sequence: LongArray, allowed: IntRange): Int {
        val shape = longArrayOf(1, sequence.size.toLong())
        return OnnxTensor.createTensor(env, LongBuffer.wrap(sequence), shape).use { prompt ->
            decoder.run(mapOf("tokens" to prompt, "audio_features" to features)).use { output ->
                val logits = (output.get(0) as OnnxTensor).floatBuffer
                val last = (sequence.size - 1) * 51865
                var winner = allowed.first
                for (index in allowed) {
                    if (logits.get(last + index) > logits.get(last + winner)) winner = index
                }
                check(logits.get(last + winner).isFinite()) { "Invalid Whisper decoder output" }
                winner
            }
        }
    }

    override fun close() {
        try {
            encoder.close()
        } finally {
            decoder.close()
        }
    }

    private companion object {
        const val START = 50258L
        const val END = 50257L
    }
}
