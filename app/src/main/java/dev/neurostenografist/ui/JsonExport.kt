package dev.neurostenografist.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.neurostenografist.core.Turn
import dev.neurostenografist.data.TranscriptJson
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * Export contract: every byte leaving the app reaches disk via [encodeFor]/[writeTo].
 * The public JSON is exactly [TranscriptJson.encode] — only the transcript array,
 * never run metadata, never a second rendering of the text.
 */
object JsonExport {
    const val MIME_JSON = "application/json"

    /** The one encoder used by Save and Share; identical bytes for identical turns. */
    fun encodeFor(turns: List<Turn>): String = TranscriptJson.encode(turns)

    /** Checked sink: any stream/write failure propagates as IOException, never as silent success. */
    fun writeTo(
        out: OutputStream,
        json: String,
    ) {
        out.write(json.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Writes the JSON into cacheDir/exports (the only directory exposed through the
     * FileProvider) and returns its content URI for ACTION_SEND sharing. Failures
     * propagate; the caller reports them instead of claiming the share was prepared.
     */
    fun writeShareFile(
        context: Context,
        turns: List<Turn>,
    ): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Keep earlier shares readable by receiving apps while their URI grant is active.
        // Android may reclaim cache files; an enduring copy belongs in the Save flow.
        val file = File(dir, "transcript-${UUID.randomUUID()}.json")
        file.outputStream().use { writeTo(it, encodeFor(turns)) }
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }
}
