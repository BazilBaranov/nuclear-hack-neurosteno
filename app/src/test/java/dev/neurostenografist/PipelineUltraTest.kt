package dev.neurostenografist

import dev.neurostenografist.core.*
import dev.neurostenografist.inference.*
import org.junit.Assert.*
import org.junit.Test

class PipelineUltraTest {
    private fun run(punctuation: (() -> TextPunctuator)? = null, isolation: ((List<Turn>) -> SpeakerIsolation)? = null,
        asr: FakeAsr = FakeAsr()): CompletedRun = Pipeline(
        source = AudioSource("source", "file:///test.wav", "test", 8000), runId = "run", samples = FloatArray(128000),
        lid = FakeLid("ru"), lidModelId = ModelCatalog.WHISPER_TINY,
        diarizer = FakeDiarizer(SpeakerInterval(0, 0.0, 5.0), SpeakerInterval(1, 3.0, 8.0)),
        requestedModelId = AUTO_MODEL_ID, readyAsrIds = setOf(ModelCatalog.WHISPER_TINY),
        asrFactory = { _, _ -> asr }, punctuationFactory = punctuation, isolationFactory = isolation,
    ) { }.execute()

    @Test fun `isolated overlap transcribes two distinct crops and records model provenance`() {
        var calls = 0
        var closed = false
        val result = run(isolation = { object : SpeakerIsolation {
            override fun hasReference(speaker: String) = true
            override fun isolate(samples: FloatArray, speaker: String): FloatArray { calls++; return samples }
            override fun close() { closed = true }
        } })
        assertEquals(2, calls)
        assertTrue(closed)
        assertEquals(listOf(0L, 3000L), result.turns.map { it.startMs })
        assertEquals(listOf(5000L, 8000L), result.turns.map { it.endMs })
        assertTrue(result.modelIds.contains(ModelCatalog.ISOLATION))
    }
    @Test fun `missing voice reference never duplicates mixed speech`() {
        val result = run(isolation = { object : SpeakerIsolation {
            override fun hasReference(speaker: String) = false
            override fun isolate(samples: FloatArray, speaker: String): FloatArray = error("must not isolate")
            override fun close() {}
        } })
        assertEquals(8000L, result.turns.sumOf { it.endMs - it.startMs })
        assertFalse(result.modelIds.contains(ModelCatalog.ISOLATION))
    }
    @Test fun `LLM runs after ASR release and hallucinated words are rejected`() {
        val asr = FakeAsr()
        var calls = 0
        val result = run(asr = asr, punctuation = {
            assertTrue(asr.closed)
            object : TextPunctuator {
                override val backendReport = "Ускорение LLM: CPU"
                override fun punctuate(text: String, language: String): String { calls++; return text + " invented" }
                override fun close() {}
            }
        })
        assertEquals(2, calls)
        assertTrue(result.turns.none { it.text.contains("invented") })
        assertTrue(result.modelIds.contains(ModelCatalog.PUNCTUATION))
    }
}
