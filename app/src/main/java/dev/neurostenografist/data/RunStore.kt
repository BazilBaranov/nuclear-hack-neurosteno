package dev.neurostenografist.data

import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.SpeechLanguage
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.core.Turn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One persisted job: frozen request + source + lifecycle status + optional payload. */
data class RunRecord(
    val id: String,
    val source: AudioSource,
    val request: RunRequest,
    val status: String,
    val message: String?,
    val createdAtMs: Long,
    val completed: CompletedRun?,
)

/** Provenance from a successful fragment retry; timings describe the latest retry. */
data class RetryEvidence(
    val modelIds: List<String> = emptyList(),
    val modelHashes: Map<String, String> = emptyMap(),
    val stageMs: Map<String, Long> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

/**
 * Small atomic per-run JSON store (temp file + rename). A run file is written whole or
 * not at all, so a partially written completion is never observed as completed. Source
 * audio is never touched: deletion removes only the run record itself.
 */
class RunStore(
    private val root: File,
) {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private fun file(id: String) = File(root, "$id.json")

    fun create(
        source: AudioSource,
        request: RunRequest,
    ): String {
        require(source.id.isNotBlank()) { "Не указан исходный файл" }
        synchronized(this) {
            val id = UUID.randomUUID().toString()
            write(
                RunRecord(
                    id = id,
                    source = source,
                    request = request,
                    status = RunStatus.CREATED,
                    message = null,
                    createdAtMs = System.currentTimeMillis(),
                    completed = null,
                ),
            )
            return id
        }
    }

    fun markRunning(runId: String) =
        synchronized(this) {
            update(runId) { record ->
                require(record.status == RunStatus.CREATED || record.status == RunStatus.RUNNING) {
                    "Эту расшифровку уже нельзя запустить повторно"
                }
                record.copy(status = RunStatus.RUNNING)
            }
        }

    fun complete(run: CompletedRun, durationMs: Long? = null) =
        synchronized(this) {
            update(run.id) { record ->
                require(run.id == record.id) { "Результат относится к другой расшифровке" }
                require(record.status == RunStatus.CREATED || record.status == RunStatus.RUNNING) {
                    "Расшифровка уже завершена или отменена"
                }
                require(durationMs == null || (durationMs >= 0 && run.turns.all { it.endMs <= durationMs })) {
                    "Длительность записи не может быть меньше конца реплики"
                }
                record.copy(status = RunStatus.COMPLETED, message = null, completed = run,
                    source = if (durationMs == null) record.source else record.source.copy(durationMs = durationMs))
            }
        }

    fun markStopped(
        runId: String,
        status: String,
        message: String?,
    ) {
        require(status == RunStatus.FAILED || status == RunStatus.CANCELLED || status == RunStatus.INTERRUPTED) {
            "Недопустимое состояние остановленной расшифровки"
        }
        synchronized(this) {
            update(runId) { record ->
                require(record.status == RunStatus.CREATED || record.status == RunStatus.RUNNING) {
                    "Расшифровка уже завершена или остановлена"
                }
                record.copy(status = status, message = message, completed = null)
            }
        }
    }

    fun read(runId: String): RunRecord? =
        synchronized(this) {
            decode(file(runId))
        }

    fun list(includeCompleted: Boolean = true): List<RunRecord> =
        synchronized(this) {
            (root.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
                .mapNotNull { decode(it, includeCompleted) }
                .sortedByDescending { it.createdAtMs }
        }

    fun readCompleted(runId: String): CompletedRun {
        val record =
            read(runId)
                ?: throw IllegalStateException("Расшифровка не найдена")
        return record.completed
            ?: throw IllegalStateException("Расшифровка ещё не завершена")
    }

    /** Compare the original turn so a delayed edit/retry cannot overwrite newer work. */
    fun editTurn(runId: String, index: Int, expected: Turn, replacement: Turn, retryEvidence: RetryEvidence? = null) {
        synchronized(this) {
            val record = read(runId) ?: error("Результат не найден")
            check(record.status == RunStatus.COMPLETED) { "Редактировать можно только завершённую расшифровку" }
            val completed = record.completed ?: error("Текст результата отсутствует")
            check(completed.turns.getOrNull(index) == expected) { "Реплика уже изменена. Откройте её заново" }
            require(replacement.speakerId.isNotBlank()) { "Укажите участника" }
            // shortcut: legacy imports have no duration; limit edits to known bounds until the source is decoded again.
            val duration = record.source.durationMs ?: completed.turns.maxOfOrNull { it.endMs } ?: 0L
            require(replacement.startMs >= 0 && replacement.endMs > replacement.startMs && replacement.endMs <= duration) {
                "Время должно быть в пределах записи, конец — позже начала"
            }
            val turns = completed.turns.toMutableList().apply { set(index, replacement) }.sortedBy { it.startMs }
            val updated = if (retryEvidence == null) completed.copy(turns = turns) else completed.copy(
                turns = turns,
                modelIds = (completed.modelIds + retryEvidence.modelIds).distinct(),
                modelHashes = completed.modelHashes + retryEvidence.modelHashes,
                stageMs = completed.stageMs + retryEvidence.stageMs.mapKeys { "retry_${it.key}" },
                warnings = (completed.warnings + retryEvidence.warnings).distinct(),
            )
            write(record.copy(completed = updated))
        }
    }

    fun deleteRun(runId: String) {
        synchronized(this) {
            require(runId.isNotBlank()) { "Не указана расшифровка" }
            val target = file(runId)
            if (target.exists()) {
                check(target.delete()) { "Не удалось удалить результат" }
                _revision.value += 1
            }
        }
    }

    /** On cold start only: a run left `running` OR stranded in `created` (selected but
     *  never admitted/started before the process died) with no live owner becomes
     *  `interrupted`. */
    fun markInterruptedOnRestart() {
        synchronized(this) {
            list(includeCompleted = false)
                .filter { it.status == RunStatus.RUNNING || it.status == RunStatus.CREATED }
                .forEach { record ->
                    write(record.copy(status = RunStatus.INTERRUPTED, message = "Обработка прервана после перезапуска приложения"))
                }
        }
    }

    private fun update(
        runId: String,
        transform: (RunRecord) -> RunRecord,
    ) {
        val record = read(runId) ?: throw IllegalStateException("Расшифровка не найдена")
        write(transform(record))
    }

    /** Atomic replace: writes a temp file and renames it over the record. On any
     *  failure the previous good record on disk stays untouched. */
    private fun write(record: RunRecord) {
        root.mkdirs()
        val tmp = File(root, "${record.id}.json.tmp")
        try {
            tmp.writeText(encode(record))
            check(tmp.renameTo(file(record.id))) { "Не удалось сохранить расшифровку" }
            _revision.value += 1
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun encode(record: RunRecord): String =
        JSONObject()
            .put("id", record.id)
            .put("status", record.status)
            .put("message", record.message ?: JSONObject.NULL)
            .put("createdAtMs", record.createdAtMs)
            .put("source", encodeSource(record.source))
            .put(
                "request",
                JSONObject()
                    .put("modelId", record.request.modelId)
                    .put("diarizationModelId", record.request.diarizationModelId)
                    .putOpt("language", record.request.language.code)
                    .put("punctuation", record.request.punctuation)
                    .put("denoise", record.request.denoise)
                    .put("separateOverlappingSpeech", record.request.separateOverlappingSpeech),
            ).putOpt("completed", record.completed?.let(::encodeCompleted))
            .toString()

    private fun decode(file: File, includeCompleted: Boolean = true): RunRecord? {
        if (!file.isFile) return null
        return try {
            val body = JSONObject(file.readText())
            val status = body.getString("status")
            if (status !in RunStatus.ALL) return null
            val completed =
                if (includeCompleted && body.has("completed") && !body.isNull("completed")) {
                    decodeCompleted(body.getJSONObject("completed"))
                } else {
                    null
                }
            val request = body.getJSONObject("request")
            RunRecord(
                id = body.getString("id"),
                source = decodeSource(body.getJSONObject("source")),
                request =
                    RunRequest(
                        request.getString("modelId"),
                        request.optString("diarizationModelId", "diarization-pyannote-seg3"),
                        SpeechLanguage.entries.firstOrNull { it.code == request.optString("language", "") }
                            ?: SpeechLanguage.AUTO,
                        // Runs saved before the checkboxes used all three stages.
                        punctuation = request.optBoolean("punctuation", true),
                        denoise = request.optBoolean("denoise", true),
                        separateOverlappingSpeech = request.optBoolean("separateOverlappingSpeech", true),
                    ),
                status = status,
                message = if (body.isNull("message")) null else body.getString("message"),
                createdAtMs = body.getLong("createdAtMs"),
                completed = completed,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeSource(source: AudioSource): JSONObject =
        JSONObject()
            .put("id", source.id)
            .put("uri", source.uri)
            .put("displayName", source.displayName)
            .put("durationMs", source.durationMs ?: JSONObject.NULL)

    private fun decodeSource(body: JSONObject): AudioSource =
        AudioSource(
            id = body.getString("id"),
            uri = body.getString("uri"),
            displayName = body.getString("displayName"),
            durationMs = if (body.isNull("durationMs")) null else body.getLong("durationMs"),
        )

    private fun encodeCompleted(run: CompletedRun): JSONObject =
        JSONObject()
            .put("id", run.id)
            .put("sourceId", run.sourceId)
            .put(
                "turns",
                JSONArray().apply {
                    run.turns.forEach { turn ->
                        put(
                            JSONObject()
                                .put("speakerId", turn.speakerId)
                                .put("startMs", turn.startMs)
                                .put("endMs", turn.endMs)
                                .put("text", turn.text),
                        )
                    }
                },
            ).put("language", run.language ?: JSONObject.NULL)
            .put("modelIds", JSONArray(run.modelIds))
            .put(
                "modelHashes",
                JSONObject().apply { run.modelHashes.forEach { (k, v) -> put(k, v) } },
            ).put("stageMs", JSONObject().apply { run.stageMs.forEach { (k, v) -> put(k, v) } })
            .put("warnings", JSONArray(run.warnings))

    private fun decodeCompleted(body: JSONObject): CompletedRun {
        val turns = mutableListOf<Turn>()
        val turnsBody = body.getJSONArray("turns")
        for (i in 0 until turnsBody.length()) {
            val t = turnsBody.getJSONObject(i)
            turns +=
                Turn(
                    speakerId = t.getString("speakerId"),
                    startMs = t.getLong("startMs"),
                    endMs = t.getLong("endMs"),
                    text = t.getString("text"),
                )
        }
        val modelIds = mutableListOf<String>()
        val idsBody = body.getJSONArray("modelIds")
        for (i in 0 until idsBody.length()) modelIds += idsBody.getString(i)
        val warnings = mutableListOf<String>()
        val warningsBody = body.getJSONArray("warnings")
        for (i in 0 until warningsBody.length()) warnings += warningsBody.getString(i)
        val modelHashes = mutableMapOf<String, String>()
        val hashesBody = body.getJSONObject("modelHashes")
        hashesBody.keys().forEach { key -> modelHashes[key] = hashesBody.getString(key) }
        val stageMs = mutableMapOf<String, Long>()
        val stagesBody = body.getJSONObject("stageMs")
        stagesBody.keys().forEach { key -> stageMs[key] = stagesBody.getLong(key) }
        return CompletedRun(
            id = body.getString("id"),
            sourceId = body.getString("sourceId"),
            turns = turns,
            language = if (body.isNull("language")) null else body.getString("language"),
            modelIds = modelIds,
            modelHashes = modelHashes,
            stageMs = stageMs,
            warnings = warnings,
        )
    }
}
