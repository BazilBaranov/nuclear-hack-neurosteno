package dev.neurostenografist

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.core.AUTO_MODEL_ID
import dev.neurostenografist.core.Progress
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.data.RetryEvidence
import dev.neurostenografist.inference.*
import dev.neurostenografist.inference.litert.LiteRtDiarizer
import dev.neurostenografist.inference.litert.LiteRtWhisperAsr
import dev.neurostenografist.inference.litert.LiteRtWhisperSession
import dev.neurostenografist.inference.litert.diarizeNemotronLiteRt
import dev.neurostenografist.inference.litert.diarizePyannoteLiteRt
import dev.neurostenografist.audio.AudioPreprocessor
import dev.neurostenografist.ui.stageLabel
import java.io.File
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.ModelStore
import dev.neurostenografist.inference.Pipeline
import dev.neurostenografist.inference.PipelineException
import dev.neurostenografist.inference.SherpaAsr
import dev.neurostenografist.inference.SherpaDiarizer
import dev.neurostenografist.inference.WhisperAutoLid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground dataSync service owning exactly one run: decode → diarize → language →
 * ASR. Admission is atomic via [AppGraph.claimRun] — duplicates and starts during
 * recording are rejected before the foreground entry completes. Cancellation waits
 * for the native stage in flight to return, releases every engine, and only then the
 * record moves to `cancelled`; no completion is ever committed after cancellation.
 */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.ensureChannels(this)
    }

    override fun onStartCommand(
        intent: Intent,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
        when (intent.action) {
            ACTION_CANCEL -> {
                // Apply the cancel when the current native call returns, not before.
                if (AppGraph.activeRunId != null) AppGraph.delayedCancel = true
                return START_NOT_STICKY
            }

            else -> {
                // Admission is atomic and centralized in AppGraph (the claim owner):
                // duplicates and foreign starts never disturb the active owner.
                val runId = intent.getStringExtra(EXTRA_RUN_ID)
                when (AppGraph.decideRunStart(runId)) {
                    RunStartDecision.IGNORE_DUPLICATE -> {
                        // Duplicate-id start intents are still sent via
                        // startForegroundService; answer the foreground contract.
                        startInForeground(runId!!)
                        return START_NOT_STICKY
                    }

                    RunStartDecision.IGNORE_NO_RUN -> {
                        // Null extra: never produced by startRun, so no FGS contract.
                        return START_NOT_STICKY
                    }

                    RunStartDecision.START_RUN -> {
                        startInForeground(runId!!)
                        val turnIndex = intent.getIntExtra(EXTRA_TURN_INDEX, -1)
                        job = scope.launch { if (turnIndex >= 0) executeRetry(runId, turnIndex) else execute(runId) }
                        return START_NOT_STICKY
                    }

                    RunStartDecision.REJECT_RUN_OWNER_ACTIVE -> {
                        // A DIFFERENT-id start while this instance owns another run:
                        // never stopSelf() — that would kill the active owner. Only
                        // the rejected fresh run is terminalized, current run untouched.
                        terminalizeRejected(
                            runId,
                            "start rejected: another run is already active",
                        )
                        // The intent arrived via startForegroundService; the foreground
                        // contract is satisfied without disturbing the owner's state.
                        startInForeground(AppGraph.activeRunId ?: runId!!)
                        return START_NOT_STICKY
                    }

                    RunStartDecision.REJECT_IDLE_STOP -> {
                        terminalizeRejected(
                            runId,
                            "start rejected: recording is already active",
                        )
                        startInForeground(runId!!) // honest unfulfilled-state foreground then stop
                        stopSelf() // no owner here: stopping this idle start is safe
                        return START_NOT_STICKY
                    }
                }
            }
        }
    }

    /** Failed admission is closed out here so a rejected start never strands a
     *  CREATED record or a running service. Only the rejected run id is mutated. */
    private fun terminalizeRejected(
        runId: String?,
        reason: String,
    ) {
        if (runId == null) return
        runCatching {
            AppGraph.runStore(this).markStopped(runId, RunStatus.FAILED, reason)
        }
    }

    private fun startInForeground(_runId: String) {
        val notification =
            builder()
                .setContentTitle("Расшифровка аудио")
                .setContentText("Подготовка")
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun builder(): NotificationCompat.Builder =
        NotificationCompat
            .Builder(this, AppGraph.CH_PROCESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

    private suspend fun execute(runId: String) {
        val store = AppGraph.runStore(this)
        try {
            val record = store.read(runId)
            if (record == null || record.status != RunStatus.CREATED) {
                return // nothing to do; admission is released in finally
            }
            store.markRunning(runId)
            val modelStore = AppGraph.modelStore(this)
            try {
                val request = record.request
                val requestedLanguage = request.language.code
                require(ModelCatalog.byId(request.diarizationModelId).kind == BundleKind.DIARIZATION) {
                    "Выберите модель диаризации"
                }
                val required = ModelCatalog.requiredModelIds(request)
                requireReadyModels(if (request.modelId == AUTO_MODEL_ID) required + ModelCatalog.WHISPER_SMALL else required)
                // Readiness is genuine verified contents, never catalog inventory.
                val readyAsrIds =
                    ModelCatalog.bundles
                        .filter { it.kind == BundleKind.ASR && modelStore.isReady(it.id) }
                        .map { it.id }
                        .toSet()
                val source = record.source
                notify(Progress("decoding audio", null))
                val decodeStart = System.currentTimeMillis()
                val samples = AudioDecoder.decodeTo16k(this, Uri.parse(source.uri))
                val decodeStageMs = System.currentTimeMillis() - decodeStart
                val enhancementMs = prepareAudio(samples, modelStore, request.denoise)
                checkCancellation()
                val backends = mutableListOf<OnnxBackend>()
                val backendReports = mutableListOf<String>()
                fun provider(stage: String): String = OnnxAcceleration.choose(this, stage).also { backends += it }.provider
                val diarizer = prepareDiarizer(modelStore, request.diarizationModelId, { provider("diarization") }) {
                    backendReports += it
                }
                val lid = if (requestedLanguage == null) object : LanguageDetector {
                    private var delegate: WhisperAutoLid? = null
                    override val modelIds: Set<String> get() = delegate?.modelIds.orEmpty()
                    override fun detect(samples: FloatArray): String? {
                        val report: (String) -> Unit = { backendReports += "Определение языка: $it" }
                        val engine = WhisperAutoLid(modelStore.bundleDir(ModelCatalog.WHISPER_TINY), provider("language"), report)
                        engine.liteRt = whisperLiteRt(modelStore, report)
                        delegate = engine
                        return engine.detect(samples)
                    }
                    override fun close() { delegate?.close(); delegate = null }
                } else null
                val pipeline =
                    Pipeline(
                        source = source,
                        runId = runId,
                        samples = samples,
                        lid = lid,
                        lidModelId = ModelCatalog.WHISPER_TINY,
                        diarizer = diarizer,
                        asrFactory = { modelId, language ->
                            prepareAsr(modelStore, modelId, language, provider("asr")) { backendReports += "Распознавание: $it" }
                        },
                        requestedModelId = request.modelId,
                        readyAsrIds = readyAsrIds,
                        requestedLanguage = requestedLanguage,
                        isCancelled = ::isCancelled,
                        decodeStageMs = decodeStageMs,
                        diarizationModelId = request.diarizationModelId,
                        isolationFactory = if (request.separateOverlappingSpeech) {
                            { turns -> VoxLockIsolation(modelStore.bundleDir(ModelCatalog.ISOLATION), cacheDir, samples, turns) }
                        } else null,
                        punctuationFactory = if (request.punctuation) ({ punctuation(modelStore) }) else null,
                        additionalModelIds = if (enhancementMs != null) listOf(ModelCatalog.DENOISE) else emptyList(),
                        initialStageMs = enhancementMs?.let { mapOf("enhancement" to it) }.orEmpty(),
                        runtimeReports = { backendReports.distinct() + backends.map { OnnxAcceleration.evidence(it) } },
                    ) { progress -> notify(progress) }
                val completed = pipeline.execute()
                // Cancellation that landed after the last stage check must still
                // prevent a completion commit.
                if (isCancelled()) {
                    store.markStopped(runId, RunStatus.CANCELLED, "cancelled by user")
                    AppGraph.delayedCancel = false
                } else {
                    store.complete(completed, samples.size * 1000L / 16000)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    runCatching { store.markStopped(runId, RunStatus.CANCELLED, "cancelled by user") }
                } else {
                    val stage = (failure as? PipelineException)?.stage ?: "processing"
                    runCatching {
                        store.markStopped(runId, RunStatus.FAILED, "$stage: ${failure.message ?: failure.javaClass.simpleName}")
                    }
                }
            } finally {
                AppGraph.delayedCancel = false
                runCatching {
                    if (store.read(runId)?.status == RunStatus.RUNNING) {
                        store.markStopped(runId, RunStatus.FAILED, "processing ended without a result")
                    }
                }
            }
        } finally {
            finishRun(runId)
        }
    }

    private fun isCancelled(): Boolean = AppGraph.delayedCancel || job?.isActive == false

    private fun checkCancellation() {
        if (isCancelled()) throw CancellationException("Обработка отменена")
    }

    private fun prepareAudio(samples: FloatArray, models: ModelStore, denoise: Boolean): Long? {
        checkCancellation()
        AudioPreprocessor.processInPlace(samples)
        if (!denoise) return null
        checkCancellation()
        notify(Progress("enhancing", 0f))
        val start = android.os.SystemClock.elapsedRealtime()
        GtcrnEnhancer(models.bundleDir(ModelCatalog.DENOISE)).use { enhancer ->
            enhancer.processInPlace(samples, ::isCancelled) { notify(Progress("enhancing", it)) }
        }
        return android.os.SystemClock.elapsedRealtime() - start
    }

    private fun punctuation(models: ModelStore): TextPunctuator {
        fun weights(id: String) = File(models.bundleDir(id), ModelCatalog.byId(id).files.single().name)
        val cpuModel = weights(ModelCatalog.PUNCTUATION)
        if (Build.VERSION.SDK_INT >= 34 && models.isReady(ModelCatalog.PUNCTUATION_LITERT)) {
            return LiteRtPunctuation(weights(ModelCatalog.PUNCTUATION_LITERT), cpuModel, codeCacheDir, ::isCancelled)
        }
        return LocalPunctuation(cpuModel, isCancelled = ::isCancelled)
    }

    private fun prepareDiarizer(
        models: ModelStore,
        id: String,
        onnxProvider: () -> String,
        report: (String) -> Unit,
    ): Diarizer {
        val bundle = models.bundleDir(id)
        val liteRtId = if (id == ModelCatalog.NEMOTRON) ModelCatalog.NEMOTRON_LITERT else ModelCatalog.PYANNOTE_LITERT
        fun fallback(): Diarizer {
            val selected = createDiarizer(id, bundle, if (id == ModelCatalog.DIARIZATION) onnxProvider() else "cpu", report)
            if (selected !is NemotronDiarizer) return selected
            return object : Diarizer {
                override fun diarize(samples: FloatArray) = selected.diarize(samples).also { report(selected.backendReport) }
            }
        }
        if (Build.VERSION.SDK_INT < 34 || !models.isReady(liteRtId)) return fallback()
        val dir = models.bundleDir(liteRtId)
        return LiteRtDiarizer(liteRtId, { samples ->
            if (liteRtId == ModelCatalog.NEMOTRON_LITERT) diarizeNemotronLiteRt(dir, samples, ::checkCancellation)
            else diarizePyannoteLiteRt(this, dir, samples, ::checkCancellation)
        }, ::fallback, report)
    }

    private fun whisperLiteRt(models: ModelStore, report: (String) -> Unit): LiteRtWhisperSession? {
        if (Build.VERSION.SDK_INT < 34 || !models.isReady(ModelCatalog.WHISPER_TINY_LITERT)) return null
        return LiteRtWhisperSession(
            models.bundleDir(ModelCatalog.WHISPER_TINY_LITERT),
            File(models.bundleDir(ModelCatalog.WHISPER_TINY), "tiny-tokens.txt"),
            ::checkCancellation,
            report,
        )
    }

    private fun prepareAsr(
        models: ModelStore,
        modelId: String,
        language: String,
        provider: String,
        onBackendReport: (String) -> Unit,
    ): Asr {
        val directory = models.bundleDir(modelId)
        if (modelId == ModelCatalog.WHISPER_TINY) {
            whisperLiteRt(models, onBackendReport)?.let { session ->
                return LiteRtWhisperAsr(session, language) { SherpaAsr(modelId, directory, language, "cpu", onBackendReport) }
            }
        }
        if (modelId == ModelCatalog.GIGAAM_V3 && provider == "nnapi") {
            return GigaAmPreparation.acquire(directory, onBackendReport)
                ?: SherpaAsr(modelId, directory, language, "cpu", onBackendReport)
        }
        return SherpaAsr(modelId, directory, language, provider, onBackendReport)
    }

    /** Existing completed result is untouched unless the entire replacement succeeds. */
    private suspend fun executeRetry(runId: String, index: Int) {
        try {
            val timings = linkedMapOf<String, Long>()
            val reports = mutableListOf<String>()
            val store = AppGraph.runStore(this)
            val record = requireNotNull(store.read(runId)) { "Результат не найден" }
            val completed = requireNotNull(record.completed) { "Расшифровка ещё не завершена" }
            val expected = completed.turns.getOrNull(index) ?: error("Реплика не найдена")
            val language = completed.language ?: error("Язык записи не определён")
            val models = AppGraph.modelStore(this)
            val asrId = completed.modelIds.firstOrNull {
                ModelCatalog.byId(it).kind == BundleKind.ASR && it != ModelCatalog.WHISPER_TINY
            } ?: ModelCatalog.WHISPER_TINY
            val request = record.request
            val hasOverlap = OverlapPlan.hasOverlap(expected, completed.turns)
            val needsIsolation = request.separateOverlappingSpeech && hasOverlap
            val used = (listOf(asrId) + ModelCatalog.optionalModelIds(request, hasOverlap)).toMutableSet()
            requireReadyModels(used)
            notify(Progress("decoding audio", null))
            var stageStart = android.os.SystemClock.elapsedRealtime()
            val samples = AudioDecoder.decodeTo16k(this, Uri.parse(record.source.uri))
            timings["decode"] = android.os.SystemClock.elapsedRealtime() - stageStart
            require(expected.endMs <= samples.size / 16L) { "Реплика выходит за границы аудио" }
            prepareAudio(samples, models, request.denoise)?.let { timings["enhancement"] = it }
            checkCancellation()
            val backend = OnnxAcceleration.choose(this, "retry-asr")
            val isolation = if (needsIsolation)
                VoxLockIsolation(models.bundleDir(ModelCatalog.ISOLATION), cacheDir, samples, completed.turns) else null
            stageStart = android.os.SystemClock.elapsedRealtime()
            val text = try {
                if (isolation != null) require(isolation.hasReference(expected.speakerId)) {
                    "Недостаточно чистой речи для повторного выделения этого голоса. Исходный результат сохранён."
                }
                val asr = prepareAsr(models, asrId, language, backend.provider) { reports += it }
                try {
                    val chunks = dev.neurostenografist.core.Timeline.split(expected, 25000)
                    chunks.mapIndexed { i, crop ->
                        checkCancellation()
                        var audio = samples.copyOfRange((crop.startMs * 16).toInt(), (crop.endMs * 16).toInt())
                        if (isolation != null) audio = isolation.isolate(audio, expected.speakerId)
                        checkCancellation()
                        notify(Progress("transcribing", i.toFloat() / chunks.size))
                        asr.transcribe(audio)
                    }.joinToString(" ").trim()
                } finally {
                    used += asr.modelIds
                    asr.close()
                }
            } finally { isolation?.close() }
            timings["asr"] = android.os.SystemClock.elapsedRealtime() - stageStart
            require(text.isNotBlank()) { "Речь не распознана. Исходный текст сохранён." }
            checkCancellation()
            val edited = if (request.punctuation) {
                notify(Progress("punctuating", null))
                stageStart = android.os.SystemClock.elapsedRealtime()
                val candidate = punctuation(models).use { punctuator ->
                    punctuator.punctuate(text, language).also {
                        used.remove(ModelCatalog.PUNCTUATION)
                        used += punctuator.modelIds
                        reports += punctuator.backendReport
                    }
                }
                timings["punctuation"] = android.os.SystemClock.elapsedRealtime() - stageStart
                PunctuationGuard.accept(text, candidate)
            } else text
            reports += OnnxAcceleration.evidence(backend)
            checkCancellation()
            store.editTurn(runId, index, expected, expected.copy(text = edited),
                RetryEvidence(used.toList(), used.associateWith { id -> ModelCatalog.byId(id).files.joinToString(",") { it.sha256 } }, timings,
                    reports.map { "Повтор реплики: $it" }))
            AppGraph.recordError = "Реплика распознана повторно"
        } catch (failure: Exception) {
            AppGraph.recordError = if (failure is CancellationException) "Повтор отменён. Исходный текст сохранён."
                else "Не удалось повторить распознавание: ${failure.message}"
        } finally {
            finishRun(runId)
        }
    }

    /** Extract bundled assets on first use; processing never downloads models. */
    private suspend fun requireReadyModels(required: Iterable<String>) {
        val modelStore = AppGraph.modelStore(this)
        notify(Progress("checking models", null))
        for (id in required) {
            checkCancellation()
            if (!modelStore.isReady(id) && id in ModelCatalog.bundledIds) {
                modelStore.install(id) { progress ->
                    checkCancellation()
                    notify(progress)
                }
            }
            if (!modelStore.isReady(id)) {
                throw ModelBundleException("Подготовьте модель «${ModelCatalog.byId(id).displayName}» в разделе «Модели»")
            }
        }
    }

    private var lastNotificationMs = 0L
    private var lastNotificationStage: String? = null

    private fun notify(progress: Progress) {
        AppGraph.activeStage = progress.stage
        AppGraph.activeProgress = progress.fraction
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastNotificationStage == progress.stage && now - lastNotificationMs < 250 && progress.fraction != 1f) return
        lastNotificationMs = now
        lastNotificationStage = progress.stage
        val stage = stageLabel(progress.stage)
        val text = progress.fraction?.let { "$stage ${(it * 100).toInt()}%" } ?: stage
        val notification =
            builder()
                .setContentTitle("Расшифровка аудио")
                .setContentText(text)
                .setProgress(100, ((progress.fraction ?: 0f) * 100).toInt(), progress.fraction == null)
                .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    // Admission and teardown share the main thread: the old worker cannot stop a newly admitted run.
    private suspend fun finishRun(runId: String) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
        AppGraph.activeStage = null
        AppGraph.activeProgress = null
        AppGraph.delayedCancel = false
        AppGraph.releaseRun(runId)
    }

    override fun onDestroy() {
        if (job?.isActive == true) AppGraph.delayedCancel = true
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RUN_ID = "run_id"
        private const val EXTRA_TURN_INDEX = "turn_index"
        private const val NOTIF_ID = 41
        const val ACTION_CANCEL = "dev.neurostenografist.CANCEL_RUN"

        fun startRun(
            context: android.content.Context,
            runId: String,
        ) {
            AppGraph.ensureChannels(context)
            context.startForegroundService(Intent(context, ProcessingService::class.java).putExtra(EXTRA_RUN_ID, runId))
        }

        fun retryTurn(context: android.content.Context, runId: String, turnIndex: Int) {
            require(turnIndex >= 0)
            AppGraph.ensureChannels(context)
            context.startForegroundService(Intent(context, ProcessingService::class.java)
                .putExtra(EXTRA_RUN_ID, runId).putExtra(EXTRA_TURN_INDEX, turnIndex))
        }

        fun cancelActive(context: android.content.Context) {
            context.startService(Intent(context, ProcessingService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
