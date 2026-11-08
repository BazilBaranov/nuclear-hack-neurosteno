package dev.neurostenografist.core

/** Immutable DTOs shared by pipeline, store and UI. Milliseconds at all boundaries. */

data class Turn(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** Raw diarization output; `speaker` is a per-recording cluster label with no ordering semantics. */
data class SpeakerInterval(
    val speaker: Int,
    val startSec: Double,
    val endSec: Double,
)

data class AudioSource(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long?,
)

/** Reserved UI value meaning "pick by detected language"; never persisted as an actual model ID. */
const val AUTO_MODEL_ID = "auto"

enum class SpeechLanguage(val code: String?) {
    AUTO(null),
    RUSSIAN("ru"),
    ENGLISH("en"),
}

data class RunRequest(
    val modelId: String,
    val diarizationModelId: String = "diarization-pyannote-seg3",
    val language: SpeechLanguage = SpeechLanguage.AUTO,
    val punctuation: Boolean = false,
    val denoise: Boolean = false,
    val separateOverlappingSpeech: Boolean = false,
)

data class Progress(
    val stage: String,
    val fraction: Float?,
)

data class CompletedRun(
    val id: String,
    val sourceId: String,
    val turns: List<Turn>,
    val language: String?,
    val modelIds: List<String>,
    val modelHashes: Map<String, String>,
    val stageMs: Map<String, Long>,
    val warnings: List<String>,
)

/** Run lifecycle values. Frozen request/settings live with the run record. */
object RunStatus {
    const val CREATED = "created"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"

    val ALL = setOf(CREATED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED)

    init {
        require(ALL == setOf(CREATED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED))
    }
}

const val PIPELINE_VERSION = "diarization-first-v1"
