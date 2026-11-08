package dev.neurostenografist

import dev.neurostenografist.core.Turn
import dev.neurostenografist.data.TranscriptJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptJsonTest {
    @Test
    fun exactPublicSchema() {
        val encoded =
            TranscriptJson.encode(
                listOf(Turn("speaker_1", 1250L, 4380L, "Добрый день!")),
            )
        assertEquals(
            """{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}""",
            encoded,
        )

        val root = JSONObject(encoded)
        assertEquals(setOf("transcript"), root.keys().asSequence().toSet())
        val items = root.getJSONArray("transcript")
        assertEquals(1, items.length())
        val first = items.getJSONObject(0)
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), first.keys().asSequence().toSet())
        assertEquals("speaker_1", first.getString("speaker_id"))
        assertEquals(1250L, first.getLong("start_ms"))
        assertEquals(4380L, first.getLong("end_ms"))
        assertEquals("Добрый день!", first.getString("text"))
        assertFalse(first.get("start_ms") is Double)
        assertFalse(first.get("end_ms") is Double)
    }

    @Test
    fun emptyTranscriptIsAllowed() {
        assertEquals("""{"transcript":[]}""", TranscriptJson.encode(emptyList()))
        val root = JSONObject(TranscriptJson.encode(emptyList()))
        assertEquals(0, root.getJSONArray("transcript").length())
    }

    @Test
    fun escapesQuotesNewlinesAndKeepsCyrillic() {
        val text = "Привет \"мир\"!\nНовая строка\tтаб."
        val encoded =
            TranscriptJson.encode(
                listOf(Turn("speaker_2", 0L, 999L, text)),
            )
        assertTrue(encoded.contains("\\n"))
        assertTrue(encoded.contains("\\\""))
        assertTrue(encoded.contains("Привет"))
        val parsed = JSONObject(encoded).getJSONArray("transcript").getJSONObject(0).getString("text")
        assertEquals(text, parsed)
    }

    @Test
    fun keepsInsertionOrderOfTurns() {
        val encoded =
            TranscriptJson.encode(
                listOf(
                    Turn("speaker_1", 0L, 100L, "a"),
                    Turn("speaker_2", 100L, 200L, "b"),
                ),
            )
        val items = JSONObject(encoded).getJSONArray("transcript")
        assertEquals("speaker_1", items.getJSONObject(0).getString("speaker_id"))
        assertEquals("speaker_2", items.getJSONObject(1).getString("speaker_id"))
    }

    @Test
    fun keepsIntegerMillisecondsNotFloatingPoint() {
        for (ms in listOf(0L, 7L, 1_250L, 63_500L, 3_600_000L, 74_999_999L)) {
            val encoded = TranscriptJson.encode(listOf(Turn("speaker_1", ms, ms + 1, "x")))
            assertTrue("no floating ms in: $encoded", Regex("\"start_ms\":$ms,").containsMatchIn(encoded))
            assertTrue("no floating ms in: $encoded", Regex("\"end_ms\":${ms + 1},").containsMatchIn(encoded))
            assertFalse(encoded.contains(".0,") || encoded.contains(".0}"))
        }
    }

    @Test
    fun repeatedExportsOfSameTurnsAreByteIdentical() {
        val turns =
            listOf(
                Turn("speaker_1", 1250L, 4380L, "Добрый \"день\"!\nВторая строка"),
                Turn("speaker_2", 4380L, 9_000L, "- Yes. Fine."),
            )
        val first = TranscriptJson.encode(turns)
        assertEquals(first, TranscriptJson.encode(turns))
        assertEquals(first, TranscriptJson.encode(turns))
    }

    @Test
    fun leaksNoRunMetadata() {
        val encoded =
            TranscriptJson.encode(listOf(Turn("speaker_1", 1250L, 4380L, "Текст")))
        for (leaked in listOf(
            "\"id\"",
            "\"runId\"",
            "\"language\"",
            "\"model\"",
            "\"status\"",
            "\"stageMs\"",
            "\"warnings\"",
            "\"message\"",
            "\"createdAt\"",
        )) {
            assertFalse("unexpected key $leaked in export", encoded.contains(leaked))
        }
        assertEquals(setOf("transcript"), JSONObject(encoded).keys().asSequence().toSet())
    }
}
