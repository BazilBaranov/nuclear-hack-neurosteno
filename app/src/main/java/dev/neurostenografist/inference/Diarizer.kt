package dev.neurostenografist.inference

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.neurostenografist.core.SpeakerInterval
import java.io.File

fun createDiarizer(
    bundleId: String,
    bundleDir: File,
    provider: String = "cpu",
    onBackendReport: (String) -> Unit = {},
): Diarizer =
    when (bundleId) {
        ModelCatalog.DIARIZATION -> SherpaDiarizer(bundleDir, provider, onBackendReport)
        ModelCatalog.NEMOTRON -> NemotronDiarizer(bundleDir)
        else -> throw ModelBundleException("Unsupported diarization model '$bundleId'; no model substitution is allowed")
    }

/** Global speaker diarization over 16 kHz mono samples. */
interface Diarizer {
    val modelIds: Set<String> get() = emptySet()
    fun diarize(samples: FloatArray): List<SpeakerInterval>

    /** Releases every acquired native resource; idempotent and safe by default. */
    fun close() {}
}

/**
 * Global speaker diarization over the full 16 kHz recording. The cluster count is
 * estimated with FastClustering; the threshold/limits are hand-picked from a handful
 * of host fixtures and are NOT calibrated — the pipeline surfaces a warning in every
 * completed run instead of claiming measured accuracy.
 */
class SherpaDiarizer(
    bundleDir: File,
    provider: String = "cpu",
    onBackendReport: (String) -> Unit = {},
) : Diarizer {
    private val diarization: OnnxSessionFallback<OfflineSpeakerDiarization>

    init {
        val segmentation = File(bundleDir, SEGMENTATION_FILE)
        val embedding = File(bundleDir, EMBEDDING_FILE)
        if (!segmentation.isFile || !embedding.isFile) {
            throw ModelBundleException("diarization bundle incomplete in ${bundleDir.absolutePath}")
        }
        diarization = OnnxSessionFallback(provider, create = { selectedProvider ->
            OfflineSpeakerDiarization(
                null, // null asset manager => native loads real file paths below
                OfflineSpeakerDiarizationConfig(
                    segmentation =
                        OfflineSpeakerSegmentationModelConfig(
                            pyannote =
                                OfflineSpeakerSegmentationPyannoteModelConfig(
                                    model = segmentation.absolutePath,
                                ),
                            numThreads = HardwareAcceleration.cpuThreads(),
                            debug = false,
                            provider = selectedProvider,
                        ),
                    embedding =
                        SpeakerEmbeddingExtractorConfig(
                            model = embedding.absolutePath,
                            numThreads = HardwareAcceleration.cpuThreads(),
                            debug = false,
                            provider = selectedProvider,
                        ),
                    clustering =
                        FastClusteringConfig(
                            numClusters = -1, // automatic cluster count
                            threshold = CLUSTER_THRESHOLD,
                            computeConfidence = false,
                        ),
                    minDurationOn = MIN_DURATION_ON,
                    minDurationOff = MIN_DURATION_OFF,
                ),
            )
        }, release = { it.release() }, onBackendReport = onBackendReport)
    }

    val backendReport: String get() = diarization.backendReport

    fun sampleRate(): Int = diarization.run { it.sampleRate() }

    override fun diarize(samples: FloatArray): List<SpeakerInterval> {
        require(samples.size >= diarization.run { it.sampleRate() } / 2) { "audio too short for diarization" }
        val segments = diarization.run { it.process(samples) }
        return segments
            .sortedBy { it.start }
            .map { SpeakerInterval(speaker = it.speaker, startSec = it.start.toDouble(), endSec = it.end.toDouble()) }
    }

    private var released = false

    override fun close() {
        if (!released) {
            released = true
            diarization.close()
        }
    }

    companion object {
        private const val SEGMENTATION_FILE = "segmentation.int8.onnx"
        private const val EMBEDDING_FILE = "titanet-small.onnx"

        /**
         * Chosen by hand on host fixtures (2/3/4-speaker wavs): 0.7 reproduced the
         * true speaker counts where 0.55 over-clustered. Not calibrated: flag stays.
         */
        private const val CLUSTER_THRESHOLD = 0.7f
        private const val MIN_DURATION_ON = 0.1f
        private const val MIN_DURATION_OFF = 0.1f
    }
}
