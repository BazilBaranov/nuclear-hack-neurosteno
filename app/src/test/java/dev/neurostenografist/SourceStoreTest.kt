package dev.neurostenografist

import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.data.SourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Coverage: fresh install (no file) lists empty, upsert/delete keep order, corrupt
 *  file is unreadable-not-fatal, blank ids rejected, mutations serialized, write
 *  failure keeps the previous good record. */
class SourceStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun source(
        id: String = "src-1",
        name: String = "meeting.wav",
    ) = AudioSource(id = id, uri = "content://x/$id", displayName = name, durationMs = 1000L)

    @Test
    fun `fresh install lists empty without crash`() {
        val store = SourceStore(File(tmp.root, "sources.json"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `upsert then list round trip`() {
        val store = SourceStore(File(tmp.root, "sources.json"))
        store.upsert(source())
        store.upsert(source("src-2"))
        assertEquals(listOf("src-2", "src-1"), store.list().map { it.id })
        // same id replaces the entry
        store.upsert(source(name = "renamed.wav"))
        assertEquals(2, store.list().size)
        assertEquals("renamed.wav", store.list().first { it.id == "src-1" }.displayName)
    }

    @Test
    fun `delete removes only the matching id`() {
        val store = SourceStore(File(tmp.root, "sources.json"))
        store.upsert(source())
        store.upsert(source("src-2"))
        store.delete("src-1")
        assertEquals(listOf("src-2"), store.list().map { it.id })
    }

    @Test
    fun `corrupt json reads as empty and is repaired on next write`() {
        val file = File(tmp.root, "sources.json")
        file.writeText("{not an array")
        val store = SourceStore(file)
        assertTrue(store.list().isEmpty())
        store.upsert(source())
        assertEquals(listOf("src-1"), store.list().map { it.id })
    }

    @Test
    fun `blank ids rejected`() {
        val store = SourceStore(File(tmp.root, "sources.json"))
        assertThrows(IllegalArgumentException::class.java) { store.upsert(source(id = "  ")) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(" ") }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `write failure keeps previous good record`() {
        val file = File(tmp.root, "sources.json")
        val store = SourceStore(file)
        store.upsert(source())
        // make rename fail by deleting the parent chain the tmp write needs after close
        file.parentFile!!.setWritable(false)
        try {
            assertThrows(Exception::class.java) { store.upsert(source("src-2")) }
        } finally {
            file.parentFile!!.setWritable(true)
        }
        assertEquals(listOf("src-1"), store.list().map { it.id })
    }

    @Test
    fun `concurrent upserts do not lose entries`() {
        val store = SourceStore(File(tmp.root, "sources.json"))
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(20)
        repeat(20) { i ->
            pool.submit {
                try {
                    store.upsert(source("s-$i"))
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(20, store.list().size)
    }
}
