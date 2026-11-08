package dev.neurostenografist.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Microphone capture to mono PCM (16 or 48 kHz, user setting) that keeps the exact
 * real RMS of the recorded signal, writes a proper WAV, and publishes to the public
 * Downloads collection. Nothing here synthesizes samples or durations.
 *
 * Failure rules from the offline plan: hardware is validated up front; capture-thread
 * failures are propagated at stop; the WAV size is verified after finalization; a
 * failed save never reaches Downloads; a pending MediaStore row is cleaned up when
 * publication fails; only this recorder's own temporary files are ever deleted.
 */
class Recorder(
    private val sampleRate: Int,
) : RecorderHandle {
    private var record: AudioRecord? = null

    @Volatile private var recording = false
    private var pcmFile: File? = null
    private var thread: Thread? = null

    @Volatile private var captureError: Throwable? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO explicitly
    fun startCatch(context: Context): File {
        check(!recording) { "already recording" }
        require(sampleRate == 16_000 || sampleRate == 48_000) {
            "unsupported capture sample rate $sampleRate; use 16000 or 48000"
        }
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, FORMAT)
        check(minBuf > 0) { "no valid AudioRecord buffer for $sampleRate Hz" }
        val tmp = File(context.cacheDir, "rec-${System.currentTimeMillis()}.pcm")
        val rec =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL,
                FORMAT,
                minBuf * 4,
            )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed; microphone permission or hardware" }
        record = rec
        pcmFile = tmp
        captureError = null
        recording = true
        rec.startRecording()
        thread =
            Thread { capture(rec, tmp) }.also {
                it.start()
            }
        return tmp
    }

    private fun capture(
        rec: AudioRecord,
        tmp: File,
    ) {
        try {
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(BYTES_PER_READ)
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        captureError = IllegalStateException("capture stream failed with code $n from AudioRecord")
                        return
                    }
                    if (n > 0) out.write(buf, 0, n)
                }
            }
        } catch (e: Throwable) {
            captureError = e
        }
    }

    /** Joins capture, releases the AudioRecord, finalizes the WAV. The AudioRecord and
     *  the temp capture file are cleaned up even when the join times out (the check
     *  that throws runs after release, never before). Throws when capture failed or
     *  the recording is empty; no temp file or native resource survives a failure. */
    @Synchronized
    override fun stop(): Pair<File, Long> {
        check(recording) { "not recording" }
        recording = false
        try {
            val th = thread
            th?.join(3000)
            // Release first: a stalled capture thread or any later failure must not
            // leave the microphone/native resource behind.
            val rec = record
            record = null
            try {
                rec?.stop()
            } finally {
                rec?.release()
            }
            check(th?.isAlive != true) { "capture thread did not stop" }
            val pcm = checkNotNull(pcmFile) { "no capture file" }
            captureError?.let { err ->
                throw IllegalStateException("capture failed", err)
            }
            val wav = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
            try {
                val pcmBytes = WavWriter.finalizeMono16(pcm, wav, sampleRate)
                val durationMs = pcmBytes * 1000L / (2 * sampleRate) // 16-bit mono
                pcm.delete()
                pcmFile = null
                return wav to durationMs
            } catch (e: Throwable) {
                wav.delete() // cleanup only owned unfinished output; finalizeMono16 already removed failed WAVs
                pcm.delete()
                pcmFile = null
                throw e
            }
        } finally {
            // the capture-failure/stall paths above must not strand the temp capture file
            pcmFile?.delete()
            pcmFile = null
        }
    }

    @Synchronized
    override fun abort() {
        if (!recording && record == null) { // a finalize-time-out path already released
            pcmFile?.delete()
            pcmFile = null
            return
        }
        recording = false
        thread?.join(2000)
        record?.release()
        record = null
        pcmFile?.delete()
        pcmFile = null
        captureError = null
    }

    /**
     * Publishes a finished WAV to public Downloads. API 29+ uses MediaStore
     * (no storage permission needed; a failed publication removes its pending row —
     * nothing is visible in Downloads unless publication fully succeeded); older
     * builds write the Downloads directory, which requires WRITE_EXTERNAL_STORAGE
     * (manifest-limited to API<=28; rejected with an actionable error otherwise).
     */
    override fun publish(
        context: Context,
        wav: File,
        displayName: String,
    ): Uri {
        check(wav.isFile && wav.length() > WAV_HEADER_BYTES) { "refusing to publish missing or empty WAV ${wav.name}" }
        val safeName = sanitizeDownloadName(displayName)
        if (Build.VERSION.SDK_INT >= 29) {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$RECORDINGS_FOLDER/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val resolver = context.contentResolver
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore rejected download insert")
            try {
                val copied =
                    resolver.openOutputStream(uri)?.use { out ->
                        wav.inputStream().use { it.copyTo(out) }
                    } ?: error("cannot write to $uri")
                check(copied == wav.length()) { "incomplete publication: copied $copied of ${wav.length()} bytes" }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) { "cannot finalize $uri" }
                return uri
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) } // no orphan pending row
                throw IllegalStateException("publication to Downloads failed", e)
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("writing Downloads on this Android version needs the WRITE_EXTERNAL_STORAGE permission")
            }
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), RECORDINGS_FOLDER)
            check(dir.isDirectory || dir.mkdirs()) { "cannot create $dir" }
            val stem = safeName.substringBeforeLast('.')
            val out = generateSequence(0) { it + 1 }
                .map { index -> File(dir, if (index == 0) safeName else "$stem ($index).wav") }
                .first { it.createNewFile() }
            try {
                val copied =
                    wav.inputStream().use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                check(copied == wav.length()) { "incomplete publication: copied $copied of ${wav.length()} bytes" }
                return Uri.fromFile(out)
            } catch (e: Throwable) {
                out.delete() // clean only our own unfinished output
                throw IllegalStateException("publication to Downloads failed", e)
            }
        }
    }

    companion object {
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_READ = 4096
        private const val WAV_HEADER_BYTES = 44L
        private const val RECORDINGS_FOLDER = "neurostenografist"

        /** Import and recording names are untrusted input: no separators, no traverse. */
        private fun sanitizeDownloadName(name: String): String {
            val cleaned =
                name
                    .replace(Regex("[/\\\\]"), "_")
                    .replace(Regex("\\.\\."), "_")
                    .trim()
                    .ifEmpty { "recording.wav" }
            return if (cleaned.endsWith(".wav", ignoreCase = true)) cleaned else "$cleaned.wav"
        }
    }
}

private fun OutputStream.copyBytesFrom(input: InputStream) {
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        write(buf, 0, n)
    }
}

/** Minimal canonical mono 16-bit WAV writer; duration comes from actual bytes. */
object WavWriter {
    fun writeMono16(
        out: OutputStream,
        source: InputStream,
        sampleRate: Int,
        pcm16: Long,
    ) {
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32((36 + pcm16).toInt()))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(le32(16))
        out.write(le16(1)) // PCM
        out.write(le16(1)) // mono
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * 2)) // byte rate, 16-bit mono
        out.write(le16(2)) // block align
        out.write(le16(16)) // bits
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le32(pcm16.toInt()))
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    /** Writes the WAV from raw PCM and verifies the resulting size against the
     *  exact expected length; deletes the WAV on any failure or mismatch. */
    fun finalizeMono16(
        pcm: File,
        wav: File,
        sampleRate: Int,
    ): Long {
        val pcmBytes = pcm.length()
        check(pcmBytes > 0) { "capture produced no audio (${pcm.name})" }
        try {
            writeMono16(FileOutputStream(wav), pcm.inputStream(), sampleRate, pcmBytes)
        } catch (e: Throwable) {
            wav.delete()
            throw e
        }
        val expected = WAV_HEADER + pcmBytes
        if (wav.length() != expected) {
            wav.delete()
            error("WAV finalization wrote ${wav.length()} of $expected bytes")
        }
        return pcmBytes
    }

    private const val WAV_HEADER = 44L

    private fun le32(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(),
            (
                (
                    v shr
                        24
                ) and
                    0xff
            ).toByte(),
        )

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
}
