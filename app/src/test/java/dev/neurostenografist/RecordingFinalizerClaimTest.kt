package dev.neurostenografist

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Coverage: the single-finalizer claim behind RecordingService's Stop/Abort handling —
 *  a repeated Stop/Abort must not claim finalization while the first finalizer still
 *  owns the recorder (that would clear recorder/claim state mid-publication), ownership
 *  is released only by its owner, and destroy-time takeover succeeds only when the
 *  finalizer coroutine never ran. */
class RecordingFinalizerClaimTest {
    @Before
    @After
    fun reset() = AppGraph.resetForTest()

    @Test
    fun `a repeated stop intent cannot claim while the first finalizer owns the recorder`() {
        val recorder = Any()
        assertTrue(AppGraph.claimFinalizer(recorder))
        // second Stop/Abort for the same recorder: the claim is held until cleanup
        assertFalse(AppGraph.claimFinalizer(recorder))
        assertTrue(AppGraph.finalizerActive(recorder))
        AppGraph.releaseFinalizer(recorder)
        // only now can a new finalizer take over the (already terminated) recorder
        assertTrue(AppGraph.claimFinalizer(recorder))
    }

    @Test
    fun `the claim slot is exclusive across recordings with fresh recorder objects`() {
        val first = Any()
        val second = Any()
        assertTrue(AppGraph.claimFinalizer(first))
        // a second in-flight finalization (fresh recorder object) cannot interleave
        assertFalse(AppGraph.claimFinalizer(second))
        assertTrue(AppGraph.releaseFinalizer(first))
        // the next recording's finalizer takes over once the former owner released
        assertTrue(AppGraph.claimFinalizer(second))
    }

    @Test
    fun `only the owner releases the finalizer claim`() {
        val recorder = Any()
        val foreign = Any()
        assertTrue(AppGraph.claimFinalizer(recorder))
        assertFalse(AppGraph.releaseFinalizer(foreign)) // a foreign key must not release
        assertTrue(AppGraph.finalizerActive(recorder))
        assertTrue(AppGraph.releaseFinalizer(recorder))
        assertFalse(AppGraph.finalizerActive(recorder))
    }

    @Test
    fun `destroy-time takeover succeeds on a recorder no finalizer owns`() {
        val recorder = Any()
        // no Stop/Abort ever ran: the claim is free, so OnDestroy's takeover path can
        // claim, abort and release without racing an in-flight finalizer
        assertTrue(AppGraph.claimFinalizer(recorder))
        assertTrue(AppGraph.releaseFinalizer(recorder))
        assertFalse(AppGraph.finalizerActive(recorder))
        // with the finalizer gone the recording claim itself is releasable again
        assertTrue(AppGraph.claimRecording())
        AppGraph.releaseRecording()
        assertFalse(AppGraph.recording)
    }
}
