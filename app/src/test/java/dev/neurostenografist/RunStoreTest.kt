package dev.neurostenografist

import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.core.SpeechLanguage
import dev.neurostenografist.core.Turn
import dev.neurostenografist.data.RunStore
import dev.neurostenografist.data.RetryEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun source() =
        AudioSource(
            id = "src-1",
            uri = "content://media/external/audio/media/1",
            displayName = "meeting.wav",
            durationMs = 12_000L,
        )

    private fun payload(id: String) =
        CompletedRun(
            id = id,
            sourceId = "src-1",
            turns = listOf(Turn("speaker_1", 0, 4000, "Привет!"), Turn("speaker_2", 4000, 8000, "Hello.")),
            language = "ru",
            modelIds = listOf("whisper-base", "pyannote-segmentation-3.0"),
            modelHashes = mapOf("whisper-base" to "abc123"),
            stageMs = mapOf("decode" to 120L, "diarization" to 340L),
            warnings = listOf("overlap_owned_by_following_speaker"),
        )

    private fun newStore() = RunStore(File(tmp.root, "runs"))

    @Test
    fun createReturnsFreshIdsAndRecordsCreatedStatus() {
        val store = newStore()
        val first = store.create(source(), RunRequest("auto"))
        val second = store.create(source(), RunRequest("auto"))
        assertNotEquals(first, second)
        assertEquals(RunStatus.CREATED, store.read(first)?.status)
        assertEquals("auto", store.read(first)?.request?.modelId)
    }

    @Test
    fun requestKeepsModelLanguageAndOptionalProcessingAcrossRestart() {
        val store = newStore()
        val request = RunRequest(
            "whisper-small",
            diarizationModelId = "diarization-nemotron-3",
            language = SpeechLanguage.ENGLISH,
            punctuation = true,
            denoise = false,
            separateOverlappingSpeech = true,
        )
        val id = store.create(source(), request)
        assertEquals(request, newStore().read(id)?.request)
    }

    @Test
    fun legacyRequestsKeepTheirOriginalProcessingDefaults() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        val file = File(tmp.root, "runs/$id.json")
        val body = org.json.JSONObject(file.readText())
        body.getJSONObject("request").apply {
            remove("diarizationModelId")
            remove("punctuation")
            remove("denoise")
            remove("separateOverlappingSpeech")
        }
        file.writeText(body.toString())
        assertEquals(
            RunRequest("auto", punctuation = true, denoise = true, separateOverlappingSpeech = true),
            newStore().read(id)?.request,
        )
    }

    @Test
    fun onlyCompletedRunsAreReadableAsCompleted() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        try {
            store.readCompleted(id)
            throw AssertionError("expected readCompleted to fail for a non-completed run")
        } catch (expected: IllegalStateException) {
            // not completed yet
        }
        store.complete(payload(id))
        assertEquals(payload(id), store.readCompleted(id))
    }

    @Test
    fun completeThenRestartKeepsPayloadAndStatusesEnforced() {
        val id =
            newStore().let { store ->
                val runId = store.create(source(), RunRequest("auto"))
                store.complete(payload(runId))
                runId
            }
        val reopened = newStore()
        assertEquals(RunStatus.COMPLETED, reopened.read(id)?.status)
        assertEquals(payload(id), reopened.readCompleted(id))

        try {
            reopened.markStopped(id, RunStatus.COMPLETED, null)
            throw AssertionError("markStopped must reject the completed status")
        } catch (expected: IllegalArgumentException) {
            // enforced
        }
    }

    @Test
    fun deleteRunKeepsSiblingRunAndSourceFileUntouched() {
        val store = newStore()
        val keep = store.create(source(), RunRequest("auto"))
        store.complete(payload(keep))
        val drop = store.create(source(), RunRequest("auto"))
        store.complete(payload(drop))

        val sourceFile = tmp.newFile("meeting.wav")
        store.deleteRun(drop)

        assertEquals(null, store.read(drop))
        assertEquals(RunStatus.COMPLETED, store.read(keep)?.status)
        assertTrue(sourceFile.exists())
    }

    @Test
    fun runningRunsBecomeInterruptedOnRestartButCompletedSurvive() {
        val store = newStore()
        val running = store.create(source(), RunRequest("auto"))
        store.markRunning(running)
        val done = store.create(source(), RunRequest("auto"))
        store.complete(payload(done))

        val reopened = newStore()
        reopened.markInterruptedOnRestart()

        assertEquals(RunStatus.INTERRUPTED, reopened.read(running)?.status)
        assertEquals(RunStatus.COMPLETED, reopened.read(done)?.status)
        assertFalse(reopened.list().any { it.status == RunStatus.RUNNING })

        // CREATED is the other in-flight status: it must be recovered too, not
        // left dangling forever if the process died between create and markRunning.
        val created = store.create(source(), RunRequest("auto"))
        store.markStopped(created, RunStatus.FAILED, "cannot start: test")
        assertEquals(RunStatus.FAILED, newStore().read(created)?.status)
    }

    @Test
    fun createdRunsGetInterruptedOnRestartLikeRunningOnes() {
        val store = newStore()
        val created = store.create(source(), RunRequest("auto"))
        val reopened = newStore()
        reopened.markInterruptedOnRestart()
        val read = reopened.read(created)
        assertEquals(RunStatus.INTERRUPTED, read?.status)
        assertEquals("Обработка прервана после перезапуска приложения", read?.message)
    }

    @Test
    fun corruptRunFileIsNotReportedAsCompleted() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        File(File(tmp.root, "runs"), "$id.json").writeText("{\"id\": \"$id\", \"sta")
        val reopened = newStore()
        assertEquals(null, reopened.read(id))
        try {
            reopened.readCompleted(id)
            throw AssertionError("corrupt file must not read as completed")
        } catch (expected: IllegalStateException) {
            // unreadable payload
        }
    }

    @Test
    fun writeFailureKeepsPreviousGoodRecord() {
        val store = newStore()
        val id1 = store.create(source(), RunRequest("auto"))
        val file = File(tmp.root, "runs/$id1.json")
        val good = file.readText()
        file.parentFile!!.setWritable(false)
        try {
            // any failure mode must leave the recorded run untouched and surface loudly
            try {
                store.complete(payload(id1))
                org.junit.Assert.fail("write failure not surfaced")
            } catch (expected: Exception) {
                // rename under a non-writable parent cannot succeed
            }
        } finally {
            file.parentFile!!.setWritable(true)
        }
        assertEquals(good, file.readText())
        store.complete(payload(id1)) // writable again -> the same completion succeeds
        assertEquals(RunStatus.COMPLETED, store.list().single().status)
    }

    @Test
    fun createRejectsBlankSourceIdentifiers() {
        for (bad in listOf("", "   ")) {
            try {
                newStore().create(source().copy(id = bad), RunRequest("auto"))
                org.junit.Assert.fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // blank source ids would decouple runs from sources
            }
        }
    }

    @Test
    fun completeForUnknownRunIdFailsLoudlyWithoutSideEffects() {
        val store = newStore()
        val id1 = store.create(source(), RunRequest("auto"))
        // a payload keyed to a run that has no record (e.g. a wrong/foreign id) must
        // fail loudly instead of silently materialising a completed run
        try {
            store.complete(payload("no-such-run"))
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // run no-such-run not found
        }
        assertEquals(RunStatus.CREATED, store.read(id1)?.status)
    }
    @Test
    fun editOneTurnPersistsAndPreservesOtherTurnsAndMetadata() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        val before = payload(id)
        store.complete(before)
        val replacement = before.turns[0].copy(text = "Добрый день!", speakerId = "Участник 1", endMs = 3500)
        store.editTurn(id, 0, before.turns[0], replacement)
        assertEquals(before.copy(turns = listOf(replacement, before.turns[1])), newStore().readCompleted(id))
    }

    @Test
    fun staleEditCannotOverwriteNewText() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        store.complete(payload(id))
        val original = payload(id).turns[0]
        val edited = original.copy(text = "Исправление")
        store.editTurn(id, 0, original, edited)
        assertThrows<IllegalStateException> { store.editTurn(id, 0, original, original.copy(text = "Старый экран")) }
        assertEquals(edited, store.readCompleted(id).turns[0])
    }

    @Test
    fun retryAtomicallyMergesEvidenceWithoutReplacingOriginalTimingsOrOtherTurns() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        val before = payload(id)
        store.complete(before)
        val replacement = before.turns[0].copy(text = "Повторное распознавание")
        val evidence = RetryEvidence(
            modelIds = listOf("whisper-base", "voxlock"),
            modelHashes = mapOf("voxlock" to "new-hash"),
            stageMs = mapOf("decode" to 42L, "asr" to 500L),
            warnings = listOf(before.warnings.single(), "Повтор на CPU"),
        )
        store.editTurn(id, 0, before.turns[0], replacement, evidence)
        val saved = newStore().readCompleted(id)
        assertEquals(listOf(replacement, before.turns[1]), saved.turns)
        assertEquals(before.modelIds + "voxlock", saved.modelIds)
        assertEquals(before.modelHashes + ("voxlock" to "new-hash"), saved.modelHashes)
        assertEquals(before.stageMs + mapOf("retry_decode" to 42L, "retry_asr" to 500L), saved.stageMs)
        assertEquals(before.warnings + "Повтор на CPU", saved.warnings)
        assertEquals(before.id, saved.id)
        assertEquals(before.sourceId, saved.sourceId)
        assertEquals(before.language, saved.language)
    }

    @Test
    fun staleRetryCannotChangeEvidenceOrRevision() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        val before = payload(id)
        store.complete(before)
        store.editTurn(id, 0, before.turns[0], before.turns[0].copy(text = "Ручная правка"))
        val edited = store.readCompleted(id)
        val revision = store.revision.value
        assertThrows<IllegalStateException> {
            store.editTurn(id, 0, before.turns[0], before.turns[0].copy(text = "Устаревший повтор"),
                RetryEvidence(listOf("voxlock"), mapOf("voxlock" to "wrong"), mapOf("asr" to 1L), listOf("Не сохранять")))
        }
        assertEquals(edited, newStore().readCompleted(id))
        assertEquals(revision, store.revision.value)
    }

    @Test
    fun editRejectsInvalidTimesBlankSpeakerAndIncompleteRuns() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        val turn = payload(id).turns[0]
        assertThrows<IllegalStateException> { store.editTurn(id, 0, turn, turn) }
        store.complete(payload(id))
        for (bad in listOf(turn.copy(startMs = -1), turn.copy(endMs = 0), turn.copy(endMs = 12001), turn.copy(speakerId = " "))) {
            assertThrows<IllegalArgumentException> { store.editTurn(id, 0, turn, bad) }
        }
        assertEquals(payload(id), store.readCompleted(id))
    }

    @Test
    fun successfulMutationPublishesRevisionButRejectedEditDoesNot() {
        val store = newStore()
        val initial = store.revision.value
        val id = store.create(source(), RunRequest("auto"))
        assertTrue(store.revision.value > initial)
        store.complete(payload(id))
        val completedRevision = store.revision.value
        assertThrows<IllegalArgumentException> { store.editTurn(id, 0, payload(id).turns[0], payload(id).turns[0].copy(startMs = -1)) }
        assertEquals(completedRevision, store.revision.value)
        store.deleteRun(id)
        assertTrue(store.revision.value > completedRevision)
    }

    @Test
    fun historySummariesDoNotRetainAllTranscriptText() {
        val store = newStore()
        val id = store.create(source(), RunRequest("auto"))
        store.complete(payload(id))
        val summary = store.list(includeCompleted = false).single()
        assertEquals(RunStatus.COMPLETED, summary.status)
        assertEquals(null, summary.completed)
        assertEquals(payload(id), store.readCompleted(id))
    }

    @Test
    fun completionPersistsDecodedDurationForLaterTimeEdits() {
        val store = newStore()
        val id = store.create(source().copy(durationMs = null), RunRequest("auto"))
        store.complete(payload(id), durationMs = 20_000)
        assertEquals(20_000L, newStore().read(id)?.source?.durationMs)
        val old = store.readCompleted(id).turns.last()
        store.editTurn(id, 1, old, old.copy(endMs = 19_000))
        assertEquals(19_000L, store.readCompleted(id).turns.last().endMs)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

}
