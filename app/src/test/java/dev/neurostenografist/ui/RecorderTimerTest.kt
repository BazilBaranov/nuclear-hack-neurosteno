package dev.neurostenografist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for original finding #7: the ViewModel emits the real elapsed
 * duration, but the screen used to subtract it from `System.currentTimeMillis()`
 * as if it were a start timestamp, rendering nonsense elapsed time. The displayed
 * value must be the polled duration itself, and must be a pure function of the
 * UiState (no wall-clock read), so the test is deterministic without a fake clock.
 */
class RecorderTimerTest {
    @Test
    fun `polled duration is displayed as-is, not subtracted from wall clock`() {
        // Old bug: clockNow() - 65_000 would yield ~1.7e12 instead of 65_000.
        val state =
            AppViewModel.UiState(
                recording = true,
                recordingElapsedMs = 65_000,
            )
        assertEquals(65_000, displayedElapsedMs(state))
    }

    @Test
    fun `stale duration state is not captured once recording stops`() {
        // The old LaunchedEffect was keyed only to `recording` and kept stale values.
        val state =
            AppViewModel.UiState(
                recording = false,
                recordingElapsedMs = 65_000,
            )
        assertEquals(0, displayedElapsedMs(state))
    }

    @Test
    fun `not-yet-polled duration while recording renders as zero`() {
        val state =
            AppViewModel.UiState(
                recording = true,
                recordingElapsedMs = 0,
            )
        assertEquals(0, displayedElapsedMs(state))
    }

    @Test
    fun `formatElapsed renders the duration as m_ss`() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("1:05", formatElapsed(65_000))
        assertEquals("10:01", formatElapsed(601_000))
    }
}
