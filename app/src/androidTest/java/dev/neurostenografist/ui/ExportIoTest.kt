package dev.neurostenografist.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.Turn
import dev.neurostenografist.data.RunStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device export contract: the saved/shared JSON is produced from
 * [RunStore.readCompleted] only (public schema), every export of the same result is
 * byte-identical, an independent decoder reads it, the FileProvider share file is
 * readable through the granted content URI, and a write failure is not reported as
 * saved. Uses the app's real filesDir-backed [RunStore] (cleared per test).
 */
@RunWith(AndroidJUnit4::class)
class ExportIoTest {
    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(): CompletedRun =
        CompletedRun(
            id = "eio-${System.nanoTime()}",
            sourceId = "src-1",
            turns =
                listOf(
                    Turn("speaker_1", 1250, 4380, "Привет!\nВторая строка"),
                    Turn("speaker_2", 4380, 9512, "Fine."),
                ),
            language = "ru",
            modelIds = listOf("zipformer-ru-small", "diarization-pyannote-seg3"),
            modelHashes = mapOf("zipformer-ru-small" to "hash1", "diarization-pyannote-seg3" to "hash2"),
            stageMs = mapOf("asr" to 4200L, "diarization" to 1600L),
            warnings = listOf(),
        )

    @Test
    fun threeExportsDecodeIndependentlyAgainstReadCompleted() {
        val ctx = context()
        val runs = RunStore(File(ctx.filesDir, "runs-eio").apply { mkdirs() })
        val completed = fixture()
        val runId =
            runs.create(
                source = dev.neurostenografist.core.AudioSource("src-1", "android(chain://x)", "a.wav", 9512L),
                request = dev.neurostenografist.core.RunRequest("auto"),
            )
        runs.complete(completed.copy(id = runId))
        val fromStore = runs.readCompleted(runId)
        val jsons = List(3) { JsonExport.encodeFor(fromStore.turns) }
        assertTrue(jsons[0] == jsons[1] && jsons[1] == jsons[2])
        val decoded = JSONObject(jsons[0]).getJSONArray("transcript")
        assertEquals(fromStore.turns.size, decoded.length())
        for (i in fromStore.turns.indices) {
            assertEquals(fromStore.turns[i].speakerId, decoded.getJSONObject(i).optString("speaker_id"))
            assertEquals(fromStore.turns[i].startMs, decoded.getJSONObject(i).optLong("start_ms"))
        }
        runs.deleteRun(runId)
    }

    @Test
    fun shareFileIsReadableThroughFileProviderUri() {
        val ctx = context()
        val completed = fixture()
        val uri = JsonExport.writeShareFile(ctx, completed.turns)
        assertEquals("${ctx.packageName}.exports", uri.authority)
        assertEquals("application/json", ctx.contentResolver.getType(uri))
        val read = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(JsonExport.encodeFor(completed.turns), read.toString(Charsets.UTF_8))
        JsonExport.writeShareFile(ctx, completed.turns)
        // A second share must not invalidate the URI still being read by another app.
        assertEquals(JsonExport.encodeFor(completed.turns),
            ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
    }

    @Test
    fun writeToNullStreamFailsInsteadOfSaved() {
        // encoding + a failing stream: the same path CreateDocument writes through
        try {
            JsonExport.writeTo(
                object : java.io.OutputStream() {
                    override fun write(b: Int) = throw java.io.IOException("disk full")
                },
                "{}",
            )
            org.junit.Assert.fail("expected write failure to propagate")
        } catch (expected: java.io.IOException) {
            assertEquals("disk full", expected.message)
        }
    }
}
