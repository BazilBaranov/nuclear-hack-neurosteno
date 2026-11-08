package dev.neurostenografist.inference

import android.os.Build
import dev.neurostenografist.core.SpeakerInterval
import java.io.File

/** NeMo-Speech.cpp Sortformer with one recurrent speaker cache per recording. */
class NemotronDiarizer(
    bundleDir: File,
    // Vulkan is the Android 14+ fallback when the LiteRT graphs are unavailable.
    private val preferGpu: Boolean = Build.VERSION.SDK_INT >= 34,
) : Diarizer {
    private val model = File(bundleDir, "Nemotron-3-Diarization.q8_0.gguf")

    init {
        require(model.isFile) { "Nemotron GGUF is missing: ${model.absolutePath}" }
        System.loadLibrary("neuro_nemotron")
    }

    override fun diarize(samples: FloatArray): List<SpeakerInterval> {
        require(samples.size >= 8000) { "audio too short for diarization" }
        return decodeNemotronSegments(nativeDiarize(model.absolutePath, samples, preferGpu, HardwareAcceleration.cpuThreads()), samples.size / 16000.0)
    }

    val backendReport: String get() = "Ускорение диаризации: ${nativeBackendReport()}"

    private external fun nativeBackendReport(): String

    // Native resources live only for this call and are released on every return/exception.
    private external fun nativeDiarize(
        path: String,
        samples: FloatArray,
        preferGpu: Boolean,
        threads: Int,
    ): DoubleArray
}

internal fun decodeNemotronSegments(
    raw: DoubleArray,
    durationSec: Double,
): List<SpeakerInterval> {
    require(raw.size % 3 == 0) { "Invalid Nemotron segment array" }
    return (raw.indices step 3).map { i ->
        val speaker = raw[i]
        val start = raw[i + 1]
        val end = raw[i + 2]
        require(speaker.isFinite() && speaker in 0.0..7.0 && speaker == speaker.toInt().toDouble())
        require(start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= durationSec)
        SpeakerInterval(speaker.toInt(), start, end)
    }
}
