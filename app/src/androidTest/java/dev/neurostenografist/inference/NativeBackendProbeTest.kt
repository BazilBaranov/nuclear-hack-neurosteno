package dev.neurostenografist.inference

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.core.SpeakerInterval
import java.security.MessageDigest
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Explicit device probe: install verified models and files/validation.wav before running. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativeBackendProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val reportFile get() = File(context.filesDir, "native-backend-probe.json")

    @Test fun e_agreementDetectsFalseSpeakersAndAcceptsRenaming() {
        val reference = listOf(SpeakerInterval(0, 0.0, 1.0), SpeakerInterval(1, 1.0, 2.0))
        val renamed = listOf(SpeakerInterval(7, 0.0, 1.0), SpeakerInterval(3, 1.0, 2.0))
        assertEquals(0.0, compareDiarization(reference, renamed, 2.0).speakerFrameError, 0.0)
        val hallucinated = (0..7).map { SpeakerInterval(it, 0.0, 2.0) }
        val wrong = compareDiarization(reference, hallucinated, 2.0)
        assertEquals(8, wrong.candidateSpeakers)
        assertTrue(wrong.speakerFrameError > 0.05)
        assertTrue(wrong.overlapSecondsDelta > 0.15)
    }

    @Test fun qwenCpu() {
        val bundle = ModelCatalog.byId(ModelCatalog.PUNCTUATION)
        val file = File(File(context.filesDir, "models/${bundle.id}"), bundle.files.single().name)
        assumeTrue("Install verified Qwen in ${file.absolutePath}", file.isFile)
        assertEquals("Unexpected model size", bundle.files.single().bytes, file.length())
        probe("qwen_cpu") { result ->
            val loadStart = SystemClock.elapsedRealtimeNanos()
            LocalPunctuation(file).use { model ->
                result.put("loadMs", elapsedMs(loadStart))
                val start = SystemClock.elapsedRealtimeNanos()
                val output = model.punctuate("привет как дела", "ru")
                result.put("inferenceMs", elapsedMs(start)).put("output", output)
                result.put("backendReport", model.backendReport)
                assertTrue(model.backendReport.contains("CPU"))
                assertEquals("Actual Russian punctuation fixture", "Привет, как дела?", output)
                val repeatStart = SystemClock.elapsedRealtimeNanos()
                val repeated = model.punctuate("привет как дела", "ru")
                result.put("repeatInferenceMs", elapsedMs(repeatStart)).put("repeatOutput", repeated)
                assertEquals("Context was not reset reliably", output, repeated)
            }
        }
    }

    @Test fun nemotronCpu() {
        val bundle = ModelCatalog.byId(ModelCatalog.NEMOTRON)
        val dir = File(context.filesDir, "models/${bundle.id}")
        val modelFile = File(dir, bundle.files.single().name)
        val wav = File(context.filesDir, "validation.wav")
        assumeTrue("Install Nemotron and files/validation.wav", modelFile.isFile && wav.isFile)
        assertEquals("Unexpected model size", bundle.files.single().bytes, modelFile.length())
        probe("nemotron_cpu") { result ->
            val samples = wav.inputStream().use(AudioDecoder::decodeWavTo16k)
            val duration = samples.size / 16000.0
            result.put("audioSeconds", duration).put("audioSha256", sha256(wav))
                .put("modelSha256", bundle.files.single().sha256)
            val model = NemotronDiarizer(dir)
            val start = SystemClock.elapsedRealtimeNanos()
            val segments = model.diarize(samples)
            val ms = elapsedMs(start)
            result.put("loadAndInferenceMs", ms).put("rtf", ms / 1000.0 / duration)
            result.put("backendReport", model.backendReport)
            result.put("segments", JSONArray().apply {
                segments.forEach { put(JSONObject().put("speaker", it.speaker).put("startSec", it.startSec).put("endSec", it.endSec)) }
            })
            assertTrue(model.backendReport.contains("CPU"))
            assertTrue("Speech fixture produced no speaker intervals", segments.isNotEmpty())
            assertTrue(segments.all { it.startSec >= 0 && it.endSec > it.startSec && it.endSec <= duration })
        }
    }

    private fun probe(name: String, action: (JSONObject) -> Unit) {
        val result = JSONObject().put("status", "running")
        persist(name, result) // Preserve the checkpoint even if a vendor driver terminates the process.
        try {
            result.put("devices", JSONArray(NativeAcceleration.availableDevices()))
            persist(name, result)
            action(result)
            result.put("status", "passed")
        } catch (failure: Throwable) {
            result.put("status", "failed").put("error", failure.toString())
            throw failure
        } finally {
            persist(name, result)
            Log.i("NeuroNativeProbe", "$name: $result")
        }
    }

    private fun persist(name: String, result: JSONObject) {
        val document = if (reportFile.isFile) JSONObject(reportFile.readText()) else JSONObject()
        document.put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
            .put("note", "Single fixture; model/backend execution check, not a quality benchmark or NPU claim")
            .put(name, result)
        val pending = File(reportFile.parentFile, "${reportFile.name}.pending")
        pending.writeText(document.toString(2))
        check(pending.renameTo(reportFile)) { "Cannot save native probe report" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun elapsedMs(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
}
