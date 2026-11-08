package dev.neurostenografist

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.neurostenografist.audio.Recorder
import dev.neurostenografist.audio.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground microphone service; publishes the finished WAV to public Downloads.
 * Recording stays "recording" in the UI until publication and source persistence
 * finish, because the process claim ([AppGraph.claimRecording]) is held until then.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: Recorder? = null

    /** Set when capture starts; one finalizer owns the recorder until cleanup, so a
     *  repeated Stop/Abort must not clear recorder/claim state mid-publication. */
    private var finalizerSession: RecordingSession? = null
    private var job: Job? = null

    @Volatile private var busy = false

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
        when (intent.action) {
            ACTION_STOP -> {
                stopRecording(publish = true)
                return START_NOT_STICKY
            }

            ACTION_ABORT -> {
                stopRecording(publish = false)
                return START_NOT_STICKY
            }

            else -> {
                // Duplicate start intents land on the live service instance: this owner
                // must survive them, so an active recording is rejected without stopping.
                if (recorder != null || busy) {
                    AppGraph.recordError = "Запись уже идёт"
                    return START_NOT_STICKY
                }
                // Atomic admission: one recording OR one transcription run, including
                // pending starts. A denied start never reaches the foreground entry.
                if (!AppGraph.claimRecording()) {
                    AppGraph.recordError = "Дождитесь завершения текущей обработки"
                    stopSelf() // no owner here: stopping this idle start is safe
                    return START_NOT_STICKY
                }
                busy = true
                val sampleRate = intent.getIntExtra(EXTRA_RATE, 16_000)
                startInForeground()
                val rec = Recorder(sampleRate)
                try {
                    rec.startCatch(this)
                } catch (failure: IllegalStateException) {
                    AppGraph.recordError = failure.message ?: "Не удалось начать запись"
                    recorder = null
                    busy = false
                    AppGraph.releaseRecording()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                recorder = rec
                finalizerSession = RecordingSession(rec, AppGraph.recordingStartedAtMs)
                job =
                    scope.launch {
                        // Ticker keeps the notification alive with real elapsed time.
                        while (recorder != null) {
                            notifyTicker()
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                return START_NOT_STICKY
            }
        }
    }

    private fun stopRecording(publish: Boolean) {
        val session = finalizerSession ?: return
        val rec = recorder ?: return
        job?.cancel()
        // Single finalizer: the first Stop/Abort owns this recorder exclusively (the
        // claim lives in AppGraph and is host-testable) until cleanup completes. A
        // repeated Stop/Abort must return here — clearing recorder/claim state now
        // would race the first finalizer's publish; unknown-intent callers stay silent.
        if (!AppGraph.claimFinalizer(rec)) return
        // Ownership (recorder/busy/claim) is held until cleanup completes. If destroy
        // cancels this coroutine before it starts, onDestroy performs the identical
        // cleanup — no path can strand the native recorder or the claim.
        scope.launch {
            try {
                if (publish) {
                    // stop() joins capture, finalizes the WAV and throws on capture
                    // failure or empty recording; the session publishes and deletes
                    // the temp WAV (our own output; the published copy lives in Downloads)
                    val outcome =
                        session.stop { wav, name ->
                            rec.publish(this@RecordingService, wav, name).toString()
                        }
                    AppGraph.activeStage = "saving recording"
                    AppGraph.sourceStore(this@RecordingService).upsert(
                        dev.neurostenografist.core.AudioSource(
                            id = UUID.randomUUID().toString(),
                            uri = requireNotNull(outcome.uri),
                            displayName = outcome.displayName,
                            durationMs = outcome.durationMs,
                        ),
                    )
                    AppGraph.recordingResult =
                        AppGraph.RecordingDone(outcome.uri, outcome.displayName, outcome.durationMs)
                } else {
                    session.abort()
                }
            } catch (failure: Throwable) {
                runCatching { session.abort() } // surface no orphan recorder on any failure
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                AppGraph.recordError = failure.message ?: "Не удалось сохранить запись"
            } finally {
                AppGraph.releaseFinalizer(rec) // only the owner releases
                if (recorder == rec) { // destroy may have cleaned up already
                    recorder = null
                    busy = false
                    finalizerSession = null
                    AppGraph.releaseRecording()
                    AppGraph.activeStage = null
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startInForeground() {
        val notification = ticker()
        // FGS type constant is API 30+; the 3-arg startForeground overload exists since 29
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notifyTicker() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, ticker())
    }

    private fun ticker(): Notification =
        NotificationCompat
            .Builder(this, AppGraph.CH_RECORD)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("Запись разговора")
            .setContentText("Нажмите «Остановить», чтобы сохранить запись в Загрузки/neurostenografist")
            .addAction(
                0,
                "Остановить",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()

    override fun onDestroy() {
        // Cancellation can abort the finalizer coroutine before its body (and its
        // finally) ever runs, so destroy takes over the finalizer claim and does the
        // full cleanup itself while the owner is set. Both callbacks run on the main
        // thread, so the claim state read here is race-free against onStartCommand.
        val session = finalizerSession
        finalizerSession = null
        val pending = recorder
        job?.cancel()
        if (pending == null) {
            scope.cancel()
            super.onDestroy()
            return
        }
        recorder = null
        busy = false
        AppGraph.releaseRecording() // system-killed service must not strand the claim
        AppGraph.activeStage = null
        if (AppGraph.claimFinalizer(pending)) { // takeover: no finalizer owns the capture
            scope.cancel() // any queued finalizer coroutine would double-cleanup
            runCatching { session?.abort() }
            AppGraph.releaseFinalizer(pending)
        } else {
            // A finalizer owns cleanup (Stop/Abort in flight): keep the scope alive so
            // its coroutine — even one not yet dispatched — runs to completion.
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RATE = "sample_rate"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "dev.neurostenografist.STOP_RECORDING"
        const val ACTION_ABORT = "dev.neurostenografist.ABORT_RECORDING"

        fun start(
            context: android.content.Context,
            sampleRate: Int,
        ) {
            AppGraph.ensureChannels(context)
            context.startForegroundService(
                Intent(context, RecordingService::class.java)
                    .putExtra(EXTRA_RATE, sampleRate),
            )
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        fun abort(context: android.content.Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_ABORT))
        }
    }
}
