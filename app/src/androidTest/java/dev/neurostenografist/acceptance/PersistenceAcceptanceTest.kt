package dev.neurostenografist.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.core.Turn
import dev.neurostenografist.data.RunStore
import dev.neurostenografist.data.SettingsStore
import dev.neurostenografist.data.SourceStore
import dev.neurostenografist.data.TranscriptJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device persistence acceptance: real file-backed stores (RunStore /
 * SourceStore / SettingsStore) and the exported transcript JSON contract.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runStore_atomicLifecycle_and_interruptionRecovery() {
        val root =
            File(context.cacheDir, "acceptance-runs").apply {
                deleteRecursively()
                mkdirs()
            }
        val store = RunStore(root)
        val source = AudioSource(id = "s1", uri = "file://x", displayName = "clip.wav", durationMs = 12_345)
        val id = store.create(source, RunRequest(modelId = "auto"))
        assertEquals(RunStatus.CREATED, store.read(id)?.status)
        store.markRunning(id)
        assertEquals(RunStatus.RUNNING, store.read(id)?.status)
        val completed =
            CompletedRun(
                id = id,
                sourceId = source.id,
                turns = listOf(Turn(speakerId = "S0", startMs = 500, endMs = 1500, text = "привет")),
                language = "ru",
                modelIds = listOf("whisper-tiny-multilingual", "diarization-pyannote-seg3"),
                modelHashes = mapOf("whisper-tiny-multilingual" to "abc123"),
                stageMs = linkedMapOf("diarization" to 10L, "language" to 2L, "asr" to 30L),
                warnings = listOf("speaker clustering is automatic and not calibrated"),
            )
        store.complete(completed)
        val record = store.read(id)?.completed
        assertEquals(completed, record)

        // a process death mid-run must be recoverable to interrupted on cold start
        val staleId = store.create(source, RunRequest(modelId = "auto"))
        store.markRunning(staleId)
        store.markInterruptedOnRestart()
        assertEquals(RunStatus.INTERRUPTED, store.read(staleId)?.status)
        assertEquals(RunStatus.COMPLETED, store.read(id)?.status)
        root.deleteRecursively()
    }

    @Test
    fun sourceStore_upsertDelete_roundTrip() {
        val file = File(context.cacheDir, "acceptance-sources.json")
        file.delete()
        val store = SourceStore(file)
        store.upsert(AudioSource(id = "a", uri = "file://a", displayName = "a.wav", durationMs = null))
        store.upsert(AudioSource(id = "b", uri = "file://b", displayName = "b.wav", durationMs = 1000))
        assertEquals(listOf("b", "a"), store.list().map { it.id })
        store.upsert(AudioSource(id = "a", uri = "file://a2", displayName = "a.wav", durationMs = 7))
        val reloaded = SourceStore(file).list()
        assertEquals(2, reloaded.size)
        assertEquals(7, reloaded.first { it.id == "a" }.durationMs)
        store.delete("b")
        assertEquals(listOf("a"), SourceStore(file).list().map { it.id })
        file.delete()
    }

    @Test
    fun transcriptJson_exportContract() {
        val turns =
            listOf(
                Turn(speakerId = "S0", startMs = 250, endMs = 1234, text = "привет мир"),
                Turn(speakerId = "S1", startMs = 1500, endMs = 2100, text = "hello"),
            )
        val exported = JSONObject(TranscriptJson.encode(turns))
        assertTrue(exported.getJSONArray("transcript").length() == 2)
        val first = exported.getJSONArray("transcript").getJSONObject(0)
        assertEquals("S0", first.getString("speaker_id"))
        assertEquals(250, first.getInt("start_ms"))
        assertEquals(1234, first.getInt("end_ms"))
        assertEquals("привет мир", first.getString("text"))
    }

    @Test
    fun settingsStore_roundTrip_and_validation() {
        val store = SettingsStore(context)
        val original = store.recordSampleRate
        try {
            store.recordSampleRate = 48_000
            assertEquals(48_000, SettingsStore(context).recordSampleRate)
            try {
                store.recordSampleRate = 44_100
                throw AssertionError("unsupported rate must be rejected")
            } catch (expected: IllegalArgumentException) {
                // required: invalid recording rate is an actionable error, never a silent knob
            }
        } finally {
            store.recordSampleRate = original
        }
    }
}
