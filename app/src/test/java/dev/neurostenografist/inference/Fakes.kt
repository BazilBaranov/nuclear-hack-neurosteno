package dev.neurostenografist

import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.inference.Asr
import dev.neurostenografist.inference.Diarizer
import dev.neurostenografist.inference.LanguageDetector

/** JVM fakes shared by pipeline-level tests (real engines are Android-native). */

class FakeLid(
    val detected: String? = "ru",
) : LanguageDetector {
    var closed = false
    var throwOnDetect: Throwable? = null

    override fun detect(samples: FloatArray): String? {
        throwOnDetect?.let { throw it }
        return detected
    }

    override fun close() {
        closed = true
    }
}

class FakeDiarizer(
    vararg intervals: SpeakerInterval,
) : Diarizer {
    val intervals = intervals.toList()
    var closed = false
    var throwOnDiarize: Throwable? = null

    override fun diarize(samples: FloatArray): List<SpeakerInterval> {
        throwOnDiarize?.let { throw it }
        return intervals
    }

    override fun close() {
        closed = true
    }
}

class FakeAsr : Asr {
    var calls = 0
    var closed = false
    var texts = mutableListOf("text")

    override fun transcribe(samples: FloatArray): String {
        calls++
        return texts.getOrNull((calls - 1) % texts.size) ?: "text"
    }

    override fun close() {
        closed = true
    }
}
