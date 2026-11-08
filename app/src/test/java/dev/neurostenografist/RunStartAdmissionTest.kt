package dev.neurostenografist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Coverage: [AppGraph.decideRunStart] verdicts that gate ProcessingService's extra
 *  start intent — in particular the guard that a second, different-id run start is
 *  REJECTED while leaving the live service instance (the active owner) intact
 *  instead of stopping the owner's process. */
class RunStartAdmissionTest {
    @Before
    @After
    fun reset() = AppGraph.resetForTest()

    @Test
    fun `start without a run id is ignored`() {
        assertEquals(RunStartDecision.IGNORE_NO_RUN, AppGraph.decideRunStart(null))
    }

    @Test
    fun `duplicate start of the owned run is ignored without touching the owner`() {
        assertTrue(AppGraph.claimRun("run-1"))
        assertEquals(RunStartDecision.IGNORE_DUPLICATE, AppGraph.decideRunStart("run-1"))
        assertEquals("run-1", AppGraph.activeRunId)
    }

    @Test
    fun `free process admits the requested run via atomic claim`() {
        assertEquals(RunStartDecision.START_RUN, AppGraph.decideRunStart("run-1"))
        assertEquals("run-1", AppGraph.activeRunId)
    }

    @Test
    fun `foreign run start is rejected while the active run owner stays intact`() {
        assertTrue(AppGraph.claimRun("run-owner"))
        assertEquals(RunStartDecision.REJECT_RUN_OWNER_ACTIVE, AppGraph.decideRunStart("run-b"))
        // The live service instance owns run-owner: stopping the service would cancel
        // the owner's job, so ownership must survive a rejected foreign start.
        assertEquals("run-owner", AppGraph.activeRunId)
        assertTrue(AppGraph.isBusy)
    }

    @Test
    fun `run start during recording is rejected as idle with no owner`() {
        assertTrue(AppGraph.claimRecording())
        assertEquals(RunStartDecision.REJECT_IDLE_STOP, AppGraph.decideRunStart("run-1"))
        assertTrue(AppGraph.recording)
        assertEquals(null, AppGraph.activeRunId)
    }
}
