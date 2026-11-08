package dev.neurostenografist.inference

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.AppGraph
import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.inference.litert.LiteRtWhisper
import dev.neurostenografist.inference.litert.diarizeNemotronLiteRt
import dev.neurostenografist.inference.litert.diarizePyannoteLiteRt
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit GPU probe; install the checksum-verified model bundles and provision validation.wav. */
@RunWith(AndroidJUnit4::class)
class LiteRtBackendProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models get() = AppGraph.modelStore(context)

    @Before fun requireAndroid14() {
        assumeTrue("LiteRT is selected on Android 14+", Build.VERSION.SDK_INT >= 34)
    }

    @Test fun whisperTiny() = probe("whisper") { report ->
        val model = weights(ModelCatalog.WHISPER_TINY_LITERT)
        val tokens = File(weights(ModelCatalog.WHISPER_TINY), "tiny-tokens.txt")
        val samples = speech()
        LiteRtWhisper(model, tokens) {}.use { whisper ->
            val language = whisper.detect(samples)
            report.put("language", language)
            assertEquals("<|ru|>", language)
            val transcript = whisper.transcribe(samples, "ru")
            report.put("nonEmpty", transcript.isNotBlank())
            assertTrue("Expected Russian speech", transcript.any { it in 'а'..'я' || it == 'ё' })
        }
    }

    @Test fun nemotron() = probe("nemotron") { report ->
        val samples = speech()
        val turns = diarizeNemotronLiteRt(weights(ModelCatalog.NEMOTRON_LITERT), samples) {}
        assertTrue(turns.isNotEmpty() && turns.all { it.startSec >= 0 && it.endSec <= samples.size / 16000.0 })
        report.put("segments", turns.size).put("speakers", turns.map { it.speaker }.distinct().size)
    }

    @Test fun pyannote() = probe("pyannote") { report ->
        val samples = speech()
        val turns = diarizePyannoteLiteRt(context, weights(ModelCatalog.PYANNOTE_LITERT), samples) {}
        assertTrue(turns.isNotEmpty() && turns.all { it.startSec >= 0 && it.endSec <= samples.size / 16000.0 })
        report.put("segments", turns.size).put("speakers", turns.map { it.speaker }.distinct().size)
    }

    @Test fun qwenPunctuation() = probe("qwen") { report ->
        val model = ModelCatalog.byId(ModelCatalog.PUNCTUATION_LITERT)
        val cpu = ModelCatalog.byId(ModelCatalog.PUNCTUATION)
        val file = File(weights(model.id), model.files.single().name)
        val fallback = File(weights(cpu.id), cpu.files.single().name)
        LiteRtPunctuation(file, fallback, context.codeCacheDir) { false }.use { qwen ->
            val text = qwen.punctuate("привет как дела", "ru")
            report.put("backend", qwen.backendReport).put("output", text)
            assertEquals("Привет, как дела?", text)
            assertEquals(setOf(ModelCatalog.PUNCTUATION_LITERT), qwen.modelIds)
        }
    }

    private fun weights(id: String): File {
        assumeTrue("Provision $id", models.isReady(id))
        return models.bundleDir(id)
    }

    private fun speech(): FloatArray {
        val file = File(context.filesDir, "validation.wav")
        assumeTrue("Provision Russian speech as validation.wav", file.isFile)
        return file.inputStream().use(AudioDecoder::decodeWavTo16k)
    }

    private fun probe(stage: String, run: (JSONObject) -> Unit) {
        val report = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("stage", stage)
        val file = File(context.filesDir, "litert-$stage.json")
        val started = SystemClock.elapsedRealtime()
        try {
            run(report)
            report.put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.toString())
            throw error
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            file.writeText(report.toString(2))
            Log.i("NeuroLiteRtProbe", report.toString())
        }
    }
}
