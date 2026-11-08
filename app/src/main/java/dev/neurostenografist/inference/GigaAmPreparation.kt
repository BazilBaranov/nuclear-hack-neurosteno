package dev.neurostenografist.inference

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Keep one GigaAM NNAPI session warm. Never block a recording on slow driver compilation. */
internal object GigaAmPreparation {
    private const val WAIT_MS = 1_500L
    private const val IDLE_SECONDS = 45L
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "GigaAM-NNAPI").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    private class Preparation(val modelDir: File) {
        val ready = CompletableFuture<SherpaAsr>()
        var leased = false
        var expiry: ScheduledFuture<*>? = null
    }

    private var pending: Preparation? = null

    fun acquire(modelDir: File, onBackendReport: (String) -> Unit): Asr? {
        val preparation = synchronized(this) {
            val current = pending
            when {
                current == null -> start(modelDir)
                current.modelDir == modelDir -> current
                else -> return null
            }
        }
        val engine = try {
            preparation.ready.get(WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            onBackendReport("CPU: NNAPI ещё готовит энкодер GigaAM в фоне")
            return null
        } catch (error: ExecutionException) {
            onBackendReport("CPU: подготовка NNAPI для GigaAM завершилась ошибкой (${error.cause?.javaClass?.simpleName})")
            return null
        }
        synchronized(this) {
            if (pending !== preparation || preparation.leased) return null
            preparation.leased = true
            preparation.expiry?.cancel(false)
        }
        return object : Asr {
            private var closed = false
            private var reported = engine.backendReport

            init { onBackendReport("GigaAM: $reported") }

            override fun transcribe(samples: FloatArray): String {
                check(!closed)
                val text = engine.transcribe(samples)
                if (reported != engine.backendReport) {
                    reported = engine.backendReport
                    onBackendReport("GigaAM: $reported")
                }
                return text
            }

            override fun close() {
                if (closed) return
                closed = true
                synchronized(GigaAmPreparation) {
                    preparation.leased = false
                    expireWhenIdle(preparation)
                }
            }
        }
    }

    private fun start(modelDir: File): Preparation = Preparation(modelDir).also { preparation ->
        pending = preparation
        worker.execute {
            try {
                preparation.ready.complete(SherpaAsr(ModelCatalog.GIGAAM_V3, modelDir, "ru", "nnapi"))
            } catch (error: Throwable) {
                preparation.ready.completeExceptionally(error)
                if (error !is Exception && error !is LinkageError) throw error
            } finally {
                synchronized(this) {
                    if (!preparation.leased) expireWhenIdle(preparation)
                }
            }
        }
    }

    private fun expireWhenIdle(preparation: Preparation) {
        preparation.expiry = worker.schedule({
            val engine = synchronized(this) {
                if (pending !== preparation || preparation.leased) return@schedule
                pending = null
                if (preparation.ready.isCompletedExceptionally) null else preparation.ready.getNow(null)
            }
            engine?.close()
        }, IDLE_SECONDS, TimeUnit.SECONDS)
    }
}
