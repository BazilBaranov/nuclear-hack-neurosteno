package dev.neurostenografist.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.CancellationException

/** Runs the same Qwen model in LiteRT-LM format when its optional weights are installed. */
internal class LiteRtPunctuation(
    model: File,
    private val cpuModel: File,
    cacheDir: File,
    private val isCancelled: () -> Boolean,
) : TextPunctuator {
    private var gpu: Engine? = null
    private var cpu: LocalPunctuation? = null
    private val usedModels = linkedSetOf<String>()

    override val modelIds: Set<String> get() = usedModels.toSet()
    override var backendReport = "Пунктуация: LiteRT-LM (запрошен GPU OpenCL)"
        private set

    init {
        checkCancellation()
        try {
            gpu = Engine(EngineConfig(modelPath = model.path, backend = Backend.GPU(),
                maxNumTokens = 2048, cacheDir = cacheDir.path)).apply { initialize() }
        } catch (failure: Exception) {
            fallBack(failure)
        } catch (failure: LinkageError) {
            fallBack(failure)
        }
    }

    override fun punctuate(text: String, language: String): String {
        checkCancellation()
        val chunks = PunctuationGuard.chunks(text)
        if (chunks.any { it.length > PunctuationGuard.MAX_CHARS }) return text
        return chunks.joinToString(" ") { chunk ->
            checkCancellation()
            val active = gpu
            if (active == null) return@joinToString punctuateOnCpu(chunk, language)
            try {
                val config = ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = 384,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
                val candidate = active.createConversation(config).use { conversation ->
                    conversation.sendMessage(PunctuationGuard.prompt(chunk, language)).contents.contents
                        .filterIsInstance<Content.Text>().joinToString("") { it.text }
                }
                checkCancellation()
                usedModels += ModelCatalog.PUNCTUATION_LITERT
                PunctuationGuard.accept(chunk, candidate)
            } catch (failure: Exception) {
                fallBack(failure)
                punctuateOnCpu(chunk, language)
            } catch (failure: LinkageError) {
                fallBack(failure)
                punctuateOnCpu(chunk, language)
            }
        }
    }

    private fun punctuateOnCpu(chunk: String, language: String): String {
        checkCancellation()
        val active = cpu ?: LocalPunctuation(cpuModel, isCancelled).also { cpu = it }
        usedModels += ModelCatalog.PUNCTUATION
        return active.punctuate(chunk, language)
    }

    private fun fallBack(failure: Throwable) {
        if (failure is CancellationException) throw failure
        checkCancellation()
        val failed = gpu
        gpu = null
        if (failed != null) {
            try {
                failed.close()
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
        }
        backendReport = "Пунктуация: CPU (LiteRT-LM: ${failure.javaClass.simpleName})"
    }

    private fun checkCancellation() {
        if (isCancelled()) throw CancellationException("Обработка отменена")
    }

    override fun close() {
        try {
            gpu?.close()
        } finally {
            gpu = null
            cpu?.close()
            cpu = null
        }
    }
}
