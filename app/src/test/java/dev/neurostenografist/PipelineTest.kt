package dev.neurostenografist

import dev.neurostenografist.core.AUTO_MODEL_ID
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.core.SpeechLanguage
import dev.neurostenografist.core.Timeline
import dev.neurostenografist.core.Turn
import dev.neurostenografist.inference.EngineSelection
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.Pipeline
import dev.neurostenografist.inference.PipelineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pipeline logic on the JVM with fake engines: stage ordering, run/source id identity,
 * ready-set-only Auto selection, manual mismatch refusal, verified no-speech, stage
 * cancellation, engine release on every path, full hashes for used assets only.
 */
class PipelineTest {
    private val source = AudioSource("src-1", "file:///x.wav", "x.wav", 4000)

    private fun runPipeline(
        language: String? = "en",
        requested: String = AUTO_MODEL_ID,
        readyAsr: Set<String> = setOf(ModelCatalog.WHISPER_TINY, ModelCatalog.ZIPFORMER_RU),
        intervals: Array<SpeakerInterval> = arrayOf(SpeakerInterval(0, 0.0, 2.0)),
        isCancelled: () -> Boolean = { false },
        samplesSeconds: Int = 4,
        asrFactory: ((String, String) -> FakeAsr)? = null,
        diarizationModelId: String = ModelCatalog.DIARIZATION,
    ): Pair<CompletedRun, FakeAsr> {
        val fakeAsr = FakeAsr()
        val pipeline =
            Pipeline(
                source = source,
                runId = "run-1",
                samples = FloatArray(16_000 * samplesSeconds),
                lid = FakeLid(language),
                lidModelId = ModelCatalog.WHISPER_TINY,
                diarizer = FakeDiarizer(*intervals),
                asrFactory = asrFactory ?: { _, _ -> fakeAsr },
                requestedModelId = requested,
                readyAsrIds = readyAsr,
                isCancelled = isCancelled,
                decodeStageMs = 21L,
                diarizationModelId = diarizationModelId,
            ) { }
        return pipeline.execute() to fakeAsr
    }

    @Test
    fun `two speakers produce canonical ids in first-occurrence order`() {
        val (result, asr) =
            runPipeline(
                intervals =
                    arrayOf(
                        SpeakerInterval(7, 0.0, 2.0), // first occurrence -> speaker_1
                        SpeakerInterval(3, 2.0, 4.0), // second -> speaker_2
                    ),
            )
        assertEquals(listOf("speaker_1", "speaker_2"), result.turns.map { it.speakerId })
        assertEquals(listOf(0L, 2000L), result.turns.map { it.startMs })
        assertTrue(result.warnings.any { it.contains("not calibrated") })
        assertTrue(asr.closed)
        assertEquals(2, asr.calls)
    }

    @Test
    fun `metadata records selected diarizer instead of hardcoded default`() {
        // Fake engine: use another catalog entry solely to distinguish the model identity.
        for (language in listOf("en", null)) {
            val result = runPipeline(language = language, diarizationModelId = ModelCatalog.ZIPFORMER_RU).first
            assertTrue(result.modelIds.contains(ModelCatalog.ZIPFORMER_RU))
            assertFalse(result.modelIds.contains(ModelCatalog.DIARIZATION))
            assertTrue(result.modelHashes.containsKey(ModelCatalog.ZIPFORMER_RU))
            assertFalse(result.modelHashes.containsKey(ModelCatalog.DIARIZATION))
        }
    }

    @Test
    fun `missing diarization intervals produce zero turns`() {
        val result = runPipeline(intervals = emptyArray()).first
        assertEquals(0, result.turns.size)
    }

    @Test
    fun `manual ru request over detected en fails without substitution`() {
        try {
            runPipeline(language = "en", requested = ModelCatalog.ZIPFORMER_RU)
            fail("expected ModelUnavailableException")
        } catch (expected: dev.neurostenografist.core.ModelUnavailableException) {
            assertTrue(expected.message!!.contains("does not match detected language 'en'"))
        }
    }

    @Test
    fun `overlapping speakers emit the overlap warning`() {
        val (result, asr) =
            runPipeline(
                language = "en",
                intervals =
                    arrayOf(
                        SpeakerInterval(1, 0.0, 3.0),
                        SpeakerInterval(2, 1.0, 4.0), // overlap 2s
                    ),
            )
        assertTrue(result.warnings.any { it.contains("Одновременная речь: 2.0 с. Разделение отключено") })
        assertFalse(ModelCatalog.ISOLATION in result.modelIds)
        // total covered time stays on the original timeline, text not duplicated
        val total = result.turns.sumOf { it.endMs - it.startMs }
        assertEquals(4000L, total)
        assertTrue(asr.calls >= 2)
    }

    @Test
    fun `only enabled options require their model downloads`() {
        val request = RunRequest(ModelCatalog.GIGAAM_V3, language = SpeechLanguage.RUSSIAN)
        val required = setOf(ModelCatalog.GIGAAM_V3, ModelCatalog.DIARIZATION)

        assertEquals(required, ModelCatalog.requiredModelIds(request))
        assertEquals(required + ModelCatalog.PUNCTUATION, ModelCatalog.requiredModelIds(request.copy(punctuation = true)))
        assertEquals(required + ModelCatalog.DENOISE, ModelCatalog.requiredModelIds(request.copy(denoise = true)))
        assertEquals(required + ModelCatalog.ISOLATION, ModelCatalog.requiredModelIds(request.copy(separateOverlappingSpeech = true)))
        assertTrue(ModelCatalog.optionalModelIds(request.copy(separateOverlappingSpeech = true), hasOverlap = false).isEmpty())
    }

    @Test
    fun `crop timestamps align with turn boundaries`() {
        val (result, _) =
            runPipeline(
                language = "en",
                intervals = arrayOf(SpeakerInterval(0, 1.5, 2.25)),
            )
        assertEquals(1, result.turns.size)
        val turn = result.turns.first()
        assertEquals(1500L, turn.startMs)
        assertEquals(2250L, turn.endMs)
    }

    @Test
    fun `auto walks the requested model order for each language`() {
        val whisper = listOf(ModelCatalog.WHISPER_BASE, ModelCatalog.WHISPER_SMALL, ModelCatalog.WHISPER_TINY)
        val expected = mapOf("ru" to (listOf(ModelCatalog.GIGAAM_V3, ModelCatalog.ZIPFORMER_RU_2025) + whisper), "en" to whisper)
        for ((language, ranked) in expected) {
            for (index in ranked.indices) {
                val installed = ranked.drop(index).toSet() + ModelCatalog.ZIPFORMER_RU
                assertEquals(ranked[index], EngineSelection.resolve(language, AUTO_MODEL_ID, installed))
            }
        }
        val legacy = ModelCatalog.ZIPFORMER_RU
        assertEquals(legacy, EngineSelection.resolve("ru", legacy, setOf(legacy)))
    }

    @Test
    fun `explicit language uses auto asr priority without a detector or lid metadata`() {
        val progress = mutableListOf<String>()
        val asr = FakeAsr()
        val completed = Pipeline(
            source = source, runId = "manual-language", samples = FloatArray(16000),
            lid = null, lidModelId = ModelCatalog.WHISPER_TINY,
            diarizer = FakeDiarizer(SpeakerInterval(0, 0.0, 1.0)),
            asrFactory = { id, language ->
                assertEquals(ModelCatalog.WHISPER_BASE, id)
                assertEquals("en", language)
                asr
            },
            requestedModelId = AUTO_MODEL_ID,
            readyAsrIds = setOf(ModelCatalog.WHISPER_BASE),
            requestedLanguage = "en",
        ) { progress += it.stage }.execute()

        assertEquals("en", completed.language)
        assertEquals(1, asr.calls)
        assertFalse("detecting language" in progress)
        assertFalse("language" in completed.stageMs)
        assertFalse(ModelCatalog.WHISPER_TINY in completed.modelHashes)
    }

    @Test
    fun `auto falls back to whisper tiny when zipformer is not ready`() {
        val seen = mutableListOf<Pair<String, String>>()
        val (result, _) =
            runPipeline(
                language = "ru",
                readyAsr = setOf(ModelCatalog.WHISPER_TINY),
                asrFactory = { modelId, language ->
                    seen += modelId to language
                    FakeAsr()
                },
            )
        assertEquals("whisper-tiny-multilingual", seen.single().first)
        assertTrue(result.modelIds.contains("whisper-tiny-multilingual"))
    }

    @Test
    fun `auto with no ready asr bundle fails explicitly`() {
        try {
            runPipeline(language = "en", readyAsr = emptySet())
            fail("expected ModelUnavailableException")
        } catch (expected: dev.neurostenografist.core.ModelUnavailableException) {
            assertTrue(expected.message!!.contains("no installed model for language 'en'"))
        }
    }

    @Test
    fun `manual request for a not-ready bundle fails without substitution`() {
        try {
            runPipeline(
                language = "ru",
                requested = ModelCatalog.ZIPFORMER_RU,
                readyAsr = setOf(ModelCatalog.WHISPER_TINY),
            )
            fail("expected ModelUnavailableException")
        } catch (expected: dev.neurostenografist.core.ModelUnavailableException) {
            assertTrue(expected.message!!.contains("'zipformer-ru-small' is not installed"))
        }
    }

    @Test
    fun `completed run id is the run store id and source id stays distinct`() {
        val (result, _) = runPipeline()
        assertEquals("run-1", result.id)
        assertEquals("src-1", result.sourceId)
        assertFalse(result.id == result.sourceId)
    }

    @Test
    fun `verified no speech completes with empty turns null language and no asr`() {
        var created = 0
        val (result, _) =
            runPipeline(
                language = null,
                asrFactory = { _, _ ->
                    created++
                    FakeAsr()
                },
            )
        assertNull(result.language)
        assertTrue(result.turns.isEmpty())
        assertEquals(0, created)
        assertEquals(listOf(ModelCatalog.WHISPER_TINY, ModelCatalog.DIARIZATION), result.modelIds)
    }

    @Test
    fun `cancellation between stages raises cancellation after engines closed`() {
        val lid = FakeLid("en")
        val diarizer = FakeDiarizer(SpeakerInterval(0, 0.0, 2.0))
        val pipeline =
            Pipeline(
                source = source,
                runId = "run-1",
                samples = FloatArray(16_000),
                lid = lid,
                lidModelId = ModelCatalog.WHISPER_TINY,
                diarizer = diarizer,
                asrFactory = { _, _ -> FakeAsr() },
                requestedModelId = AUTO_MODEL_ID,
                readyAsrIds = setOf(ModelCatalog.WHISPER_TINY),
                isCancelled = { true },
            ) { }
        try {
            pipeline.execute()
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // cleanup ran before the raise propagated
            assertTrue(lid.closed)
            assertTrue(diarizer.closed)
        }
    }

    @Test
    fun `cancellation between in-turn crops stops before the next crop and releases the asr`() {
        val fake = FakeAsr()
        // One 50 s turn at MAX_CROP_MS=25_000 splits into exactly 2 crops: cancellation
        // after the first must not transcribe the second, and the ASR must still close.
        val pipeline =
            Pipeline(
                source = source,
                runId = "run-1",
                samples = FloatArray(16_000 * 50),
                lid = FakeLid("en"),
                lidModelId = ModelCatalog.WHISPER_TINY,
                diarizer = FakeDiarizer(SpeakerInterval(0, 0.0, 50.0)),
                asrFactory = { _, _ -> fake },
                requestedModelId = AUTO_MODEL_ID,
                readyAsrIds = setOf(ModelCatalog.WHISPER_TINY),
                isCancelled = { fake.calls > 0 }, // in-flight JNI equivalent returned first
            ) { }
        try {
            pipeline.execute()
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue("second crop must not run after cancellation: calls=${fake.calls}", fake.calls == 1)
            assertTrue(fake.closed)
        }
    }

    @Test
    fun `cancellation during transcription releases the asr engine`() {
        val fake = FakeAsr()
        val pipeline =
            Pipeline(
                source = source,
                runId = "run-1",
                samples = FloatArray(16_000 * 4),
                lid = FakeLid("en"),
                lidModelId = ModelCatalog.WHISPER_TINY,
                diarizer =
                    FakeDiarizer(
                        SpeakerInterval(0, 0.0, 2.0),
                        SpeakerInterval(3, 2.0, 4.0), // two turns (different raw speakers, so no merge); cancel lands after the first
                    ),
                asrFactory = { _, _ -> fake },
                requestedModelId = AUTO_MODEL_ID,
                readyAsrIds = setOf(ModelCatalog.WHISPER_TINY),
                isCancelled = { fake.calls > 0 }, // in-flight JNI equivalent returned first
            ) { }
        try {
            pipeline.execute()
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(fake.closed)
        }
    }

    @Test
    fun `lid and diarizer are released even when diarization fails`() {
        val diarizer = FakeDiarizer()
        diarizer.throwOnDiarize = IllegalStateException("diarizer exploded")
        val pipeline =
            Pipeline(
                source = source,
                runId = "run-1",
                samples = FloatArray(16_000),
                lid = FakeLid("en"),
                lidModelId = ModelCatalog.WHISPER_TINY,
                diarizer = diarizer,
                asrFactory = { _, _ -> FakeAsr() },
                requestedModelId = AUTO_MODEL_ID,
                readyAsrIds = setOf(ModelCatalog.WHISPER_TINY),
            ) { }
        try {
            pipeline.execute()
            fail("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("diarizer exploded", expected.message)
        }
        assertTrue(diarizer.closed)
    }

    @Test
    fun `lid failure still releases the diarizer`() {
        val lid = FakeLid("en")
        lid.throwOnDetect = PipelineException("language", "detection exploded")
        val diarizer = FakeDiarizer(SpeakerInterval(0, 0.0, 2.0))
        val pipeline =
            Pipeline(
                source = source,
                runId = "run-1",
                samples = FloatArray(16_000),
                lid = lid,
                lidModelId = ModelCatalog.WHISPER_TINY,
                diarizer = diarizer,
                asrFactory = { _, _ -> FakeAsr() },
                requestedModelId = AUTO_MODEL_ID,
                readyAsrIds = setOf(ModelCatalog.WHISPER_TINY),
            ) { }
        try {
            pipeline.execute()
            fail("expected PipelineException")
        } catch (expected: PipelineException) {
            assertEquals("language", expected.stage)
        }
        assertTrue(lid.closed)
        assertTrue(diarizer.closed)
    }

    @Test
    fun `model hashes are full digest strings for used assets only`() {
        val (result, _) = runPipeline(language = "en", readyAsr = setOf(ModelCatalog.WHISPER_TINY))
        assertEquals(
            setOf(ModelCatalog.WHISPER_TINY, ModelCatalog.DIARIZATION),
            result.modelHashes.keys,
        )
        assertFalse(result.modelHashes.containsKey(ModelCatalog.ZIPFORMER_RU))
        // each bundle's value holds one full 64-char digest per catalog member
        for ((_, joined) in result.modelHashes) {
            for (digest in joined.split(",")) {
                assertEquals(64, digest.length)
                assertTrue(digest.all { it.isDigit() || it in 'a'..'f' })
            }
        }
        val catalog = ModelCatalog.byId(ModelCatalog.DIARIZATION)
        assertEquals(
            catalog.files.joinToString(",") { it.sha256 },
            result.modelHashes[ModelCatalog.DIARIZATION],
        )
    }

    @Test
    fun `stage timings include decode diarization language and asr`() {
        val (result, _) = runPipeline()
        assertEquals(21L, result.stageMs["decode"])
        assertTrue(result.stageMs.containsKey("diarization"))
        assertTrue(result.stageMs.containsKey("language"))
        assertTrue(result.stageMs.containsKey("asr"))
    }

    @Test
    fun `completed diarizer and lid are released before ASR allocation`() {
        val lid = FakeLid("ru")
        val diarizer = FakeDiarizer(SpeakerInterval(0, 0.0, 1.0))
        Pipeline(
            source = source, runId = "resource-order", samples = FloatArray(16000),
            lid = lid, lidModelId = ModelCatalog.WHISPER_TINY, diarizer = diarizer,
            asrFactory = { _, _ ->
                assertTrue("diarizer must be freed before loading ASR", diarizer.closed)
                assertTrue("LID must be freed before loading ASR", lid.closed)
                FakeAsr()
            },
            requestedModelId = AUTO_MODEL_ID, readyAsrIds = setOf(ModelCatalog.WHISPER_TINY),
        ) { }.execute()
    }

    /** Turns must stay referable via Turn copies; the split path is deterministic. */
    @Test
    fun `turn split joins crops in order`() {
        val turn = Turn("s1", 0, 70_000, "")
        val parts = Timeline.split(turn, 25_000)
        assertEquals(listOf(0L, 25_000L, 50_000L), parts.map { it.startMs })
        assertEquals(3, parts.size)
    }
}
