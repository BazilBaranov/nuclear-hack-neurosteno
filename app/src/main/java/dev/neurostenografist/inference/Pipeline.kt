package dev.neurostenografist.inference

import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.Progress
import dev.neurostenografist.core.Timeline
import dev.neurostenografist.core.Turn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/** Error carrying the failing pipeline stage plus a user-facing explanation. */
class PipelineException(
    val stage: String,
    message: String,
) : IllegalStateException("$stage: $message")

/**
 * Synchronous pipeline core, engine-agnostic (real engines are injected). The caller
 * (ProcessingService) runs it on a background dispatcher; every acquired engine — LID,
 * diarizer and ASR — is released in thoroughly, no matter how execution ends.
 *
 * [runId] is the ID of the run record this execution belongs to, so CompletedRun.id
 * is exactly the RunStore run ID and CompletedRun.sourceId stays the distinct source
 * identifier. Only assets actually used by this run (LID + chosen ASR + diarization)
 * end up in modelIds/modelHashes with full SHA-256s. [readyAsrIds] holds bundles that
 * are genuinely installed AND verified (ModelCatalog inventory alone is not installed
 * evidence); Auto selects from this set only. [isCancelled] is checked between every
 * native stage, per turn and per in-turn crop; a raised cancellation leaves through
 * CancellationException
 * after the in-flight call returned and cleanup ran — the caller decides the record
 * status, never the pipeline.
 */
class Pipeline(
    private val source: AudioSource,
    private val runId: String,
    private val samples: FloatArray,
    private val lid: LanguageDetector?,
    private val lidModelId: String,
    private val diarizer: Diarizer,
    private val asrFactory: (modelId: String, language: String) -> Asr,
    private val requestedModelId: String,
    private val readyAsrIds: Set<String>,
    private val isCancelled: () -> Boolean = { false },
    private val decodeStageMs: Long = 0L,
    private val diarizationModelId: String = ModelCatalog.DIARIZATION,
    private val isolationFactory: ((List<Turn>) -> SpeakerIsolation)? = null,
    private val punctuationFactory: (() -> TextPunctuator)? = null,
    private val additionalModelIds: List<String> = emptyList(),
    private val initialStageMs: Map<String, Long> = emptyMap(),
    private val runtimeReports: () -> List<String> = { emptyList() },
    private val requestedLanguage: String? = null,
    private val onProgress: (Progress) -> Unit,
) {
    fun execute(): CompletedRun {
        val stageMs = LinkedHashMap<String, Long>()
        stageMs["decode"] = decodeStageMs
        stageMs.putAll(initialStageMs)
        val warnings = ArrayList<String>()
        val usedAdditional = additionalModelIds.toMutableList()
        var diarizerClosed = false
        var lidClosed = false
        var isolation: SpeakerIsolation? = null
        val durationMs = (samples.size * 1000L) / AudioDecoder.TARGET_SAMPLE_RATE
        try {
            checkCancelled()
            // 1. Diarization is global and language independent; it must run first.
            onProgress(Progress("diarizing", 0f))
            val diarStart = clock()
            val intervals = diarizer.diarize(samples)
            usedAdditional += diarizer.modelIds
            stageMs["diarization"] = clock() - diarStart
            diarizer.close()
            diarizerClosed = true
            onProgress(Progress("diarizing", 1f))

            checkCancelled()
            // 2. Language decision over the recording's own speech windows. A verified
            //    no-speech recording completes with empty turns and null language; an
            //    uncertain or unsupported recording is an explicit failure.
            val usedLidIds = if (requestedLanguage == null) listOf(lidModelId) else emptyList()
            val language = requestedLanguage ?: run {
                val detector = requireNotNull(lid)
                onProgress(Progress("detecting language", null))
                val lidStart = clock()
                val detected = detector.detect(samples)
                usedAdditional += detector.modelIds
                lidClosed = true
                detector.close()
                stageMs["language"] = clock() - lidStart
                detected
            }
            if (language == null || (requestedLanguage != null && intervals.isEmpty())) {
                warnings += runtimeReports()
                val usedModels = (usedLidIds + diarizationModelId + usedAdditional).distinct()
                return CompletedRun(
                    id = runId,
                    sourceId = source.id,
                    turns = emptyList(),
                    language = language,
                    modelIds = usedModels,
                    modelHashes = hashesFor(usedModels),
                    stageMs = stageMs,
                    warnings = warnings,
                )
            }

            // 3. Deterministic engine selection over genuinely ready bundles (manual
            //    requests must match the language and be ready; no substitution).
            val chosen =
                EngineSelection.resolve(
                    detectedLanguage = language,
                    requestedModelId = requestedModelId,
                    readyAsrIds = readyAsrIds,
                )

            checkCancelled()
            // 4. Turn splitting on the original timeline with overlap ownership.
            var turns = Timeline.canonicalize(intervals, durationMs)
            val overlap = Timeline.overlapMs(intervals)
            if (overlap > 0 && isolationFactory == null) {
                warnings += "Одновременная речь: ${overlap / 1000.0} с. Разделение отключено. Проверьте принадлежность реплик по аудио."
            } else if (overlap > 0) {
                val tracks = OverlapPlan.turns(intervals, durationMs)
                isolation = isolationFactory?.invoke(tracks)
                val overlapping = tracks.filter { OverlapPlan.hasOverlap(it, tracks) }
                if (isolation != null && overlapping.all { isolation!!.hasReference(it.speakerId) }) {
                    turns = tracks
                    warnings += "Одновременная речь: ${overlap / 1000.0} с. Голоса выделены моделью VoxLock по чистым образцам этой записи; качество разделения требует проверки."
                } else {
                    isolation?.close()
                    isolation = null
                    warnings += "speaker overlap ${overlap / 1000.0}s: недостаточно чистой речи для выделения всех голосов; спорные интервалы отнесены к одному участнику. Проверьте их по аудио."
                }
            }
            warnings +=
                if (diarizationModelId == ModelCatalog.DIARIZATION) {
                    WARN_CLUSTERING_UNCALIBRATED
                } else {
                    "Диаризация Nemotron: качество разделения говорящих зависит от записи; проверьте короткие и одновременные реплики."
                }

            // 5. Real ASR per turn: chunked crops, all timestamps stay on the original
            //    timeline; one engine for the whole run, released even on failure.
            onProgress(Progress("transcribing", 0f))
            val asrStart = clock()
            var emptyTurns = 0
            var processed = 0
            val engine = asrEngine(chosen, language)
            val withText =
                try {
                    turns.map { turn ->
                        checkCancelled()
                        val text =
                            Timeline
                                .split(turn, MAX_CROP_MS)
                                .joinToString(" ") { crop ->
                                    checkCancelled() // long turns still stop at the next crop
                                    val audio = cropSamples(samples, crop)
                                    val isolated = if (isolation != null && OverlapPlan.hasOverlap(crop, turns)) {
                                        val separated = isolation!!.isolate(audio, crop.speakerId)
                                        if (ModelCatalog.ISOLATION !in usedAdditional) usedAdditional += ModelCatalog.ISOLATION
                                        separated
                                    } else audio
                                    checkCancelled()
                                    engine.transcribe(isolated)
                                }.trim()
                        if (text.isBlank()) emptyTurns++
                        processed++
                        onProgress(Progress("transcribing", processed.toFloat() / turns.size.coerceAtLeast(1)))
                        turn.copy(text = text)
                    }
                } finally {
                    usedAdditional += engine.modelIds
                    engine.close()
                }
            stageMs["asr"] = clock() - asrStart
            if (emptyTurns > 0) warnings += "removed $emptyTurns turns with empty ASR output"

            isolation?.close()
            isolation = null
            var finalTurns = withText.filter { it.text.isNotBlank() }
            if (finalTurns.isNotEmpty() && punctuationFactory != null) {
                checkCancelled()
                val start = clock()
                onProgress(Progress("punctuating", 0f))
                punctuationFactory.invoke().use { punctuation ->
                    finalTurns = finalTurns.mapIndexed { index, turn ->
                        checkCancelled()
                        val text = PunctuationGuard.accept(turn.text, punctuation.punctuate(turn.text, language))
                        onProgress(Progress("punctuating", (index + 1f) / finalTurns.size))
                        turn.copy(text = text)
                    }
                    usedAdditional += punctuation.modelIds
                    warnings += punctuation.backendReport
                }
                stageMs["punctuation"] = clock() - start
            }
            warnings += runtimeReports()
            val usedModels = (usedLidIds + chosen + diarizationModelId + usedAdditional).distinct()
            return CompletedRun(
                id = runId,
                sourceId = source.id,
                turns = finalTurns,
                language = language,
                modelIds = usedModels,
                modelHashes = hashesFor(usedModels),
                stageMs = stageMs,
                warnings = warnings,
            )
        } finally {
            try {
                isolation?.close()
            } finally {
                try { if (!lidClosed) lid?.close() } finally { if (!diarizerClosed) diarizer.close() }
            }
        }
    }

    private fun checkCancelled() {
        if (isCancelled()) throw CancellationException("run cancelled between stages")
    }

    /** Creates (and releases) one dedicated engine for this run. */
    private fun asrEngine(
        modelId: String,
        language: String,
    ): Asr = asrFactory(modelId, language)

    private fun cropSamples(
        samples: FloatArray,
        turn: Turn,
    ): FloatArray {
        val start = (turn.startMs / 1000.0 * AudioDecoder.TARGET_SAMPLE_RATE).roundToLong().toInt()
        val end = (turn.endMs / 1000.0 * AudioDecoder.TARGET_SAMPLE_RATE).roundToLong().toInt()
        return samples.copyOfRange(start.coerceIn(0, samples.size), end.coerceIn(start, samples.size))
    }

    /** Full SHA-256 per bundle member, joined in catalog order; unused bundles never appear. */
    private fun hashesFor(ids: List<String>): Map<String, String> =
        ids.associate { id ->
            val bundle = ModelCatalog.byId(id)
            bundle.id to bundle.files.joinToString(",") { it.sha256 }
        }

    private fun clock(): Long = System.nanoTime() / 1_000_000

    companion object {
        private const val MAX_CROP_MS = 25_000L // whisper accepts <=30s; leave tail headroom
        private const val WARN_CLUSTERING_UNCALIBRATED =
            "speaker clustering is automatic and not calibrated; counts may be over- or under-estimated on this audio"
    }
}

/** Local text postprocessor; the pipeline separately enforces word/number preservation. */
interface TextPunctuator : AutoCloseable {
    val modelIds: Set<String> get() = setOf(ModelCatalog.PUNCTUATION)
    val backendReport: String
    fun punctuate(text: String, language: String): String
}
