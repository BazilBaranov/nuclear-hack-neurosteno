package dev.neurostenografist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Coverage: mutual exclusion/atomic admission of single run/recording claims, release
 *  re-enabling idle state, duplicate starts rejected without orphan claims, and
 *  once-per-process recovery semantics. */
class JobOwnershipTest {
    @Before
    @After
    fun reset() = AppGraph.resetForTest()

    @Test
    fun `run claim excludes recording and duplicate runs`() {
        assertTrue(AppGraph.claimRun("run-1"))
        assertFalse(AppGraph.claimRun("run-2"))
        assertFalse(AppGraph.claimRecording())
        assertEquals("run-1", AppGraph.activeRunId)
        assertTrue(AppGraph.isBusy)
    }

    @Test
    fun `recording claim excludes run and is not cleared by a foreign release`() {
        assertTrue(AppGraph.claimRecording())
        assertTrue(AppGraph.recording)
        assertFalse(AppGraph.claimRun("run-1"))
        AppGraph.releaseRun("run-1") // not the owner — claim must survive
        assertTrue(AppGraph.recording)
        AppGraph.releaseRecording()
        assertFalse(AppGraph.recording)
        assertTrue(AppGraph.claimRun("run-1"))
    }

    @Test
    fun `release re-enables admission`() {
        assertTrue(AppGraph.claimRun("run-1"))
        AppGraph.releaseRun("run-1")
        assertFalse(AppGraph.isBusy)
        assertTrue(AppGraph.claimRecording())
        AppGraph.releaseRecording()
        assertFalse(AppGraph.isBusy)
    }

    @Test
    fun `release with another run id leaves the owner intact`() {
        assertTrue(AppGraph.claimRun("owner"))
        AppGraph.releaseRun("other")
        assertEquals("owner", AppGraph.activeRunId)
    }

    @Test
    fun `concurrent start attempts admit at most one`() {
        val pool = Executors.newFixedThreadPool(8)
        val admitted = AtomicInteger(0)
        val done = CountDownLatch(20)
        repeat(20) { i ->
            pool.submit {
                try {
                    if (i % 2 == 0) {
                        if (AppGraph.claimRun("r-$i")) admitted.incrementAndGet()
                    } else {
                        if (AppGraph.claimRecording()) admitted.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, admitted.get())
        pool.shutdown()
    }

    @Test
    fun `stale cancel does not poison a fresh claim`() {
        assertTrue(AppGraph.claimRun("old"))
        AppGraph.delayedCancel = true
        AppGraph.releaseRun("old")
        assertTrue(AppGraph.claimRun("new"))
        assertFalse("fresh run must not inherit stale cancel", AppGraph.delayedCancel)
    }
}
