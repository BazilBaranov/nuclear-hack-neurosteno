package dev.neurostenografist.audio

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Coverage: [RecordingSession] finalization — a finished capture is finalized,
 *  published through the caller's sink, the temp WAV is always deleted (success or
 *  publish failure), the outcome carries the published uri and duration, and both the
 *  no-publish (null sink) and abort paths consume the recorder terminal state. Drives
 *  [RecorderHandle] with a fake so no Android recorder hardware is touched. */
class RecordingSessionTest {
    /** Fake recorder: stop() yields a temp WAV and a fixed duration; the publish sink
     *  is supplied by each test, so no Android Context is ever needed. */
    private class FakeRecorder : RecorderHandle {
        var stoppedCount = 0
        var abortedCount = 0
        var lastTempWav: File? = null

        /** Not used by these tests: publication is routed through the session's sink. */
        var published: Pair<String, File>? = null

        override fun stop(): Pair<File, Long> {
            stoppedCount++
            val wav = File.createTempFile("session-test", ".wav")
            wav.writeBytes(ByteArray(16))
            lastTempWav = wav
            return wav to 42_000L
        }

        override fun abort() {
            abortedCount++
        }

        override fun publish(
            context: android.content.Context,
            wav: File,
            displayName: String,
        ): Uri {
            published = displayName to wav
            return Uri.parse("content://downloads/${'$'}{wav.name}")
        }
    }

    @Test
    fun `stop publishes through the sink and consumes the temp wav`() {
        val rec = FakeRecorder()
        val seen = mutableListOf<Pair<String, File>>()
        val outcome =
            RecordingSession(rec).stop { wav, name ->
                seen.add(name to wav)
                "content://downloads/out.wav"
            }
        assertEquals(1, rec.stoppedCount)
        assertEquals(0, rec.abortedCount)
        assertEquals(42_000L, outcome.durationMs)
        assertTrue(Regex("""\d{2}\.\d{2}\.\d{4}-\d{2}：\d{2}：\d{2}\.wav""").matches(outcome.displayName))
        assertEquals(listOf<Pair<String, File>>(outcome.displayName to rec.lastTempWav!!), seen)
        assertEquals("content://downloads/out.wav", outcome.uri)
        assertFalse("temp WAV must be deleted after publication", rec.lastTempWav!!.exists())
    }

    @Test
    fun `different recording start times generate distinct names`() {
        val rec = FakeRecorder()
        val one = RecordingSession(rec, 0L).stop { _, _ -> "content://downloads/1.wav" }
        val two = RecordingSession(rec, 1000L).stop { _, _ -> "content://downloads/2.wav" }
        assertNotEquals(one.displayName, two.displayName)
    }

    @Test
    fun `stop without a sink finalizes without publishing`() {
        val rec = FakeRecorder()
        val outcome = RecordingSession(rec).stop(null)
        assertEquals(1, rec.stoppedCount)
        assertEquals(0, rec.abortedCount)
        assertEquals(42_000L, outcome.durationMs)
        assertNull(outcome.uri)
        assertFalse(rec.lastTempWav!!.exists())
    }

    @Test
    fun `publish failure deletes the temp wav and abort terminates the recorder`() {
        val rec = FakeRecorder()
        val session = RecordingSession(rec)
        val outcome =
            runCatching {
                session.stop { _, _ -> error("publish rejected") }
            }
        assertTrue(outcome.isFailure)
        assertEquals("publish rejected", assertIs<IllegalStateException>(outcome.exceptionOrNull()).message)
        assertFalse("publish failure must not strand the temp WAV", rec.lastTempWav!!.exists())
        // the caller owes the recorder an abort on any finalization failure
        session.abort()
        assertEquals(1, rec.abortedCount)
    }

    @Test
    fun `abort without a stop discards the capture`() {
        val rec = FakeRecorder()
        RecordingSession(rec).abort()
        assertEquals(0, rec.stoppedCount)
        assertEquals(1, rec.abortedCount)
    }
}

private inline fun <reified T : Throwable> assertIs(t: Throwable?): T = checkNotNull(t) { "expected exception" } as T
