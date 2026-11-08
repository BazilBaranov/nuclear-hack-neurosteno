package dev.neurostenografist.acceptance

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.AppGraph
import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.OnnxAcceleration
import dev.neurostenografist.inference.SherpaAsr
import dev.neurostenografist.inference.SherpaDiarizer
import dev.neurostenografist.inference.WhisperAutoLid
import dev.neurostenografist.inference.parseOnnxProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real model placement probe. Uses prepared files only; no downloads. */
@RunWith(AndroidJUnit4::class)
class OnnxBackendProbeTest {
    @Test fun gigaamEncoderUsesNnapiOnPixel9Pro() {
        assumeTrue("Pixel 9 Pro acceleration probe", Build.DEVICE == "caiman")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = AppGraph.modelStore(context)
        val id = ModelCatalog.GIGAAM_V3
        val wav = File(context.filesDir, "validation.wav")
        assumeTrue("Provision the GigaAM bundle and validation.wav", models.isReady(id) && wav.isFile)
        assertTrue("Another inference job is active", !AppGraph.isBusy)
        val samples = wav.inputStream().use { AudioDecoder.decodeWavTo16k(it) }
        val backend = OnnxAcceleration.choose(context, "probe-gigaam", diagnostic = true)
        val directory = requireNotNull(backend.profileDir)
        fun transcribe(provider: String): String {
            val asr = SherpaAsr(id, models.bundleDir(id), "ru", provider)
            return try { asr.transcribe(samples) } finally { asr.close() }
        }
        try {
            val reference = transcribe("cpu")
            assertTrue("Need a non-empty CPU transcript", reference.isNotBlank())
            assertEquals(reference, transcribe(backend.provider))
            val traces = directory.listFiles { file -> file.extension == "json" }.orEmpty()
            assertTrue("GigaAM did not execute any NNAPI nodes", traces.any {
                it.length() in 1..(8L * 1024 * 1024) && parseOnnxProfile(it.readText()).nnapiExecutions > 0
            })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun preparedModelsReportActualProvidersAndSafeFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = AppGraph.modelStore(context)
        assertTrue("Another inference job is active", !AppGraph.isBusy)
        for (id in listOf(ModelCatalog.WHISPER_TINY, ModelCatalog.DIARIZATION)) {
            assertTrue("Prepare model $id before the probe", models.isReady(id))
        }
        val wav = File(context.filesDir, "validation.wav")
        assertTrue("Prepare real speech validation.wav before the probe", wav.isFile)
        val samples = wav.inputStream().use { AudioDecoder.decodeWavTo16k(it) }.let {
            it.copyOfRange(0, minOf(it.size, 4 * 16000))
        }
        assertTrue("Need at least half a second of speech", samples.size >= 8000)
        val report = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("sampleCount", samples.size)
        val reportFile = File(context.filesDir, "onnx-backend-probe.json")
        for (stage in listOf("lid", "asr", "diarization")) {
            val backend = OnnxAcceleration.choose(context, "probe-$stage", diagnostic = true)
            val fallbacks = mutableListOf<String>()
            val started = SystemClock.elapsedRealtime()
            val stageReport = JSONObject().put("requested", backend.description)
            report.put(stage, stageReport)
            reportFile.writeText(report.toString(2)) // survive an unexpected native abort
            try {
                when (stage) {
                    "lid" -> {
                        val engine = WhisperAutoLid(models.bundleDir(ModelCatalog.WHISPER_TINY), backend.provider, fallbacks::add)
                        try { stageReport.put("language", engine.detect(samples)) } finally { engine.close() }
                    }
                    "asr" -> {
                        val engine = SherpaAsr(ModelCatalog.WHISPER_TINY, models.bundleDir(ModelCatalog.WHISPER_TINY), "ru", backend.provider, fallbacks::add)
                        try { stageReport.put("text", engine.transcribe(samples)) } finally { engine.close() }
                    }
                    else -> {
                        val engine = SherpaDiarizer(models.bundleDir(ModelCatalog.DIARIZATION), backend.provider, fallbacks::add)
                        try { stageReport.put("segments", engine.diarize(samples).size) } finally { engine.close() }
                    }
                }
                stageReport.put("succeeded", true)
            } catch (error: Exception) {
                stageReport.put("succeeded", false).put("error", error.toString())
            } finally {
                stageReport.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("fallbacks", JSONArray(fallbacks))
                    .put("evidence", OnnxAcceleration.evidence(backend))
                    .put("profileDir", backend.profileDir?.absolutePath)
                reportFile.writeText(report.toString(2))
            }
        }
        // Language ambiguity remains a valid LID domain result. ASR and diarization must recover.
        assertTrue(report.toString(2), report.getJSONObject("asr").getBoolean("succeeded"))
        assertTrue(report.toString(2), report.getJSONObject("diarization").getBoolean("succeeded"))
    }
}
