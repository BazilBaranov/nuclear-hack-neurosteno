package dev.neurostenografist.inference.litert

import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.inference.litert.nemotron.Engine
import dev.neurostenografist.inference.litert.nemotron.MelFrontend
import dev.neurostenografist.inference.litert.nemotron.Nemotron3Diarizer
import dev.neurostenografist.inference.litert.nemotron.StreamConfig
import java.io.File

internal fun diarizeNemotronLiteRt(
    dir: File,
    samples: FloatArray,
    checkCancellation: () -> Unit,
): List<SpeakerInterval> {
    val mel = MelFrontend(
        File(dir, "frontend_mel128_257.bin").readLittleEndianFloats(128 * 257),
        File(dir, "hann400.bin").readLittleEndianFloats(400),
    )
    val silence = File(dir, "silence_embeds.bin").readLittleEndianFloats(512)
    GpuGraph(File(dir, "nemotron3_diar_frontend.tflite"), listOf("mel"), "chunk_embeds").use { a ->
        GpuGraph(
            File(dir, "nemotron3_diar_encoder_offline_fp16.tflite"),
            listOf("packed_embeds", "attn_bias", "rope_cos", "rope_sin"), "logits",
        ).use { b ->
            val engine = object : Engine {
                private var ropeWritten = false

                override fun frontend(mel: FloatArray): FloatArray {
                    checkCancellation()
                    a.write("mel", mel)
                    return a.run()
                }

                override fun encoder(
                    packed: FloatArray, bias: FloatArray, cos: FloatArray, sin: FloatArray,
                ): FloatArray {
                    checkCancellation()
                    if (!ropeWritten) {
                        b.write("rope_cos", cos)
                        b.write("rope_sin", sin)
                        ropeWritten = true
                    }
                    b.write("packed_embeds", packed)
                    b.write("attn_bias", bias)
                    return b.run().also { check(it.all(Float::isFinite)) { "LiteRT produced non-finite speaker logits" } }
                }

                override fun close() = Unit
            }
            val runner = Nemotron3Diarizer(engine, mel, silence, StreamConfig.OFFLINE)
            val frames = samples.size / 160
            val logits = FloatArray((frames + 1) * Nemotron3Diarizer.NUM_SPEAKERS)
            for (step in runner.runFile(samples)) {
                step.logits.copyInto(logits, step.firstFrame * Nemotron3Diarizer.NUM_SPEAKERS)
            }
            return Nemotron3Diarizer.segments(logits, frames).map { (start, end, speaker) ->
                SpeakerInterval(speaker, start / 100.0, end / 100.0)
            }
        }
    }
}
