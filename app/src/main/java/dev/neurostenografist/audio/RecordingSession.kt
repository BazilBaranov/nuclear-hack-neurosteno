package dev.neurostenografist.audio

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recorder surface consumed by [RecordingSession]: capture terminalization and
 *  publication. Implemented by [Recorder]; a fake drives host tests. */
interface RecorderHandle {
    /** Joins capture, releases the AudioRecord, finalizes the WAV (see [Recorder.stop]). */
    fun stop(): Pair<File, Long>

    /** Discards the capture (see [Recorder.abort]). */
    fun abort()

    /** Publishes a finished WAV to public Downloads (see [Recorder.publish]). */
    fun publish(
        context: android.content.Context,
        wav: File,
        displayName: String,
    ): Uri
}

/** Immutable outcome of finalizing a recording. Pure data so the finalize path is
 *  host-testable without an Android Context. */
data class RecordingFinalized(
    val displayName: String,
    val durationMs: Long,
    /** Published Downloads uri, or null when nothing was published. */
    val uri: String? = null,
)

/**
 * Finalization seam for the recording owner: wraps one [recorder] plus the actions
 * that must run when its capture is consumed. [stop] turns a finished capture into a
 * published source via [publishTo] (a null sink finalizes without publishing); [abort]
 * discards. The recorder's terminal state is consumed exactly once — the owning
 * service guarantees this with its single-finalizer claim.
 */
class RecordingSession(
    val recorder: RecorderHandle,
    private val startedAtMs: Long = System.currentTimeMillis(),
) {
    /** Joins capture, finalizes the WAV, publishes it through [publishTo] (null = no
     *  publication), deletes the temp WAV, returns the outcome. Throws when capture,
     *  finalization, or publication fails; the caller then aborts the recorder's
     *  terminal state. */
    fun stop(publishTo: ((wav: File, displayName: String) -> String)?): RecordingFinalized {
        val (wav, durationMs) = recorder.stop()
        try {
            // MediaStore replaces ASCII colons in public filenames.
            val displayName = SimpleDateFormat("dd.MM.yyyy-HH'：'mm'：'ss'.wav'", Locale.ROOT)
                .format(Date(startedAtMs))
            val uri = publishTo?.let { it(wav, displayName) }
            return RecordingFinalized(displayName, durationMs, uri)
        } finally {
            wav.delete() // our own temp output; the published copy lives in Downloads
        }
    }

    /** Discards the capture; also terminates an already-stopped recorder safely. */
    fun abort() {
        recorder.abort()
    }
}
