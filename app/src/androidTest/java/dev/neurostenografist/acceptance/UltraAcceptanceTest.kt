package dev.neurostenografist.acceptance

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.AppGraph
import dev.neurostenografist.MainActivity
import dev.neurostenografist.ProcessingService
import dev.neurostenografist.audio.AudioDecoder
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.data.RunRecord
import dev.neurostenografist.data.TranscriptJson
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.OverlapPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.security.MessageDigest

/** Real foreground-service inference. Provision models/validation.wav and disable networking before running. */
@RunWith(AndroidJUnit4::class)
class UltraAcceptanceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = AppGraph.runStore(context)
    private val qualityModels get() = InstrumentationRegistry.getArguments().getString("qualityModels") == "true"

    @Before
    fun requirePreparedOfflineDevice() {
        AppGraph.onProcessStart(context)
        val preflight = JSONObject().put("busy", AppGraph.isBusy)
        persist("preflight", preflight)
        assertTrue("Another recording or inference job is active; leave it untouched", !AppGraph.isBusy)
        val models = AppGraph.modelStore(context)
        val required = listOf(ModelCatalog.WHISPER_TINY, ModelCatalog.DIARIZATION, ModelCatalog.PUNCTUATION, ModelCatalog.DENOISE, ModelCatalog.ISOLATION) +
            if (qualityModels) listOf(ModelCatalog.GIGAAM_V3, ModelCatalog.WHISPER_BASE) else emptyList()
        val missing = required.filterNot(models::isReady)
        preflight.put("missingModels", JSONArray(missing))
        persist("preflight", preflight)
        assertTrue("Provision these verified model bundles before testing: $missing", missing.isEmpty())
        // Read-only isolation check: the test never enables/disables networking or downloads models.
        check(Build.VERSION.SDK_INT >= 29) { "This offline probe needs Android 10+ shell permission adoption" }
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.ACCESS_NETWORK_STATE")
        try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            fun observe(): OfflineNetworkState {
                val active = connectivity.activeNetwork
                val activeIsVpn = active?.let { connectivity.getNetworkCapabilities(it) }
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val physicalInternetNetworks = connectivity.allNetworks.count { network ->
                    connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true
                }
                return OfflineNetworkState(active != null, activeIsVpn, physicalInternetNetworks,
                    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
                    Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 1)
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var network = observe()
            while (!network.offline && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
                network = observe()
            }
            preflight.put("activeNetworkPresent", network.activeNetworkPresent)
                .put("activeNetworkIsVpn", network.activeNetworkIsVpn)
                .put("physicalInternetNetworks", network.physicalInternetNetworks)
                .put("airplaneModeEnabled", network.airplaneModeEnabled)
                .put("wifiEnabled", network.wifiEnabled)
                .put("offlinePreconditionMet", network.offline)
            persist("preflight", preflight)
            assertEquals("Disable all underlying networks with Internet capability before this offline run", 0, network.physicalInternetNetworks)
            assertTrue("An active VPN placeholder is accepted only with airplane mode enabled and Wi-Fi disabled", network.offline)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private data class OfflineNetworkState(
        val activeNetworkPresent: Boolean,
        val activeNetworkIsVpn: Boolean,
        val physicalInternetNetworks: Int,
        val airplaneModeEnabled: Boolean,
        val wifiEnabled: Boolean,
    ) {
        val offline: Boolean get() = physicalInternetNetworks == 0 &&
            (!activeNetworkPresent || (activeNetworkIsVpn && airplaneModeEnabled && !wifiEnabled))
    }

    @Test
    fun realRussianSpeechAndSingleTurnRetryStayOffline() {
        val wav = File(context.filesDir, "validation.wav")
        assertTrue("Provision the real Russian validation.wav fixture in app filesDir", wav.isFile && wav.length() > 44)
        val durationMs = wav.inputStream().use { AudioDecoder.decodeWavTo16k(it).size * 1000L / AudioDecoder.TARGET_SAMPLE_RATE }
        assertTrue("The speech fixture must contain audio", durationMs > 0)
        val measured = runService(wav, durationMs)
        val run = measured.record.completed ?: error("Completed payload missing")
        persist("speech", evidence(measured, durationMs))
        assertTrue("Real speech produced no transcript", run.turns.isNotEmpty() && run.turns.any { it.text.isNotBlank() })
        assertEquals("The fixture is Russian speech", "ru", run.language)
        if (qualityModels) assertTrue("Russian must use GigaAM", ModelCatalog.GIGAAM_V3 in run.modelIds)
        assertTrue("The actual run must use the local LLM", ModelCatalog.PUNCTUATION in run.modelIds)
        assertTrue("The actual run must use the denoiser", ModelCatalog.DENOISE in run.modelIds)
        assertTrue("Missing actual backend evidence", run.warnings.any { it.contains("CPU") || it.contains("GPU") || it.contains("NPU") })
        assertTranscript(run, durationMs)

        val before = run.turns
        val revisionBefore = store.revision.value
        AppGraph.recordError = null
        val retryStarted = SystemClock.elapsedRealtime()
        ProcessingService.retryTurn(context, run.id, 0)
        waitUntil("single-turn retry", run.id) {
            AppGraph.activeRunId != run.id && (store.revision.value > revisionBefore || AppGraph.recordError != null)
        }
        val retryWallMs = SystemClock.elapsedRealtime() - retryStarted
        val after = store.read(run.id) ?: error("Retry deleted the source result")
        val retryEvidence = JSONObject()
            .put("runId", run.id).put("wallMs", retryWallMs)
            .put("message", AppGraph.recordError ?: JSONObject.NULL)
            .put("status", after.status)
            .put("revisionChanged", store.revision.value > revisionBefore)
        after.completed?.let { retryEvidence.put("output", JSONObject(TranscriptJson.encode(it.turns))) }
        persist("retry", retryEvidence)
        assertTrue("Retry failed: ${AppGraph.recordError}", store.revision.value > revisionBefore)
        assertEquals(RunStatus.COMPLETED, after.status)
        val retried = after.completed ?: error("Retry removed the transcript")
        assertEquals(before.size, retried.turns.size)
        assertTrue("Retry changed other turns", before.drop(1) == retried.turns.drop(1))
        assertEquals(before.first().speakerId, retried.turns.first().speakerId)
        assertEquals(before.first().startMs, retried.turns.first().startMs)
        assertEquals(before.first().endMs, retried.turns.first().endMs)
        assertTrue(retried.turns.first().text.isNotBlank())
        assertTranscript(retried, durationMs)
        Log.i(TAG, "speech wallMs=${measured.wallMs} durationMs=$durationMs rtf=${measured.wallMs.toDouble() / durationMs} turns=${run.turns.size}; retry wallMs=$retryWallMs")
    }

    @Test
    fun threeSecondsOfSilenceProducesNoHallucinatedText() {
        val wav = File(context.filesDir, "ultra-acceptance-silence.wav")
        writeSilence(wav)
        val measured = runService(wav, 3000)
        persist("silence", evidence(measured, 3000))
        val run = measured.record.completed ?: error("Completed silence payload missing")
        assertTrue("Silence produced hallucinated turns", run.turns.isEmpty())
        assertEquals(null, run.language)
        assertEquals(0, JSONObject(TranscriptJson.encode(run.turns)).getJSONArray("transcript").length())
        assertTranscript(run, 3000)
        Log.i(TAG, "silence wallMs=${measured.wallMs} durationMs=3000 turns=${run.turns.size}")
    }

    /** Diagnostic AMI development clip; this checks model behavior, not reference WER. */
    @Test
    fun amiMultipleSpeakersProduceOfflineStructuredTranscript() {
        assertTrue("Provision the verified Nemotron diarization bundle before the AMI test",
            AppGraph.modelStore(context).isReady(ModelCatalog.NEMOTRON))
        val wav = File(context.filesDir, "validation-ami.wav")
        assertTrue("Provision the real 60-second AMI validation-ami.wav fixture in app filesDir",
            wav.isFile && wav.length() > 44)
        val durationMs = wav.inputStream().use { AudioDecoder.decodeWavTo16k(it).size * 1000L / AudioDecoder.TARGET_SAMPLE_RATE }
        assertTrue("The AMI fixture must be the first 60 seconds of the prepared development clip",
            durationMs in 59_900L..60_100L)
        val measured = runService(wav, durationMs, ModelCatalog.NEMOTRON)
        val run = measured.record.completed ?: error("Completed AMI payload missing")
        val speakers = run.turns.map { it.speakerId }.toSet()
        val overlappingTurns = run.turns.any { OverlapPlan.hasOverlap(it, run.turns) }
        val reportedOverlap = run.warnings.any {
            it.contains("overlap", ignoreCase = true) || it.contains("Одновременная речь", ignoreCase = true)
        }
        val actualSeparation = ModelCatalog.ISOLATION in run.modelIds
        val explicitFallback = run.warnings.any { it.contains("недостаточно чистой речи", ignoreCase = true) }
        persist("ami", evidence(measured, durationMs)
            .put("diagnosticOnly", true)
            .put("speakerCount", speakers.size)
            .put("overlappingOutputTurns", overlappingTurns)
            .put("overlapReported", reportedOverlap)
            .put("isolationUsed", actualSeparation)
            .put("explicitIsolationFallback", explicitFallback))
        assertEquals("The AMI fixture contains English speech", "en", run.language)
        if (qualityModels) assertTrue("English must use Whisper base", ModelCatalog.WHISPER_BASE in run.modelIds)
        assertTrue("AMI speech produced an empty transcript", run.turns.isNotEmpty() && run.turns.any { it.text.isNotBlank() })
        assertTrue("AMI dialogue must retain at least two speakers", speakers.size >= 2)
        assertTrue("AMI must execute the selected Nemotron diarizer", ModelCatalog.NEMOTRON in run.modelIds)
        assertTranscript(run, durationMs)
        if (overlappingTurns || reportedOverlap) {
            assertTrue("Detected overlap needs actual VoxLock provenance or an explicit insufficient-clean-speech fallback",
                actualSeparation || explicitFallback)
        }
        Log.i(TAG, "ami wallMs=${measured.wallMs} durationMs=$durationMs rtf=${measured.wallMs.toDouble() / durationMs} speakers=${speakers.size} turns=${run.turns.size} isolation=$actualSeparation fallback=$explicitFallback")
    }

    private data class MeasuredRun(val record: RunRecord, val wallMs: Long, val inputSha256: String)

    private fun runService(wav: File, durationMs: Long, diarizationId: String = ModelCatalog.DIARIZATION): MeasuredRun {
        val source = AudioSource(UUID.randomUUID().toString(), Uri.fromFile(wav).toString(),
            "${if (qualityModels) "Проверка GigaAM/Whisper" else "Техническая проверка Tiny"}: ${wav.name}", durationMs)
        val id = store.create(
            source,
            RunRequest(
                if (qualityModels) "auto" else ModelCatalog.WHISPER_TINY,
                diarizationId,
                punctuation = true,
                denoise = true,
                separateOverlappingSpeech = true,
            ),
        )
        val digest = MessageDigest.getInstance("SHA-256")
        wav.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val inputSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val start = SystemClock.elapsedRealtime()
        ProcessingService.startRun(context, id)
        waitUntil("full pipeline", id) {
            val status = store.read(id)?.status
            status != null && status != RunStatus.CREATED && status != RunStatus.RUNNING && AppGraph.activeRunId != id
        }
        val measured = MeasuredRun(store.read(id) ?: error("Run record disappeared"), SystemClock.elapsedRealtime() - start, inputSha256)
        if (measured.record.status != RunStatus.COMPLETED) persist("failure-$id", evidence(measured, durationMs))
        assertEquals("Pipeline failed: ${measured.record.message}", RunStatus.COMPLETED, measured.record.status)
        return measured
    }

    private fun waitUntil(label: String, runId: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                persist("timeout-$runId", JSONObject().put("operation", label).put("stage", AppGraph.activeStage ?: JSONObject.NULL).put("timeoutMs", TIMEOUT_MS))
                if (AppGraph.activeRunId == runId) ProcessingService.cancelActive(context)
                throw AssertionError("Timed out after $TIMEOUT_MS ms in $label, stage=${AppGraph.activeStage}")
            }
            SystemClock.sleep(200)
        }
    }

    private fun assertTranscript(run: CompletedRun, durationMs: Long) {
        assertTrue("Invalid timing measurements", run.stageMs.isNotEmpty() && run.stageMs.values.all { it >= 0 })
        val parsed = JSONObject(TranscriptJson.encode(run.turns)).getJSONArray("transcript")
        assertEquals(run.turns.size, parsed.length())
        var previousStart = -1L
        for (i in 0 until parsed.length()) {
            val turn = parsed.getJSONObject(i)
            assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), turn.keys().asSequence().toSet())
            assertTrue(turn.get("speaker_id") is String && turn.getString("speaker_id").isNotBlank())
            assertTrue(turn.get("text") is String)
            assertTrue(turn.get("start_ms") is Int || turn.get("start_ms") is Long)
            assertTrue(turn.get("end_ms") is Int || turn.get("end_ms") is Long)
            val start = turn.getLong("start_ms")
            val end = turn.getLong("end_ms")
            assertTrue("Invalid source-time bounds", start >= 0 && end > start && end <= durationMs)
            assertTrue("Transcript is not chronological", start >= previousStart)
            previousStart = start
        }
    }

    private fun evidence(measured: MeasuredRun, durationMs: Long): JSONObject = JSONObject().apply {
        put("runId", measured.record.id)
        put("inputSha256", measured.inputSha256)
        put("status", measured.record.status)
        put("message", measured.record.message ?: JSONObject.NULL)
        put("wallMs", measured.wallMs)
        put("audioDurationMs", durationMs)
        put("rtf", measured.wallMs.toDouble() / durationMs)
        measured.record.completed?.let { run ->
            put("language", run.language ?: JSONObject.NULL)
            put("stageMs", JSONObject(run.stageMs))
            put("modelIds", JSONArray(run.modelIds))
            put("modelHashes", JSONObject(run.modelHashes))
            put("runtimeEvidenceAndWarnings", JSONArray(run.warnings))
            put("output", JSONObject(TranscriptJson.encode(run.turns)))
        }
    }

    private fun persist(key: String, value: JSONObject) {
        val file = File(context.filesDir, "ultra-acceptance.json")
        val previous = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        val report = if (previous.optString("sessionId") == SESSION_ID) previous else JSONObject()
        report.put("sessionId", SESSION_ID)
        report.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        report.put("sdk", Build.VERSION.SDK_INT)
        report.put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        report.put("airplaneMode", Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        report.put("networkPrecondition", "Required: zero non-VPN Internet-capable networks; an active VPN placeholder additionally requires airplane mode and Wi-Fi disabled. Observations are in preflight.")
        report.put("updatedAtMs", System.currentTimeMillis())
        report.put(key, value)
        val tmp = File(context.filesDir, "ultra-acceptance.json.tmp")
        tmp.writeText(report.toString(2))
        check(tmp.renameTo(file)) { "Cannot save acceptance evidence" }
    }

    private fun writeSilence(file: File) {
        val dataBytes = 3 * 16000 * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        wav.putInt(16000).putInt(32000).putShort(2).putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        file.writeBytes(wav.array())
    }

    companion object {
        private val SESSION_ID = UUID.randomUUID().toString()
        private const val TAG = "NeuroAcceptance"
        private const val TIMEOUT_MS = 600_000L
    }
}
