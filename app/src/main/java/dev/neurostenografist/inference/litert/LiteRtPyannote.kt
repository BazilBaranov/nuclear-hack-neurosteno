package dev.neurostenografist.inference.litert

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.inference.HardwareAcceleration
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Pyannote 3.0 segmentation on CPU and the published WeSpeaker embedding graph on LiteRT GPU. */
internal fun diarizePyannoteLiteRt(
    context: Context,
    dir: File,
    samples: FloatArray,
    checkCancellation: () -> Unit,
): List<SpeakerInterval> {
    val fbank = WeSpeakerFbank(context)
    val env = OrtEnvironment.getEnvironment()
    val windows = mutableListOf<Window>()
    val centers = mutableListOf<FloatArray>()
    val windowCount = maxOf(1, 1 + (samples.size - WINDOW + HOP - 1) / HOP)
    GpuGraph(File(dir, "wespeaker_emb_fp16.tflite"), listOf("args_0"), "output_0").use { embedder ->
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(HardwareAcceleration.cpuThreads())
            env.createSession(File(dir, "pyannote_seg30.onnx").absolutePath, options)
        }.use { segmenter ->
            for (index in 0 until windowCount) {
                checkCancellation()
                val offset = index * HOP
                val length = minOf(WINDOW, samples.size - offset)
                val input = FloatArray(WINDOW)
                if (length > 0) samples.copyInto(input, 0, offset, offset + length)
                val active = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 1, WINDOW.toLong())).use { tensor ->
                    segmenter.run(mapOf(segmenter.inputNames.single() to tensor)).use { outputs ->
                        @Suppress("UNCHECKED_CAST")
                        val rows = (outputs.get(0).value as Array<Array<FloatArray>>).single()
                        check(rows.size == 589 && rows.all { it.size == 7 && it.all(Float::isFinite) })
                        IntArray(rows.size) { POWERS[rows[it].indices.maxBy { id -> rows[it][id] }] }
                    }
                }
                val clusterIds = IntArray(3) { -1 }
                for (speaker in 0 until 3) {
                    checkCancellation()
                    val mask = 1 shl speaker
                    if (active.none { it and mask != 0 }) continue
                    val solo = FloatArray(WE_SPEAKER_SAMPLES)
                    var filled = 0
                    // Prefer solo speech. For windows containing only overlapping speech, leave
                    // assignment to the original Pyannote/TitaNet pipeline.
                    for (frame in active.indices) {
                        if (active[frame] != mask) continue
                        val start = minOf(length, frame * WINDOW / active.size)
                        val end = minOf(length, (frame + 1) * WINDOW / active.size)
                        val count = minOf(end - start, solo.size - filled)
                        samples.copyInto(solo, filled, offset + start, offset + start + count)
                        filled += count
                        if (filled == solo.size) break
                    }
                    if (filled == 0) {
                        check(active.indices.none { active[it] and mask != 0 && it * WINDOW / active.size < length }) {
                            "No solo speech for a Pyannote speaker"
                        }
                        continue
                    }
                    for (i in filled until solo.size) solo[i] = solo[i % filled]
                    val features = fbank.compute(solo)
                    val flat = FloatArray(500 * 80)
                    for (i in features.indices) features[i].copyInto(flat, i * 80)
                    embedder.write("args_0", flat)
                    val embedding = embedder.run()
                    check(embedding.size == 256 && embedding.all(Float::isFinite))
                    clusterIds[speaker] = assignSpeaker(embedding, centers, clusterIds)
                }
                windows += Window(active, clusterIds)
            }
        }
    }
    return stitchWindows(windows, samples.size)
}

private data class Window(val activity: IntArray, val speakers: IntArray)

private fun assignSpeaker(embedding: FloatArray, centers: MutableList<FloatArray>, local: IntArray): Int {
    fun norm(x: FloatArray) = sqrt(x.fold(0.0) { sum, v -> sum + v * v }).coerceAtLeast(1e-9)
    val scale = norm(embedding)
    for (i in embedding.indices) embedding[i] = (embedding[i] / scale).toFloat()
    var closest = -1
    var similarity = 1.0 - 0.7045654963945799
    for ((index, center) in centers.withIndex()) {
        if (index in local) continue
        val cosine = embedding.indices.fold(0.0) { sum, i -> sum + embedding[i] * center[i] } / norm(center)
        if (cosine > similarity) {
            similarity = cosine
            closest = index
        }
    }
    if (closest < 0) {
        centers += embedding
        return centers.lastIndex
    }
    val center = centers[closest]
    for (i in center.indices) center[i] += embedding[i]
    return closest
}

private fun stitchWindows(windows: List<Window>, length: Int): List<SpeakerInterval> {
    val segments = mutableListOf<SpeakerInterval>()
    val open = mutableMapOf<Int, Int>()
    val next = mutableSetOf<Int>()
    for ((index, window) in windows.withIndex()) {
        val offset = index * HOP
        val from = if (index == 0) 0 else offset + HOP / 2
        val to = minOf(length, if (index == windows.lastIndex) length else offset + HOP * 3 / 2)
        for (i in window.activity.indices) {
            val start = maxOf(from, offset + i * WINDOW / window.activity.size)
            val end = minOf(to, offset + (i + 1) * WINDOW / window.activity.size)
            if (end <= start) continue
            next.clear()
            for ((local, speaker) in window.speakers.withIndex()) {
                if (speaker >= 0 && window.activity[i] and (1 shl local) != 0) next += speaker
            }
            val iterator = open.iterator()
            while (iterator.hasNext()) {
                val (speaker, first) = iterator.next()
                if (speaker !in next) {
                    segments += SpeakerInterval(speaker, first / 16000.0, start / 16000.0)
                    iterator.remove()
                }
            }
            for (speaker in next) open.putIfAbsent(speaker, start)
        }
    }
    for ((speaker, start) in open) segments += SpeakerInterval(speaker, start / 16000.0, length / 16000.0)
    return segments.filter { it.endSec > it.startSec }.sortedBy { it.startSec }
}

private const val WINDOW = 160000
private const val HOP = 80000
private const val WE_SPEAKER_SAMPLES = 80240
private val POWERS = intArrayOf(0, 1, 2, 4, 3, 5, 6)
