package dev.neurostenografist.inference

import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.core.ModelChoice
import java.io.File

/**
 * Language unit ("ru"/"en") decided from the recording itself. `null` means the
 * recording was verified to contain no audible speech (silence-level signal everywhere);
 * the pipeline then completes with empty turns instead of failing. Uncertain or
 * unsupported speech is still an explicit error, never a silent fallback.
 */
interface LanguageDetector {
    fun detect(samples: FloatArray): String?

    /** Releases every acquired native resource; idempotent and safe by default. */
    fun close() {}
}

/** Transcribes a 16 kHz mono float array; cheap to instantiate per language decision. */
interface Asr {
    fun transcribe(samples: FloatArray): String

    fun close()
}

/**
 * Language decision combines:
 *  1) per-window votes of the sherpa SpokenLanguageIdentification model, and
 *  2) per-window language outputs of Whisper auto-transcription,
 * with majority over distinct speech windows. Uncertainty is an error, never a
 * silent fallback to a preferred language.
 */
class WhisperAutoLid(
    bundleDir: File,
    provider: String = "cpu",
    onBackendReport: (String) -> Unit = {},
) : LanguageDetector {
    private val recognizer = OnnxSessionFallback(provider, create = { selectedProvider -> SpokenLanguageIdentification(
        null,
        SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(
                encoder = File(bundleDir, "tiny-encoder.int8.onnx").absolutePath,
                decoder = File(bundleDir, "tiny-decoder.int8.onnx").absolutePath,
            ),
            numThreads = HardwareAcceleration.cpuThreads(), provider = selectedProvider,
        ),
    ) }, release = { it.release() }, onBackendReport = onBackendReport)

    val backendReport: String get() = recognizer.backendReport

    override fun detect(samples: FloatArray): String? {
        val windows = speechWindows(samples, WINDOW_SECONDS, MAX_WINDOWS)
        if (windows.isEmpty()) {
            // No window cleared the speech thresholds. Pure silence (global RMS below
            // the silence floor) is a VERIFIED no-speech input; remaining energy without
            // a usable window is uncertainty and must fail explicitly.
            if (rms(samples) <= SILENCE_RMS) return null
            throw PipelineException(
                "language",
                "audio has energy but no speech window is long enough to detect language; choose the model manually",
            )
        }
        val votes = HashMap<String, Int>()
        for (window in windows) {
            val lang = normalize(recognizer.run { it.decidedLanguage(window) })
            votes.merge(lang, 1, Int::plus)
            if (votes[lang]!! >= 2) break
        }
        val ranked = votes.entries.sortedByDescending { it.value }
        val top = ranked.first()
        val tied = ranked.size > 1 && ranked[1].value == top.value
        if (tied) {
            throw PipelineException(
                "language",
                "language detection is uncertain across ${windows.size} speech windows: $votes; choose the model manually",
            )
        }
        val lang = top.key
        if (lang !in ModelCatalog.supportedLanguages) {
            throw PipelineException(
                "language",
                "detected language '$lang' is not supported (supported: ${ModelCatalog.supportedLanguages.joinToString()}); " +
                    "no automatic transcription is offered and Russian is never forced",
            )
        }
        return lang
    }

    /** Runs Whisper auto-decode over one window and pulls the decided language from the result. */
    private fun SpokenLanguageIdentification.decidedLanguage(window: FloatArray): String {
        val stream: OfflineStream = createStream()
        try {
            stream.acceptWaveform(window, AudioDecoder.TARGET_SAMPLE_RATE)
            return compute(stream)
        } finally {
            stream.release()
        }
    }

    private fun normalize(raw: String): String =
        raw
            .trim()
            .removePrefix("<|")
            .removeSuffix("|>")
            .lowercase()

    /** Evenly spaced windows that contain actual speech energy (global-RMS-relative). */
    private fun speechWindows(
        samples: FloatArray,
        windowSec: Int,
        maxWindows: Int,
    ): List<FloatArray> {
        val len = samples.size
        if (len <= AUDIO_RATE * windowSec) {
            return if (enoughSpeech(samples)) listOf(samples.copyOf()) else emptyList()
        }
        val globalRms = rms(samples)
        val out = ArrayList<FloatArray>(maxWindows)
        for (i in 0 until maxWindows) {
            val startSamples = (i * (len - AUDIO_RATE * windowSec) / (maxWindows - 1).coerceAtLeast(1)).coerceAtLeast(0)
            val endSamples = minOf(startSamples + AUDIO_RATE * windowSec, len)
            val win = samples.copyOfRange(startSamples, endSamples)
            if (enoughSpeech(win) && rms(win) > 0.2f * globalRms) out += win
        }
        return out
    }

    private fun enoughSpeech(window: FloatArray): Boolean {
        if (window.size < AUDIO_RATE / 5) return false
        return rms(window) > SILENCE_RMS
    }

    private fun rms(x: FloatArray): Float {
        var sum = 0.0
        for (v in x) sum += v * v
        return kotlin.math.sqrt(sum / x.size).toFloat()
    }

    private var released = false

    override fun close() {
        if (!released) {
            released = true
            recognizer.close()
        }
    }

    companion object {
        private const val AUDIO_RATE = 16_000
        private const val THREADS = 2
        private const val WINDOW_SECONDS = 4
        private const val MAX_WINDOWS = 3
        private const val SILENCE_RMS = 1e-4f
    }
}

/**
 * How one ASR bundle maps onto a sherpa engine config, resolved from the bundle's
 * cataloged file names — the only contract between ModelCatalog naming and engine
 * construction. New bundles with a supported shape need no adapter change.
 */
sealed interface AsrAdapterPlan {
    val tokensFile: File

    /** Whisper encoder/decoder pair (no joiner); needs an explicit language per stream. */
    class Whisper(
        val encoderFile: File,
        val decoderFile: File,
        override val tokensFile: File,
    ) : AsrAdapterPlan

    /** Transducer triple (Zipformer, NeMo/GigaAM); sherpa dispatches on ONNX metadata. */
    class Transducer(
        val encoderFile: File,
        val decoderFile: File,
        val joinerFile: File,
        override val tokensFile: File,
    ) : AsrAdapterPlan
}

/**
 * Resolves bundle member names into engine roles. Model prefixes ("tiny-", "base-")
 * and quantization suffixes (".int8") are ignored; a joiner selects the transducer
 * path, its absence the whisper pair. Ambiguity or gaps are loud errors listing the
 * actual files, never a silent model substitution.
 */
object AsrBundleLayout {
    fun plan(
        bundle: ModelBundle,
        bundleDir: File,
    ): AsrAdapterPlan {
        val names = bundle.files.map { it.name }

        fun file(role: String): File {
            val matches = names.filter { roleOf(it) == role }
            if (matches.size != 1) {
                throw ModelBundleException(
                    "bundle '${bundle.id}' needs exactly one $role file; found $matches in $names",
                )
            }
            return File(bundleDir, matches.single())
        }

        val tokens = file("tokens")
        return if (names.any { roleOf(it) == "joiner" }) {
            AsrAdapterPlan.Transducer(file("encoder"), file("decoder"), file("joiner"), tokens)
        } else {
            AsrAdapterPlan.Whisper(file("encoder"), file("decoder"), tokens)
        }
    }

    private fun roleOf(name: String): String? =
        when {
            name.endsWith(".txt") -> {
                val stem = name.removeSuffix(".txt")
                if (stem == "tokens" || stem.endsWith("-tokens")) "tokens" else null
            }

            name.endsWith(".onnx") -> {
                val stem = name.removeSuffix(".onnx").removeSuffix(".int8")
                when {
                    stem == "encoder" || stem.endsWith("-encoder") -> "encoder"
                    stem == "decoder" || stem.endsWith("-decoder") -> "decoder"
                    stem == "joiner" || stem.endsWith("-joiner") -> "joiner"
                    else -> null
                }
            }

            else -> {
                null
            }
        }
}

/**
 * Real sherpa ASR over installed bundle files. The engine type comes from the
 * bundle's file shape ([AsrBundleLayout]): a whisper pair or a transducer triple —
 * so cataloged whisper (tiny/base/small), zipformer (legacy and 2025) and GigaAM v3
 * bundles all run their real local model. Anything else is a loud error.
 */
class SherpaAsr(
    modelId: String,
    bundleDir: File,
    language: String,
    provider: String = "cpu",
    onBackendReport: (String) -> Unit = {},
) : Asr {
    private val recognizer: OnnxSessionFallback<OfflineRecognizer>

    init {
        val plan = AsrBundleLayout.plan(ModelCatalog.byId(modelId), bundleDir)
        recognizer = OnnxSessionFallback(provider, create = { selectedProvider ->
        val modelProvider = if (modelId == ModelCatalog.GIGAAM_V3 && selectedProvider.substringBefore(':') == "nnapi") {
            selectedProvider.replaceFirst("nnapi", "nnapi-gigaam")
        } else selectedProvider
        val modelConfig =
            when (plan) {
                is AsrAdapterPlan.Whisper -> {
                    OfflineModelConfig(
                        whisper =
                            OfflineWhisperModelConfig(
                                encoder = plan.encoderFile.absolutePath,
                                decoder = plan.decoderFile.absolutePath,
                                language = language, // e.g. "en"/"ru"; explicit, no auto at this point
                                task = "transcribe",
                            ),
                        tokens = plan.tokensFile.absolutePath,
                        numThreads = HardwareAcceleration.cpuThreads(),
                        debug = false,
                        provider = modelProvider,
                    )
                }

                is AsrAdapterPlan.Transducer -> {
                    OfflineModelConfig(
                        // Single-language models (zipformer/GigaAM); `language` is a
                        // whisper-only parameter, deliberately unused here.
                        transducer =
                            OfflineTransducerModelConfig(
                                encoder = plan.encoderFile.absolutePath,
                                decoder = plan.decoderFile.absolutePath,
                                joiner = plan.joinerFile.absolutePath,
                            ),
                        // Avoid a separate CPU encoder session used only for architecture detection.
                        modelType = if (modelId == ModelCatalog.GIGAAM_V3) "nemo_transducer" else "",
                        tokens = plan.tokensFile.absolutePath,
                        numThreads = HardwareAcceleration.cpuThreads(),
                        debug = false,
                        provider = modelProvider,
                    )
                }
            }
        OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = modelConfig))
        }, release = { it.release() }, onBackendReport = onBackendReport)
    }

    val backendReport: String get() = recognizer.backendReport

    override fun transcribe(samples: FloatArray): String = recognizer.run { engine ->
        require(samples.isNotEmpty()) { "empty crop" }
        val stream: OfflineStream = engine.createStream()
        try {
            stream.acceptWaveform(samples, AudioDecoder.TARGET_SAMPLE_RATE)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.close()

    companion object {
        private const val THREADS = 2
    }
}

/**
 * Decision stage that owns the manual/auto selection policy: manual IDs must be
 * compatible and genuinely ready (installed after full verification — the catalog
 * inventory alone is never an installation signal); auto resolves through [ModelChoice]
 * with catalog priority against the ready set only.
 */
object EngineSelection {
    fun resolve(
        detectedLanguage: String,
        requestedModelId: String,
        readyAsrIds: Set<String>,
    ): String =
        ModelChoice.select(
            language = detectedLanguage,
            requestedId = requestedModelId,
            installedIds = readyAsrIds,
            priority = if (requestedModelId == ModelChoice.AUTO) ModelCatalog.autoPriority else
                ModelCatalog.supportedLanguages.associateWith { language ->
                    ModelCatalog.bundles.filter { it.kind == BundleKind.ASR && language in it.languages }.map { it.id }
                },
        )
}
