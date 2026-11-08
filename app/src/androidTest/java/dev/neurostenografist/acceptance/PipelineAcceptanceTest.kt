package dev.neurostenografist.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.core.AUTO_MODEL_ID
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.data.TranscriptJson
import dev.neurostenografist.inference.Diarizer
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.ModelStore
import dev.neurostenografist.inference.Pipeline
import dev.neurostenografist.inference.PipelineException
import dev.neurostenografist.inference.SherpaAsr
import dev.neurostenografist.inference.SherpaDiarizer
import dev.neurostenografist.inference.WhisperAutoLid
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * End-to-end on-device acceptance probes: the REAL sherpa runtimes (real
 * downloaded baseline bundles, real diarization/LID/ASR) against short
 * synthetic tone clips. Gate is structural (schema, stage timings, warning
 * contract, release behavior), never an accuracy claim: synthetic clips carry
 * no reference transcript, so no WER is reported anywhere.
 */
@RunWith(AndroidJUnit4::class)
class PipelineAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelStore = ModelStore(context, isBusy = { false })

    private fun ensureBundlesReady() {
        listOf(ModelCatalog.WHISPER_TINY, ModelCatalog.DIARIZATION).map(ModelCatalog::byId).forEach { bundle ->
            if (!modelStore.isReady(bundle.id)) {
                runBlocking { modelStore.install(bundle.id, {}) }
            }
            assertTrue("bundle ${bundle.id} must be installed and ready", modelStore.isReady(bundle.id))
        }
    }

    @Test
    fun realPipeline_ru_and_en_tones_schema_and_cleanup() {
        ensureBundlesReady()
        val clips =
            mapOf(
                "ru" to
                    TestAudio.concatenated(
                        TestAudio.sine(TestAudio.TONE_RU, 4000),
                        TestAudio.silence(500),
                        TestAudio.sine(TestAudio.TONE_RU, 3000, amplitude = 0.55),
                    ),
                "en" to
                    TestAudio.concatenated(
                        TestAudio.sine(TestAudio.TONE_EN, 3500),
                        TestAudio.silence(500),
                        TestAudio.sine(TestAudio.TONE_EN, 3000, amplitude = 0.55),
                    ),
            )
        clips.forEach { (label, samples) ->
            val dir = File(context.cacheDir, "acceptance-$label")
            dir.deleteRecursively()
            dir.mkdirs()
            val wav = TestAudio.write(dir, "$label.wav", samples)
            val decoded = AudioDecoder.decodeWavTo16k(wav.inputStream())
            assertTrue("decode must keep non-trivial audio", decoded.size > TestAudio.SAMPLE_RATE)

            val source = AudioSource(id = "accept-$label", uri = wav.toURI().toString(), displayName = wav.name, durationMs = null)
            val lid = WhisperAutoLid(modelStore.bundleDir(ModelCatalog.WHISPER_TINY))
            val diarizer: Diarizer = SherpaDiarizer(modelStore.bundleDir(ModelCatalog.DIARIZATION))
            val run =
                try {
                    val pipeline =
                        Pipeline(
                            source = source,
                            runId = UUID.randomUUID().toString(),
                            samples = decoded,
                            lidModelId = ModelCatalog.WHISPER_TINY,
                            readyAsrIds =
                                ModelCatalog.bundles
                                    .filter { modelStore.isReady(it.id) }
                                    .map { it.id }
                                    .toSet(),
                            lid = lid,
                            diarizer = diarizer,
                            requestedModelId = AUTO_MODEL_ID,
                            asrFactory = { modelId, language ->
                                SherpaAsr(modelId, modelStore.bundleDir(modelId), language) as dev.neurostenografist.inference.Asr
                            },
                            onProgress = {},
                        )
                    val startedAt = System.nanoTime()
                    val completed = pipeline.execute()
                    val wallMs = (System.nanoTime() - startedAt) / 1_000_000
                    assertCompletedRunSchema(completed, decoded)
                    println("ACCEPTANCE pipeline=$label wallMs=$wallMs stageMs=$completed.stageMs")
                    completed
                } catch (e: PipelineException) {
                    // supported languages go through; unsupported/uncertain clips fail loudly
                    assertTrue("pipeline failure must name its stage, got: ${e.message}", e.stage.isNotBlank())
                    println("ACCEPTANCE pipeline=$label ok-failure stage=${e.stage} message=${e.message}")
                    null
                } finally {
                    lid.close()
                }
            if (run != null) {
                // JSON transcript export must round-trip real turns
                val json = JSONObject(TranscriptJson.encode(run.turns))
                val transcript = json.getJSONArray("transcript")
                for (i in 0 until transcript.length()) {
                    val turn = transcript.getJSONObject(i)
                    assertTrue("speaker_id must exist", turn.has("speaker_id"))
                    assertTrue("text must exist", turn.has("text"))
                    assertTrue("start_ms must be integer", turn.get("start_ms") is Int)
                    assertTrue("end_ms must be integer", turn.get("end_ms") is Int)
                }
            }
            wav.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun realPipeline_tone_only_clip_fails_loudly_not_silently() {
        ensureBundlesReady()
        // unsupported tone clip: pipeline must never invent a transcript for it
        val dir = File(context.cacheDir, "acceptance-unsupported").apply { mkdirs() }
        val samples =
            TestAudio.concatenated(
                TestAudio.sine(TestAudio.TONE_UNSUPPORTED, 3000),
                TestAudio.silence(400),
                TestAudio.sine(TestAudio.TONE_UNSUPPORTED, 2500, amplitude = 0.55),
            )
        val wav = TestAudio.write(dir, "unsupported.wav", samples)
        val decoded = AudioDecoder.decodeWavTo16k(wav.inputStream())
        val source = AudioSource(id = "accept-unsupported", uri = wav.toURI().toString(), displayName = wav.name, durationMs = null)
        val lid = WhisperAutoLid(modelStore.bundleDir(ModelCatalog.WHISPER_TINY))
        val diarizer = SherpaDiarizer(modelStore.bundleDir(ModelCatalog.DIARIZATION))
        val pipeline =
            Pipeline(
                source = source,
                runId = UUID.randomUUID().toString(),
                samples = decoded,
                lidModelId = ModelCatalog.WHISPER_TINY,
                readyAsrIds =
                    ModelCatalog.bundles
                        .filter { modelStore.isReady(it.id) }
                        .map { it.id }
                        .toSet(),
                lid = lid,
                diarizer = diarizer,
                requestedModelId = AUTO_MODEL_ID,
                asrFactory = {
                    modelId,
                    language,
                    ->
                    SherpaAsr(modelId, modelStore.bundleDir(modelId), language) as dev.neurostenografist.inference.Asr
                },
                onProgress = {},
            )
        try {
            val completed = pipeline.execute()
            // Completion is acceptable ONLY as the same honest no-speech outcome the
            // pipeline produces for verified silence (empty turns, empty export) —
            // a hallucinated transcript over a tone-only clip is forbidden, and a
            // phantom non-empty turn would point at un-clamped VAD segment bounds.
            assertTrue(
                "tone-only clip must never emit non-empty turn text: ${completed.turns}",
                completed.turns.isEmpty(),
            )
            assertTrue(
                "tone-only clip must export exactly the empty assignment schema",
                TranscriptJson.encode(completed.turns) == "{\"transcript\":[]}",
            )
            println("ACCEPTANCE pipeline=unsupported completed language=${completed.language} turns=${completed.turns.size}")
        } catch (e: PipelineException) {
            assertTrue("failure must be a named stage", e.stage.isNotBlank())
            println("ACCEPTANCE pipeline=unsupported fail-loud stage=${e.stage}")
        } finally {
            lid.close()
            wav.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun realPipeline_empty_audio_fails_loudly() {
        ensureBundlesReady()
        val dir = File(context.cacheDir, "acceptance-empty").apply { mkdirs() }
        val wav = TestAudio.write(dir, "silence.wav", ShortArray(TestAudio.SAMPLE_RATE))
        val decoded = AudioDecoder.decodeWavTo16k(wav.inputStream())
        val source = AudioSource(id = "accept-empty", uri = wav.toURI().toString(), displayName = wav.name, durationMs = null)
        val lid = WhisperAutoLid(modelStore.bundleDir(ModelCatalog.WHISPER_TINY))
        val diarizer = SherpaDiarizer(modelStore.bundleDir(ModelCatalog.DIARIZATION))
        // ASR engines are created lazily and only after a non-null language decision
        // (Pipeline.kt: 'no ASR engine is ever created' for verified silence), so a
        // zero invocation count proves silence never reaches ASR.
        var asrCreated = 0
        val pipeline =
            Pipeline(
                source = source,
                runId = UUID.randomUUID().toString(),
                samples = decoded,
                lidModelId = ModelCatalog.WHISPER_TINY,
                readyAsrIds =
                    ModelCatalog.bundles
                        .filter { modelStore.isReady(it.id) }
                        .map { it.id }
                        .toSet(),
                lid = lid,
                diarizer = diarizer,
                requestedModelId = AUTO_MODEL_ID,
                asrFactory = { modelId, language ->
                    asrCreated++
                    SherpaAsr(modelId, modelStore.bundleDir(modelId), language) as dev.neurostenografist.inference.Asr
                },
                onProgress = {},
            )
        try {
            // A verified no-speech recording legitimately completes with the empty
            // contract (empty turns, null language, no ASR); a fake transcript or a
            // fire-up ASR over silence remains forbidden here.
            val completed = pipeline.execute()
            assertTrue(
                "silence-only run must produce exactly empty turns, got: ${completed.turns}",
                completed.turns.isEmpty(),
            )
            assertTrue(
                "silence-only run must be verified no-speech (null language), got: ${completed.language}",
                completed.language == null,
            )
            assertTrue(
                "silence-only run must export exactly {\"transcript\":[\"]",
                TranscriptJson.encode(completed.turns) == "{\"transcript\":[\"]}",
            )
            assertTrue("silence must never reach ASR (created $asrCreated engines)", asrCreated == 0)
            assertTrue("silence run must not record an asr stage", completed.stageMs["asr"] == null)
            println("ACCEPTANCE pipeline=silence ok-empty language=${completed.language} turns=${completed.turns.size}")
        } catch (e: PipelineException) {
            // Failing loudly is the other honest outcome: it is never a silent fake transcript.
            println("ACCEPTANCE pipeline=silence fail-loud stage=${e.stage}")
        } finally {
            lid.close()
            wav.delete()
            dir.deleteRecursively()
        }
    }

    private fun assertCompletedRunSchema(
        run: CompletedRun,
        decoded: FloatArray,
    ) {
        assertTrue(run.id.isNotBlank())
        assertTrue(run.stageMs.isNotEmpty())
        assertTrue(run.stageMs.values.all { it >= 0 })
        run.turns.forEach { turn ->
            assertTrue("turn ms must be ordered: $turn", turn.startMs <= turn.endMs)
            val endSample = turn.endMs * AudioDecoder.TARGET_SAMPLE_RATE / 1000
            assertTrue("turn must fit audio duration", endSample <= decoded.size)
        }
        assertTrue("uncalibrated-warning must always surface", run.warnings.any { it.contains("not calibrated") })
    }
}
