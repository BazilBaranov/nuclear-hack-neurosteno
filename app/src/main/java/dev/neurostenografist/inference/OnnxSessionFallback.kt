package dev.neurostenografist.inference

import java.util.concurrent.CancellationException

/** One native engine at a time; NNAPI exceptions retry once on a fresh CPU engine. */
internal class OnnxSessionFallback<T>(
    private var provider: String,
    private val create: (String) -> T,
    private val release: (T) -> Unit,
    private val onBackendReport: (String) -> Unit = {},
) : AutoCloseable {
    private var engine: T? = null
    var backendReport: String = "Провайдер: ${provider.substringBefore(':')}"
        private set

    init {
        engine = try { create(provider) } catch (error: Exception) {
            fallback(error)
            create(provider)
        }
    }

    fun <R> run(block: (T) -> R): R {
        val current = checkNotNull(engine) { "Сессия уже закрыта" }
        return try { block(current) } catch (error: Exception) {
            if (!canRetry(error)) throw error
            engine = null
            release(current)
            fallback(error)
            val replacement = create(provider)
            engine = replacement
            block(replacement)
        }
    }

    private fun canRetry(error: Exception): Boolean =
        provider.substringBefore(':') == "nnapi" && error !is CancellationException

    private fun fallback(error: Exception) {
        if (!canRetry(error)) throw error
        provider = "cpu" + provider.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
        backendReport = "CPU fallback: NNAPI отклонил модель или вычисление (${error.javaClass.simpleName})"
        onBackendReport(backendReport)
    }

    override fun close() {
        val current = engine ?: return
        engine = null
        release(current)
    }
}
