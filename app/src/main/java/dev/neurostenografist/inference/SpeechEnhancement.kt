package dev.neurostenografist.inference

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import dev.neurostenografist.audio.AudioDecoder
import java.io.File
import java.util.concurrent.CancellationException

/** One recurrent GTCRN session for the recording, with bounded chunks and an explicit tail flush. */
class GtcrnEnhancer(bundleDir: File) : AutoCloseable {
    private val engine = OnlineSpeechDenoiser(
        config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(File(bundleDir, "gtcrn.onnx").absolutePath),
                numThreads = 2,
                provider = "cpu",
            ),
        ),
    )
    private var closed = false

    init {
        if (engine.sampleRate != AudioDecoder.TARGET_SAMPLE_RATE) {
            engine.release()
            error("Модель шумоподавления должна работать на частоте 16000 Гц")
        }
    }

    fun processInPlace(
        samples: FloatArray,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ) {
        check(!closed)
        engine.reset()
        enhanceStreamInPlace(
            samples,
            run = { chunk ->
                val result = engine.run(chunk, AudioDecoder.TARGET_SAMPLE_RATE)
                check(result.sampleRate == AudioDecoder.TARGET_SAMPLE_RATE) { "Шумоподавление изменило частоту аудио" }
                result.samples
            },
            flush = {
                val tail = engine.flush()
                check(tail.sampleRate == AudioDecoder.TARGET_SAMPLE_RATE) { "Шумоподавление изменило частоту аудио" }
                tail.samples
            },
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
    }

    override fun close() {
        if (!closed) {
            engine.release()
            closed = true
        }
    }
}

/** Native output may lag input; never overwrite unconsumed input or silently change its length. */
internal fun enhanceStreamInPlace(
    samples: FloatArray,
    run: (FloatArray) -> FloatArray,
    flush: () -> FloatArray,
    isCancelled: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {},
) {
    var written = 0
    var consumed = 0
    fun checkCancelled() {
        if (isCancelled()) throw CancellationException("Шумоподавление отменено")
    }
    fun write(output: FloatArray) {
        check(output.size <= consumed - written) { "Шумоподавление вернуло лишние сэмплы" }
        check(output.all { it.isFinite() }) { "Шумоподавление вернуло некорректный звук" }
        output.copyInto(samples, written)
        written += output.size
    }
    while (consumed < samples.size) {
        checkCancelled()
        val end = minOf(consumed + AudioDecoder.TARGET_SAMPLE_RATE, samples.size)
        val chunk = samples.copyOfRange(consumed, end)
        consumed = end
        write(run(chunk))
        onProgress(written.toFloat() / samples.size)
    }
    checkCancelled()
    write(flush())
    check(written == samples.size) { "Шумоподавление изменило длительность записи: $written вместо ${samples.size} сэмплов" }
    onProgress(1f)
}
