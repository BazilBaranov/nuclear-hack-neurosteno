package dev.neurostenografist.inference

import android.os.Build
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException

/** A punctuation edit may change case and punctuation, never words or numbers. */
object PunctuationGuard {
    const val MAX_CHARS = 384
    private val words = Regex("[\\p{L}\\p{N}\\p{M}]+(?:[-'’][\\p{L}\\p{N}\\p{M}]+)*")
    private val numbers = Regex("[+\\-−]?\\p{N}+(?:[.,:/-]\\p{N}+)*(?:\\s?[%‰])?")
    private val allowed = Regex("[\\p{L}\\p{N}\\p{M}\\p{P}\\p{Z}\\s№]*")

    fun accept(original: String, candidate: String): String {
        val result = candidate.trim()
        if (original.isBlank() || result.isBlank() || !allowed.matches(result) || result.any { it in "<>[]{}=`" }) return original
        fun tokens(text: String) = words.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
        if (tokens(original) != tokens(result)) return original
        if (numbers.findAll(original).map { it.value }.toList() != numbers.findAll(result).map { it.value }.toList()) return original
        return result
    }

    fun chunks(text: String): List<String> {
        val result = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > MAX_CHARS) {
            val boundary = rest.lastIndexOf(' ', MAX_CHARS)
            // A pathological unbroken token is left unchanged by the caller.
            if (boundary <= 0) return listOf(text)
            result += rest.substring(0, boundary)
            rest = rest.substring(boundary + 1).trimStart()
        }
        if (rest.isNotBlank()) result += rest
        return result
    }
}

/** One real GGUF model per punctuation stage; call only from a worker dispatcher. */
class LocalPunctuation(
    private val modelFile: File,
    // Honor 200's tested Vulkan driver fails Qwen compute and can poison later GPU sessions.
    preferGpu: Boolean = Build.DEVICE != "HNELIX",
    private val isCancelled: () -> Boolean = { false },
) : AutoCloseable {
    private var handle: Long
    var backendReport: String
        private set

    init {
        require(modelFile.isFile) { "Модель пунктуации не найдена. Загрузите Qwen в разделе «Модели»." }
        System.loadLibrary("neuro_nemotron")
        handle = nativeCreate(modelFile.absolutePath, preferGpu, HardwareAcceleration.cpuThreads())
        check(handle != 0L) { "Не удалось загрузить локальную LLM" }
        backendReport = nativeBackendReport(handle)
    }

    @Synchronized
    fun punctuate(text: String, language: String): String {
        check(handle != 0L) { "Модель пунктуации уже закрыта" }
        if (isCancelled()) throw CancellationException("Обработка отменена")
        if (text.isBlank()) return text
        val chunks = PunctuationGuard.chunks(text)
        if (chunks.any { it.length > PunctuationGuard.MAX_CHARS }) return text
        return chunks.joinToString(" ") { chunk ->
            if (isCancelled()) throw CancellationException("Обработка отменена")
            // The small bilingual model follows this concise English instruction more reliably.
            val languageName = if (language == "ru") "Russian" else "English"
            val prompt = "Add punctuation to the following $languageName text. " +
                "Return the same words with commas, periods, and question marks.\n$chunk"
            val generated = try {
                nativeGenerate(handle, prompt.toByteArray(Charsets.UTF_8), 384).toString(Charsets.UTF_8)
            } catch (failure: IllegalStateException) {
                if (isCancelled()) throw CancellationException("Обработка отменена")
                if (!backendReport.startsWith("GPU ")) throw failure
                // Free failed GPU buffers before retrying the same text on CPU.
                nativeDestroy(handle)
                handle = 0L
                handle = nativeCreate(modelFile.absolutePath, false, HardwareAcceleration.cpuThreads())
                check(handle != 0L) { "Не удалось повторить пунктуацию на CPU" }
                backendReport = nativeBackendReport(handle) + "; ошибка вычисления GPU, повтор на CPU"
                try {
                    nativeGenerate(handle, prompt.toByteArray(Charsets.UTF_8), 384).toString(Charsets.UTF_8)
                } catch (cpuFailure: IllegalStateException) {
                    if (isCancelled()) throw CancellationException("Обработка отменена")
                    throw cpuFailure
                }
            }
            if (isCancelled()) throw CancellationException("Обработка отменена")
            PunctuationGuard.accept(chunk, generated)
        }
    }

    /** Called synchronously by JNI between bounded decode batches and generated tokens. */
    @Suppress("unused")
    private fun isCancellationRequested(): Boolean = isCancelled()

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(path: String, preferGpu: Boolean, threads: Int): Long
    private external fun nativeBackendReport(handle: Long): String
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int): ByteArray
    private external fun nativeDestroy(handle: Long)
}
