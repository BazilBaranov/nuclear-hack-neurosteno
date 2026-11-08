package dev.neurostenografist.inference.litert

import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.inference.Asr
import dev.neurostenografist.inference.Diarizer
import dev.neurostenografist.inference.ModelCatalog
import java.io.File
import java.util.concurrent.CancellationException

internal class LiteRtWhisperSession(
    private val modelDir: File,
    private val tokensFile: File,
    private val checkCancellation: () -> Unit,
    private val onBackendReport: (String) -> Unit,
) : AutoCloseable {
    private var model: LiteRtWhisper? = null
    private var failed = false
    var used = false
        private set

    fun <T : Any> run(infer: (LiteRtWhisper) -> T): T? {
        if (failed) return null
        try {
            val current = model ?: LiteRtWhisper(modelDir, tokensFile, checkCancellation).also { model = it }
            val result = infer(current)
            if (!used) onBackendReport("Whisper tiny: энкодер LiteRT GPU, декодер ONNX CPU")
            used = true
            return result
        } catch (error: Throwable) {
            if (!canFallBack(error)) throw error
            failed = true
            close()
            onBackendReport("Whisper tiny: CPU fallback после ошибки LiteRT (${error.javaClass.simpleName})")
            return null
        }
    }

    override fun close() {
        val current = model
        model = null
        current?.close()
    }
}

internal class LiteRtWhisperAsr(
    private val session: LiteRtWhisperSession,
    private val language: String,
    createCpu: () -> Asr,
) : Asr {
    private val cpu = lazy(createCpu)
    override val modelIds: Set<String>
        get() = if (session.used) setOf(ModelCatalog.WHISPER_TINY_LITERT) else emptySet()

    override fun transcribe(samples: FloatArray): String =
        session.run { it.transcribe(samples, language) } ?: cpu.value.transcribe(samples)

    override fun close() {
        try {
            session.close()
        } finally {
            if (cpu.isInitialized()) cpu.value.close()
        }
    }
}

internal class LiteRtDiarizer(
    private val acceleratedId: String,
    private val accelerated: (FloatArray) -> List<SpeakerInterval>,
    createFallback: () -> Diarizer,
    private val onBackendReport: (String) -> Unit,
) : Diarizer {
    private val fallback = lazy(createFallback)
    override var modelIds: Set<String> = emptySet()
        private set

    override fun diarize(samples: FloatArray): List<SpeakerInterval> = try {
        val result = accelerated(samples)
        modelIds = setOf(acceleratedId)
        onBackendReport(
            if (acceleratedId == ModelCatalog.PYANNOTE_LITERT) "Pyannote: сегментация ONNX CPU, эмбеддинги WeSpeaker LiteRT GPU"
            else "Nemotron: графы LiteRT GPU",
        )
        result
    } catch (error: Throwable) {
        if (!canFallBack(error)) throw error
        onBackendReport("Диаризация: ошибка LiteRT (${error.javaClass.simpleName}), повтор со штатной моделью")
        fallback.value.diarize(samples)
    }

    override fun close() {
        if (fallback.isInitialized()) fallback.value.close()
    }
}

internal fun canFallBack(error: Throwable): Boolean =
    error !is CancellationException && (error is Exception || error is LinkageError)
