package dev.neurostenografist.inference

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserVoxLockModelConfig
import dev.neurostenografist.core.Turn
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface SpeakerIsolation : AutoCloseable {
    fun hasReference(speaker: String): Boolean
    fun isolate(samples: FloatArray, speaker: String): FloatArray
}

/** Speaker-conditioned extraction; never guesses enrollment from the mixed crop. */
class VoxLockIsolation(
    private val bundleDir: File,
    private val cacheDir: File,
    private val original: FloatArray,
    private val turns: List<Turn>,
) : SpeakerIsolation {
    private val references = turns.map { it.speakerId }.distinct().associateWith { OverlapPlan.enrollment(turns, it) }
    private var currentSpeaker: String? = null
    private var engine: OfflineSpeechDenoiser? = null
    private var referenceFile: File? = null

    override fun hasReference(speaker: String): Boolean =
        references[speaker]?.sumOf { it.second - it.first } == 3000L

    override fun isolate(samples: FloatArray, speaker: String): FloatArray {
        require(hasReference(speaker)) { "Для выделения голоса нужны 3 секунды чистой речи этого участника" }
        if (currentSpeaker != speaker) {
            close()
            cacheDir.mkdirs()
            val file = File.createTempFile("voice-reference-", ".wav", cacheDir)
            referenceFile = file
            val pcm = ByteBuffer.allocate(48000 * 2).order(ByteOrder.LITTLE_ENDIAN)
            for ((start, end) in references.getValue(speaker)) {
                for (i in (start * 16).toInt() until (end * 16).toInt()) {
                    pcm.putShort((original[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                }
            }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.position()).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(pcm.position())
            file.outputStream().use { out -> out.write(header.array()); out.write(pcm.array(), 0, pcm.position()) }
            try {
                engine = OfflineSpeechDenoiser(config = OfflineSpeechDenoiserConfig(
                    model = OfflineSpeechDenoiserModelConfig(
                        voxlock = OfflineSpeechDenoiserVoxLockModelConfig(
                            enrollModel = File(bundleDir, "enroll.onnx").absolutePath,
                            chunkModel = File(bundleDir, "chunk_int8.onnx").absolutePath,
                            voiceSample = file.absolutePath,
                        ),
                        numThreads = HardwareAcceleration.cpuThreads(), provider = "cpu",
                    ),
                ))
                currentSpeaker = speaker
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }
        val result = engine!!.run(samples, 16000)
        check(result.sampleRate == 16000 && result.samples.size == samples.size) {
            "Выделение голоса изменило временную шкалу; результат отклонён"
        }
        check(result.samples.all { it.isFinite() }) { "Выделение голоса вернуло повреждённое аудио" }
        return result.samples
    }

    override fun close() {
        engine?.release()
        engine = null
        currentSpeaker = null
        referenceFile?.delete()
        referenceFile = null
    }
}
