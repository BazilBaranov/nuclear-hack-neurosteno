package dev.neurostenografist.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device acceptance probe of [ModelStore]: real HTTPS downloads, SHA-256
 * verification and tamper repair. Requires internet and downloads the baseline bundles.
 */
@RunWith(AndroidJUnit4::class)
class ModelStoreAcceptanceTest {
    private fun store(): ModelStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ModelStore(context, isBusy = { false })
    }

    @Test
    fun install_isReady_and_tamperRepair() {
        val modelStore = store()
        // force a clean start so extraction is measured in full
        val baseline = listOf(ModelCatalog.WHISPER_TINY, ModelCatalog.DIARIZATION).map(ModelCatalog::byId)
        baseline.forEach { modelStore.delete(it.id) }
        baseline.forEach { bundle ->
            assertTrue("bundle dir should be empty before install", !modelStore.isReady(bundle.id))
            val startedAt = System.nanoTime()
            runBlocking { modelStore.install(bundle.id, {}) }
            val extractMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("install must produce a verified, sized bundle", modelStore.isReady(bundle.id))
            println("ACCEPTANCE ${bundle.id} extractMs=$extractMs bytes=${bundle.totalBytes}")
        }

        // tamper with one bundled file: isReady must fail fast, reinstall must repair
        val diarization = modelStore.bundleDir(ModelCatalog.DIARIZATION)
        val victim = diarization.listFiles().orEmpty().first { it.isFile && !it.name.startsWith(".ready") }
        val originalBytes = victim.readBytes()
        victim.appendBytes(ByteArray(1))
        assertTrue("tampered bundle must not be ready", !modelStore.isReady(ModelCatalog.DIARIZATION))
        victim.writeBytes(originalBytes)
        runBlocking { modelStore.install(ModelCatalog.DIARIZATION, {}) }
        assertTrue("reinstall must repair a tampered bundle", modelStore.isReady(ModelCatalog.DIARIZATION))
        assertTrue(
            "reinstall must not leave install temp dirs behind",
            !File(diarization.parentFile, "${ModelCatalog.DIARIZATION}.installing").exists(),
        )

        assertTrue("weights must not ship inside the APK", assets().list("models").orEmpty().isEmpty())
    }

    private fun assets() = InstrumentationRegistry.getInstrumentation().targetContext.assets
}
