package dev.neurostenografist

import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.Turn
import dev.neurostenografist.data.RunStore
import dev.neurostenografist.data.TranscriptJson
import dev.neurostenografist.ui.JsonExport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Export contract: what reaches Save/Share comes from RunStore.readCompleted runId,
 * encoded by TranscriptJson.encode, three times, byte-identical, decodable by an
 * independent reader, with only the public schema — never rendered or truncated text
 * and never run metadata.
 */
class ExportContractTest {
    @get:Rule val tmp = TemporaryFolder()

    private val source =
        AudioSource(
            id = "src-1",
            uri = "file:///data/sources/a.wav",
            displayName = "meeting.wav",
            durationMs = 90_000L,
        )

    private fun completedWithCyrillicQuotesNewlines(runId: String) =
        CompletedRun(
            id = runId,
            sourceId = source.id,
            turns =
                listOf(
                    Turn("speaker_1", 1250L, 4380L, "Добрый \"день\"!\nВторая строка\tтаб"),
                    Turn("speaker_2", 4380L, 9_512L, "- Yes. Fine."),
                    Turn("speaker_1", 9_512L, 12_000L, "Ок"),
                ),
            language = "ru",
            modelIds = listOf("whisper-tiny-multilingual", "diarization-pyannote-seg3"),
            modelHashes = mapOf("whisper-tiny-multilingual" to "abc123"),
            stageMs = mapOf("diarize" to 900L, "asr" to 4_200L),
            warnings = listOf("overlap ownership is approximate"),
        )

    @Test
    fun threeExportsOfSameStoredResultAreIdenticalJacksonIndependentDecodable() {
        val store = RunStore(tmp.newFolder("runs"))
        val runId = store.create(source, RunRequest("auto"))
        store.complete(completedWithCyrillicQuotesNewlines(runId))

        val stored = store.readCompleted(runId)
        val out =
            ByteArrayOutputStream()
                .also {
                    JsonExport.writeTo(it, JsonExport.encodeFor(stored.turns))
                }.toByteArray()

        val first = JsonExport.encodeFor(stored.turns)
        assertEquals(first, TranscriptJson.encode(stored.turns))
        assertEquals(first, String(out)) // written stream content equals encoded string

        val second = JsonExport.encodeFor(store.readCompleted(runId).turns)
        val third = JsonExport.encodeFor(store.readCompleted(runId).turns)
        assertEquals(first, second)
        assertEquals(first, third)

        // Independent decode (org.json, same reader a receiving app/free host uses)
        val root = JSONObject(String(out))
        val items = root.optJSONArray("transcript")
        assertNotNull(items)
        assertEquals(stored.turns.size, items.length())
        for (i in stored.turns.indices) {
            val obj = items.getJSONObject(i)
            val turn = stored.turns[i]
            assertEquals(turn.speakerId, obj.getString("speaker_id"))
            assertEquals(turn.startMs, obj.getLong("start_ms"))
            assertEquals(turn.endMs, obj.getLong("end_ms"))
            assertEquals(turn.text, obj.getString("text"))
        }
    }

    @Test
    fun writeFailurePropagatesInsteadOfReportingSaved() {
        val json = JsonExport.encodeFor(listOf(Turn("speaker_1", 0, 1, "x")))
        val broken =
            object : java.io.OutputStream() {
                override fun write(b: Int) = throw java.io.IOException("disk full")
            }
        try {
            JsonExport.writeTo(broken, json)
            org.junit.Assert.fail("write failure was silently swallowed")
        } catch (expected: java.io.IOException) {
            assertEquals("disk full", expected.message)
        }
        // a good stream still receives the full bytes after the failed attempt
        val good = ByteArrayOutputStream()
        JsonExport.writeTo(good, json)
        assertEquals(json, good.toString())
    }

    @Test
    fun exportFileContainsOnlyPublicSchemaForPersistedRun() {
        val store = RunStore(tmp.newFolder("runs"))
        val runId = store.create(source, RunRequest("auto"))
        store.complete(completedWithCyrillicQuotesNewlines(runId))

        val json = JsonExport.encodeFor(store.readCompleted(runId).turns)
        assertTrue(json.startsWith("""{"transcript"""))
        assertTrue(json.endsWith("}"))
        for (leaked in listOf(
            "\"sourceId\"",
            "\"modelIds\"",
            "\"stageMs\"",
            "\"language\"",
            "\"warnings\"",
            "\"createdAt\"",
            "\"status\"",
            "\"message\"",
        )) {
            assertTrue("metadata leak: $leaked", json.contains(leaked) == false)
        }
    }
}
