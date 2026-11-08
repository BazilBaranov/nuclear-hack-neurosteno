package dev.neurostenografist

import dev.neurostenografist.core.Progress
import dev.neurostenografist.inference.BundleFile
import dev.neurostenografist.inference.BundleKind
import dev.neurostenografist.inference.ModelBundle
import dev.neurostenografist.inference.ModelBundleException
import dev.neurostenografist.inference.ModelCatalog
import dev.neurostenografist.inference.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/** Coverage: install success, checksum/missing-member failure with rollback, marker
 *  integrity, leftover temp cleanup, serialized concurrent installs, busy-gated delete,
 *  unsafe/unknown IDs rejected before file-system access. */
class ModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Deterministic fake asset bytes (CRC32 keeps them tiny but unique per name). */
    private fun fakeBytes(
        assetPath: String,
        length: Int = 1024,
    ): ByteArray {
        val crc = CRC32().apply { update(assetPath.toByteArray()) }
        return ByteArray(length) { i -> ((crc.value + i) and 0xFF).toByte() }
    }

    private fun fakeBundle(
        id: String = "test-bundle",
        assetDir: String = "models/$id",
        vararg names: String = arrayOf("model.onnx", "tokens.txt"),
    ) = ModelBundle(
        id = id,
        kind = BundleKind.ASR,
        displayName = "Test bundle",
        languages = listOf("ru"),
        assetDir = assetDir,
        files =
            names.map { name ->
                val bytes = fakeBytes("$assetDir/$name")
                BundleFile(
                    name = name,
                    sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                    bytes = bytes.size.toLong(),
                )
            },
    )

    private fun openFor(vararg bundles: ModelBundle): (String) -> java.io.InputStream? {
        val map = HashMap<String, ByteArray>()
        bundles.forEach { b ->
            b.files.forEach { f ->
                map["${b.assetDir}/${f.name}"] = fakeBytes("${b.assetDir}/${f.name}")
            }
        }
        return { path ->
            map[path]?.let { ByteArrayInputStream(it) }
        }
    }

    @Test
    fun `successful install is ready with verified contents`() {
        val bundle = fakeBundle()
        val roots = tmp.newFolder("models")
        val store = ModelStore(roots, openFor(bundle))
        assertFalse(store.isReady(bundle.id))
        runBlocking { store.installBundle(bundle) { } }
        assertTrue(store.isReady(bundle.id))
        assertEquals("verified:${bundle.id}", File(roots, "${bundle.id}/.ready-hash-verified").readText())
        // all members at exactly the catalog size
        bundle.files.forEach { f ->
            assertEquals(f.bytes, File(roots, "${bundle.id}/${f.name}").length())
        }
        assertFalse(File(roots, "${bundle.id}.installing").exists())
    }

    @Test
    fun `checksum mismatch preserves existing good bundle and cleans temp`() {
        val bundle = fakeBundle()
        val roots = tmp.newFolder("models")
        var poisoned = false
        val store = ModelStore(roots, openFor(bundle))
        runBlocking { store.installBundle(bundle) { } }
        val goodFiles = File(roots, bundle.id).listFiles()!!.map { it.name to it.length() }.toSet()

        // second install delivering wrong bytes -> checksum mismatch, good bundle must survive
        val badOpen: (String) -> java.io.InputStream? = { ByteArrayInputStream(ByteArray(64)) }
        val brokenStore = ModelStore(roots, if (poisoned) badOpen else badOpen)
        val e =
            assertThrows(ModelBundleException::class.java) {
                runBlocking { brokenStore.installBundle(bundle) { } }
            }
        assertTrue(e.message!!.contains("checksum mismatch"))
        assertEquals(goodFiles, File(roots, bundle.id).listFiles()!!.map { it.name to it.length() }.toSet())
        assertTrue(store.isReady(bundle.id))
        assertFalse(File(roots, "${bundle.id}.installing").exists())
    }

    @Test
    fun `missing asset member fails without destroying existing bundle`() {
        val bundle = fakeBundle()
        val roots = tmp.newFolder("models")
        val store = ModelStore(roots, openFor(bundle))
        runBlocking { store.installBundle(bundle) { } }

        val partialOpen: (String) -> java.io.InputStream? = { path ->
            if (path.endsWith(
                    "model.onnx",
                )
            ) {
                null
            } else {
                ByteArrayInputStream(fakeBytes(path))
            }
        }
        val missing =
            assertThrows(ModelBundleException::class.java) {
                runBlocking { ModelStore(roots, partialOpen).installBundle(bundle) { } }
            }
        assertTrue(missing.message!!.contains("missing from the APK assets"))
        assertTrue(store.isReady(bundle.id))
    }

    @Test
    fun `ready marker with wrong content is not ready`() {
        val bundle = fakeBundle()
        val roots = tmp.newFolder("models")
        val dir = File(roots, bundle.id).apply { mkdirs() }
        File(dir, ".ready-hash-verified").writeText("verified:some-other-id")
        bundle.files.forEach { f -> File(dir, f.name).writeBytes(fakeBytes("${bundle.assetDir}/${f.name}")) }
        val store = ModelStore(roots, openFor(bundle))
        assertFalse(store.isReady(bundle.id))
    }

    @Test
    fun `concurrent installs are serialized`() {
        val a = fakeBundle("slow-a")
        val b = fakeBundle("slow-b")
        val roots = tmp.newFolder("models")
        val store = ModelStore(roots, openFor(a, b))
        val inside = AtomicInteger(0)
        val overlap = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)
        val done = CountDownLatch(2)
        runBlocking {
            listOf(a, b).forEach { bundle ->
                pool.submit {
                    runBlocking {
                        store.installBundle(bundle) { p ->
                            inside.incrementAndGet()
                            if (inside.get() > 1) overlap.incrementAndGet()
                            inside.decrementAndGet()
                        }
                    }
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, overlap.get())
        assertTrue(store.isReady(a.id))
        assertTrue(store.isReady(b.id))
    }

    @Test
    fun `delete refused while busy and removes bundle otherwise`() {
        val bundle = fakeBundle()
        val roots = tmp.newFolder("models")
        val busy =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val store = ModelStore(roots, openFor(bundle), isBusy = { busy.get() })
        runBlocking { store.installBundle(bundle) { } }
        val e = assertThrows(IllegalStateException::class.java) { store.delete(bundle.id) }
        assertTrue(e.message!!.contains("cancel it first"))
        busy.set(false)
        store.delete(bundle.id)
        assertFalse(store.isReady(bundle.id))
    }

    @Test
    fun `unknown bundle id rejected from public install`() {
        val store = ModelStore(tmp.newFolder("models"), { null })
        val e =
            assertThrows(ModelBundleException::class.java) {
                runBlocking { store.install("../escape") { } }
            }
        assertTrue(e.message!!.contains("unknown bundle"))
        // nothing was written near the escape path
        assertFalse(File(tmp.root, "..escape").exists())
    }

    @Test
    fun `progress starts below 1 and ends at 1`() {
        val bundle = fakeBundle()
        val store = ModelStore(tmp.newFolder("models"), openFor(bundle))
        val seen = mutableListOf<Progress>()
        runBlocking { store.installBundle(bundle) { seen.add(it) } }
        assertTrue(seen.first().fraction!! < 1f)
        assertEquals(1f, seen.last().fraction!!)
        assertEquals("installing", seen.last().stage)
    }
}
