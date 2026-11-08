package dev.neurostenografist.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.neurostenografist.AppGraph
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.SpeechLanguage
import dev.neurostenografist.data.RunRecord
import dev.neurostenografist.data.RunStore
import dev.neurostenografist.data.SettingsStore
import dev.neurostenografist.data.SourceStore
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.BundleKind
import dev.neurostenografist.inference.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import dev.neurostenografist.core.Turn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Single app state holder. Stores are re-read after every mutation; while a run or a
 * recording is active a 500 ms poll keeps status, stage and elapsed time fresh.
 *
 * Job truth lives in [AppGraph] (the services' owner), never in UI state: recording
 * stays `recording` through stop/finalization/publication because the recording claim
 * is held until the source is persisted, and `recordingElapsedMs` is the real elapsed
 * duration from the claim timestamp, not the start timestamp.
 */
class AppViewModel(
    app: Application,
) : AndroidViewModel(app) {
    val runStore: RunStore = AppGraph.runStore(app)
    val sourceStore: SourceStore = AppGraph.sourceStore(app)
    val settings: SettingsStore = AppGraph.settingsStore(app)
    val modelStore: ModelStore = AppGraph.modelStore(app)

    data class UiState(
        val sources: List<AudioSource> = emptyList(),
        val runs: List<RunRecord> = emptyList(),
        val readyBundles: Set<String> = emptySet(),
        val activeRunId: String? = null,
        val activeStage: String? = null,
        val activeFraction: Float? = null,
        val recording: Boolean = false,
        val recordingElapsedMs: Long = 0,
        val message: String? = null,
        val installing: String? = null,
        val downloadFraction: Float? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private data class CachedResult(val runId: String, val revision: Long, val record: RunRecord?)
    private val resultLock = Mutex()
    private var cachedResult: CachedResult? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (ignored in refreshRequests) {
                try {
                    AppGraph.onProcessStart(app)
                    val ready = ModelCatalog.bundles.filter { modelStore.isReady(it.id) }.map { it.id }.toSet()
                    val sources = sourceStore.list()
                    val runs = runStore.list(includeCompleted = false)
                    _ui.update { it.copy(sources = sources, runs = runs, readyBundles = ready) }
                } catch (failure: Exception) {
                    _ui.update { it.copy(message = "Не удалось обновить список: ${userMessage(failure.message)}") }
                }
            }
        }
        viewModelScope.launch {
            runStore.revision.collect {
                resultLock.withLock {
                    if (cachedResult?.revision != runStore.revision.value) cachedResult = null
                }
                refresh()
            }
        }
        viewModelScope.launch {
            var previousOwner: Pair<String?, Boolean>? = null
            while (true) {
                val recording = AppGraph.recording
                val owner = AppGraph.activeRunId to recording
                if (owner != previousOwner || AppGraph.recordingResult != null) {
                    previousOwner = owner
                    AppGraph.recordingResult = null
                    refresh()
                }
                _ui.update {
                    it.copy(
                        activeRunId = owner.first,
                        activeStage = if (owner.first != null || recording) AppGraph.activeStage else null,
                        activeFraction = if (owner.first != null) AppGraph.activeProgress else null,
                        recording = recording,
                        recordingElapsedMs = if (recording) (System.currentTimeMillis() - AppGraph.recordingStartedAtMs).coerceAtLeast(0) else 0,
                        message = AppGraph.recordError?.let(::userMessage) ?: it.message,
                    )
                }
                delay(500)
            }
        }
    }

    fun refresh() { refreshRequests.trySend(Unit) }

    /** Keep only the last opened result; any store mutation invalidates its parsed payload. */
    suspend fun loadResult(runId: String): RunRecord? = withContext(Dispatchers.IO) {
        resultLock.withLock {
            val revision = runStore.revision.value
            val cached = cachedResult
            if (cached != null && cached.runId == runId && cached.revision == revision) {
                cached.record
            } else {
                runStore.read(runId).also { cachedResult = CachedResult(runId, revision, it) }
            }
        }
    }

    suspend fun editTurn(runId: String, index: Int, expected: Turn, replacement: Turn) = withContext(Dispatchers.IO) {
        check(!AppGraph.isBusy) { "Дождитесь завершения обработки или записи" }
        runStore.editTurn(runId, index, expected, replacement)
    }

    fun retryTurn(runId: String, index: Int) {
        try {
            check(!AppGraph.isBusy && _ui.value.installing == null) { "Дождитесь завершения текущей задачи" }
            dev.neurostenografist.ProcessingService.retryTurn(getApplication(), runId, index)
        } catch (failure: Exception) {
            _ui.update { it.copy(message = "Не удалось повторить фрагмент: ${userMessage(failure.message)}") }
        }
    }

    fun clearMessage() {
        AppGraph.recordError = null
        _ui.update { current -> current.copy(message = null) }
    }

    /**
     * Imports an SAF audio document as a direct content-URI reference (no data copy)
     * and keeps the read grant persistable when the provider offers it. Revoked or
     * ephemeral access is reported honestly; nothing promises persistence it cannot
     * keep. Import names are untrusted input and stored verbatim.
     */
    fun importAudio(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { _ui.update { current -> current.copy(message = null) } }
                val ctx = getApplication<Application>()
                val resolver = ctx.contentResolver
                val displayName = queryDisplayName(resolver, uri) ?: "import-${UUID.randomUUID().toString().take(8)}.wav"
                val persisted =
                    runCatching {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        true
                    }.isSuccess
                val source =
                    AudioSource(
                        id = UUID.randomUUID().toString(),
                        uri = uri.toString(),
                        displayName = displayName,
                        durationMs = null, // measured at decode time; never guessed
                    )
                sourceStore.upsert(source)
                if (!persisted) {
                    withContext(Dispatchers.Main) {
                        _ui.update { current -> current.copy(
                                message = "$displayName добавлен. Доступ временный: обработайте файл до его отзыва",
                            ) }
                    }
                }
                val warning = AppGraph.recordError?.let(::userMessage) // access may already be gone; decode will surface it
                _ui.update { current -> current.copy(message = warning ?: current.message) }
                refresh()
            } catch (failure: SecurityException) {
                withContext(Dispatchers.Main) {
                    _ui.update { current -> current.copy(message = "Нет доступа к файлу. Выберите его заново") }
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { current -> current.copy(message = "Не удалось импортировать: ${userMessage(failure.message)}") }
                }
            }
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String? =
        runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()

    fun startRun(
        sourceId: String,
        request: RunRequest,
    ) {
        if (_ui.value.installing != null) {
            _ui.update { current -> current.copy(message = "Дождитесь окончания загрузки модели") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppGraph.onProcessStart(getApplication())
                require(ModelCatalog.byId(request.diarizationModelId).kind == BundleKind.DIARIZATION) {
                    "Выберите модель диаризации"
                }
                ModelCatalog.requiredModelIds(request).firstOrNull { !modelStore.isReady(it) }?.let { id ->
                    if (id == ModelCatalog.WHISPER_TINY && request.language == SpeechLanguage.AUTO) {
                        error("Скачайте Whisper tiny в разделе «Модели» или укажите язык записи вручную")
                    }
                    error("Подготовьте модель «${ModelCatalog.byId(id).displayName}» в разделе «Модели»")
                }
                val source =
                    sourceStore.list().firstOrNull { it.id == sourceId }
                        ?: throw IllegalArgumentException("Исходный файл больше не найден")
                val runId = runStore.create(source, request)
                try {
                    dev.neurostenografist.ProcessingService.startRun(getApplication(), runId)
                } catch (startFailure: Exception) {
                    // The service never received the start (throttled or missing): the
                    // just-created record must not remain stranded in CREATED.
                    runCatching {
                        runStore.markStopped(
                            runId,
                            dev.neurostenografist.core.RunStatus.FAILED,
                            "Не удалось начать: ${userMessage(startFailure.message)}",
                        )
                    }
                    throw startFailure
                }
                refresh()
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { current -> current.copy(message = "Не удалось начать: ${userMessage(failure.message)}") }
                }
            }
        }
    }

    fun cancelRun() {
        try {
            dev.neurostenografist.ProcessingService.cancelActive(getApplication())
        } catch (failure: Exception) { _ui.update { it.copy(message = "Не удалось отменить обработку: ${userMessage(failure.message)}") } }
    }

    fun deleteRun(runId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(!AppGraph.isBusy) { "Дождитесь завершения текущей задачи" }
                runStore.deleteRun(runId)
                refresh()
            } catch (failure: Exception) { _ui.update { it.copy(message = userMessage(failure.message)) } }
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(!AppGraph.isBusy) { "Дождитесь завершения текущей задачи" }
                sourceStore.delete(sourceId)
                refresh()
            } catch (failure: Exception) { _ui.update { it.copy(message = userMessage(failure.message)) } }
        }
    }

    fun recordStart() {
        // admission, state and the clock are service-owned via AppGraph.claimRecording
        try {
            dev.neurostenografist.RecordingService.start(getApplication(), settings.recordSampleRate)
        } catch (failure: Exception) { _ui.update { it.copy(message = "Не удалось начать запись: ${userMessage(failure.message)}") } }
        refresh()
    }

    fun recordStop() {
        try {
            dev.neurostenografist.RecordingService.stop(getApplication())
        } catch (failure: Exception) { _ui.update { it.copy(message = "Не удалось сохранить запись: ${userMessage(failure.message)}") } }
        refresh()
    }

    fun installBundle(bundleId: String) {
        if (_ui.value.installing != null || AppGraph.isBusy) return
        _ui.update { current -> current.copy(installing = bundleId, downloadFraction = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var lastUpdateMs = 0L
                modelStore.install(bundleId) { progress ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdateMs >= 100 || progress.fraction == 1f) {
                        lastUpdateMs = now
                        _ui.update { if (it.installing == bundleId) it.copy(downloadFraction = progress.fraction) else it }
                    }
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { current -> current.copy(message = "Не удалось загрузить: ${userMessage(failure.message)}") }
                }
            } finally {
                withContext(Dispatchers.Main) { _ui.update { current -> current.copy(installing = null, downloadFraction = null) } }
                refresh()
            }
        }
    }

    fun deleteBundle(bundleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                modelStore.delete(bundleId)
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { current -> current.copy(message = "Не удалось удалить: ${userMessage(failure.message)}") }
                }
            }
            refresh()
        }
    }

    fun setStatus(status: String): Boolean =
        try {
            settings.recordSampleRate = status.toInt()
            true
        } catch (_: Exception) {
            false
        }

    /** True while the service reports an actively owned run. */
    fun isRunActive(runId: String): Boolean = AppGraph.activeRunId == runId
}

/**
 * Legacy UI-mirrored recording handle retained for source compatibility; the live
 * recording state is now owned by [AppGraph] through the recording claim.
 */
object RecordingHandle {
    @Volatile var isRunning: Boolean = false

    @Volatile var startedAtMs: Long = 0
}
