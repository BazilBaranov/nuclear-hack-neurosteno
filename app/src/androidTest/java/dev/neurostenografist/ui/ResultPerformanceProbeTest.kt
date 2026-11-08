package dev.neurostenografist.ui

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.neurostenografist.AppGraph
import dev.neurostenografist.MainActivity
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.Turn
import java.util.concurrent.atomic.AtomicLong
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI load only: reports frames, never represents speech quality or inference speed. */
@RunWith(AndroidJUnit4::class)
class ResultPerformanceProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun repeatedLargeTranscriptNavigationReportsFrameTimes() {
        val store = AppGraph.runStore(compose.activity)
        val source = AudioSource("ui-performance-probe", "file:///unused.wav", "Проверка интерфейса", 2_000_000L)
        val runId = store.create(source, RunRequest("auto"))
        val thread = HandlerThread("frame-probe").apply { start() }
        val frames = AtomicLong()
        val slowFrames = AtomicLong()
        val droppedReports = AtomicLong()
        val totalNanos = AtomicLong()
        val maximumNanos = AtomicLong()
        val budgetNanos = (1_000_000_000.0 / compose.activity.windowManager.defaultDisplay.refreshRate).toLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            droppedReports.addAndGet(dropped.toLong())
            val elapsed = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (elapsed >= 0) {
                frames.incrementAndGet()
                totalNanos.addAndGet(elapsed)
                maximumNanos.updateAndGet { maxOf(it, elapsed) }
                if (elapsed > budgetNanos) slowFrames.incrementAndGet()
            }
        }
        try {
            store.complete(CompletedRun(runId, source.id,
                List(2000) { Turn("S${it % 3}", it * 1000L, (it + 1) * 1000L, "Проверочная реплика $it для нагрузки интерфейса") },
                "ru", emptyList(), emptyMap(), emptyMap(), emptyList()))
            compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            compose.onNodeWithTag("tab_transcription").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("open_result_$runId").fetchSemanticsNodes().isNotEmpty() }
            repeat(10) {
                compose.onNodeWithTag("open_result_$runId").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("result_turns").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("result_save").assertIsDisplayed()
                compose.onNodeWithText("Назад").performClick()
            }
        } finally {
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            thread.join(2000)
            store.deleteRun(runId)
            val count = frames.get()
            Log.i("NeuroUiProbe", "synthetic=true iterations=10 turns=2000 frames=$count slowFrames=${slowFrames.get()} droppedReports=${droppedReports.get()} frameBudgetNs=$budgetNanos meanFrameNs=${if (count == 0L) 0 else totalNanos.get() / count} maxFrameNs=${maximumNanos.get()}")
        }
    }
}
