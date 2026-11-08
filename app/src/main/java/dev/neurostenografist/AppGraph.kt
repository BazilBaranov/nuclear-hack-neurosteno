package dev.neurostenografist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.neurostenografist.data.RunStore
import dev.neurostenografist.data.SettingsStore
import dev.neurostenografist.data.SourceStore
import dev.neurostenografist.inference.ModelStore
import java.io.File

/** Verdict of [AppGraph.decideRunStart] for an extra ProcessingService start intent. */
enum class RunStartDecision {
    /** No run id was provided; nothing to do. */
    IGNORE_NO_RUN,

    /** The requested run is already owned by the live service instance. */
    IGNORE_DUPLICATE,

    /** Admission won and the claim is now held; the caller must start the run. */
    START_RUN,

    /** Rejected because another RUN owns the process: stopping the service here
     *  would cancel the active owner's job, so the service must survive. */
    REJECT_RUN_OWNER_ACTIVE,

    /** Rejected with no run owner (recording active or a foreign claim): this idle
     *  start instance may stop itself safely. */
    REJECT_IDLE_STOP,
}

/**
 * Single process-wide wiring point; services and UI construct stores only through it.
 *
 * Also owns the two global claims from the offline plan (one recording OR one inference
 * run in flight, including pending starts and finalization) and the recording clock the
 * UI mirrors. Claims are released only by the component that acquired them and only
 * after final cleanup, so a duplicate start can never leave a successful-looking
 * orphan job.
 */
object AppGraph {
    // ---- atomic admission: at most one of {inference run, recording} at a time ----
    private val admissionLock = Any()

    @Volatile private var runOwner: String? = null

    @Volatile private var recordingOwner = false

    // Owner of finalizing a recording (one recorder, one finalizer until cleanup): a
    // repeated Stop/Abort intent must not clear recorder/claim state while the first
    // finalizer is still publishing. The owner key is the recorder being finalized.
    private val recordingFinalizer =
        java.util.concurrent.atomic
            .AtomicReference<Any?>(null)

    /** Claims exclusive finalization of [recorder]. Returns false when a finalizer
     *  already owns it. */
    fun claimFinalizer(recorder: Any): Boolean = recordingFinalizer.compareAndSet(null, recorder)

    /** Releases finalization ownership; only the owner actually held it releases. */
    fun releaseFinalizer(recorder: Any): Boolean = recordingFinalizer.compareAndSet(recorder, null)

    /** True when some finalizer currently owns the given recorder. */
    fun finalizerActive(recorder: Any): Boolean = recordingFinalizer.get() == recorder

    /** Wall-clock timestamp of the recording start (0 = not recording). */
    @Volatile var recordingStartedAtMs: Long = 0
        private set

    /** The run actually being executed by ProcessingService, or null. */
    val activeRunId: String? get() = runOwner

    /** What [dev.neurostenografist.ProcessingService.onStartCommand] must do with an
     *  extra start intent; the decision (including the atomic claim attempt) is a
     *  seam here so the service's admission branch is host-testable without an
     *  Android Service instance. */
    fun decideRunStart(requestedRunId: String?): RunStartDecision {
        if (requestedRunId == null) return RunStartDecision.IGNORE_NO_RUN
        // A request already owned by the live service instance is ignored without
        // stopping it — the owner is this very service's job.
        if (runOwner == requestedRunId) return RunStartDecision.IGNORE_DUPLICATE
        if (claimRun(requestedRunId)) return RunStartDecision.START_RUN
        // Admission denied: another job owns the process. If that owner is a run, the
        // live service instance owns that job and must NOT stop itself (a second,
        // different-id start used to stopSelf() the active owner); only when the
        // process is merely recording (or otherwise run-free) is this an idle start.
        return if (runOwner != null) RunStartDecision.REJECT_RUN_OWNER_ACTIVE else RunStartDecision.REJECT_IDLE_STOP
    }

    /** Whether a microphone recording owns the process (until publication completes). */
    val recording: Boolean get() = recordingOwner

    /** A run or recording is in flight or a start is pending. */
    val isBusy: Boolean get() = runOwner != null || recordingOwner

    /** [delayedCancel] set by the processing cancel action; honoured between stages. */
    @Volatile var delayedCancel: Boolean = false

    @Volatile var activeStage: String? = null

    @Volatile var activeProgress: Float? = null

    /** Attempt to admit a run exclusively. Returns false when another job owns the process. */
    fun claimRun(runId: String): Boolean =
        synchronized(admissionLock) {
            if (runOwner != null || recordingOwner) return false
            runOwner = runId
            delayedCancel = false // a fresh run must not inherit a stale cancel request
            true
        }

    /** Releases the run claim only when this component still owns it. */
    fun releaseRun(runId: String) {
        synchronized(admissionLock) {
            if (runOwner == runId) runOwner = null
        }
    }

    /** Attempt to admit a recording exclusively. Returns false when another job owns the process. */
    fun claimRecording(): Boolean =
        synchronized(admissionLock) {
            if (runOwner != null || recordingOwner) return false
            recordingOwner = true
            recordingStartedAtMs = System.currentTimeMillis()
            true
        }

    /** Releases the recording claim and stops the mirrored clock. */
    fun releaseRecording() {
        synchronized(admissionLock) {
            recordingOwner = false
            recordingStartedAtMs = 0
        }
    }

    @Volatile private var runStoreRef: RunStore? = null

    fun runStore(context: Context): RunStore =
        runStoreRef ?: synchronized(this) {
            runStoreRef ?: RunStore(File(context.filesDir, "runs")).also { runStoreRef = it }
        }

    private fun runStore(): RunStore? = runStoreRef

    fun sourceStore(context: Context): SourceStore = SourceStore(File(context.filesDir, "sources.json"))

    fun modelStore(context: Context): ModelStore = ModelStore(context, isBusy = { isBusy })

    fun settingsStore(context: Context): SettingsStore = SettingsStore(context)

    fun activeRun(context: Context): RunStore? = runStore(context)

    /** Published by RecordingService when a recording completes or fails. */
    @Volatile var recordError: String? = null

    @Volatile var recordingResult: RecordingDone? = null

    data class RecordingDone(
        val uri: String,
        val displayName: String,
        val durationMs: Long,
    )

    private val recoveryLock = Any()

    @Volatile private var recovered = false

    /** Marks runs left `running` by a previous process (no live owner in this one) as
     *  `interrupted`. Runs at cold start only: equivalent calls in the same process
     *  are no-ops, so an in-flight run is never disturbed on Activity recreation. */
    fun onProcessStart(context: Context) {
        if (recovered) return
        synchronized(recoveryLock) {
            if (recovered) return
            runStore(context).markInterruptedOnRestart()
            recovered = true
        }
    }

    /** Test-visible admission state reset; production code must not call this. */
    fun resetForTest() {
        synchronized(admissionLock) {
            runOwner = null
            recordingOwner = false
            recordingStartedAtMs = 0
        }
        recordingFinalizer.set(null)
        recovered = false
        delayedCancel = false
        activeStage = null
        activeProgress = null
        recordError = null
        recordingResult = null
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CH_PROCESS, "Расшифровка", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_RECORD, "Запись", NotificationManager.IMPORTANCE_LOW),
        )
    }

    const val CH_PROCESS = "processing"
    const val CH_RECORD = "recording"
}
