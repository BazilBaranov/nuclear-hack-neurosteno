package dev.neurostenografist.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dev.neurostenografist.AppGraph
import dev.neurostenografist.MainActivity
import dev.neurostenografist.inference.ModelCatalog
import org.junit.Assume.assumeTrue
import dev.neurostenografist.core.AudioSource
import dev.neurostenografist.core.CompletedRun
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.Turn
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real navigation instrumented checks: the drawer is genuinely reachable from the
 * home screen (no unreachable state), all three drawer destinations open their
 * screens, both tabs are visible on home, and the recorder tab is the first tab.
 * Navigation is exercised on the real [MainActivity] activity.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openRun(id: String) {
        lateinit var vm: AppViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(5000) { vm.ui.value.runs.any { it.id == id } }
        compose.onNodeWithTag("transcription_content").performScrollToNode(hasTestTag("open_result_$id"))
        compose.onNodeWithTag("open_result_$id").performClick()
    }

    @Test
    fun homeShowsBothTabsAndRecorderFirst() {
        compose.onNodeWithTag("top_tabs").assertIsDisplayed()
        compose.onNodeWithTag("tab_recorder").assertIsDisplayed()
        compose.onNodeWithTag("tab_transcription").assertIsDisplayed()
        // Recorder tab content (status text) present under the first tab.
        compose.onNodeWithTag("recorder_status").assertIsDisplayed()
    }

    @Test
    fun drawerIsReachableAndOpensModelsScreen() {
        compose.onNodeWithTag("menu_button").performClick()
        compose.onNodeWithTag("nav_drawer").assertIsDisplayed()
        compose.onNodeWithTag("nav_models").performClick()
        compose.onNodeWithTag("models_screen").assertIsDisplayed()
    }

    @Test
    fun drawerOpensHistoryScreen() {
        compose.onNodeWithTag("menu_button").performClick()
        compose.onNodeWithTag("nav_history").performClick()
        compose.onNodeWithTag("history_screen").assertIsDisplayed()
        val historyTop = compose.onNodeWithTag("history_screen").fetchSemanticsNode().boundsInWindow.top
        compose.runOnIdle {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            val safeTop = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            assertTrue("History top $historyTop must be below safe inset $safeTop", historyTop >= safeTop)
        }
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithTag("top_tabs").assertIsDisplayed()
    }

    @Test
    fun drawerOpensSettingsScreen() {
        compose.onNodeWithTag("menu_button").performClick()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
    }

    @Test
    fun menuIsBelowStatusBarAndCameraCutout() {
        compose.onNodeWithTag("menu_button").assertIsDisplayed()
        val menuTop =
            compose
                .onNodeWithTag("menu_button")
                .fetchSemanticsNode()
                .boundsInWindow.top
        compose.runOnIdle {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            val safeTop =
                insets
                    .getInsetsIgnoringVisibility(
                        WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
                    ).top
            assertTrue("Menu top $menuTop must be below safe inset $safeTop", menuTop >= safeTop)
        }
    }

    @Test
    fun completedRunOpensSavedTranscriptFromTranscriptionTab() {
        val store = AppGraph.runStore(compose.activity)
        val source = AudioSource("navigation-result", "file:///unused.wav", "Navigation result.wav", 1000L)
        val runId = store.create(source, RunRequest("auto"))
        try {
            store.complete(
                CompletedRun(
                    id = runId,
                    sourceId = source.id,
                    turns = listOf(Turn("speaker_0", 0, 1000, "Сохранённый текст результата")),
                    language = "ru",
                    modelIds = emptyList(),
                    modelHashes = emptyMap(),
                    stageMs = emptyMap(),
                    warnings = emptyList(),
                ),
            )
            compose.onNodeWithTag("tab_transcription").performClick()
            openRun(runId)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Сохранённый текст результата").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Сохранённый текст результата").assertIsDisplayed()
            compose.onNodeWithTag("result_save").assertIsDisplayed()
        } finally {
            store.deleteRun(runId)
        }
    }

    @Test
    fun emptyCompletedRunExplainsWhyNoTextIsVisible() {
        val store = AppGraph.runStore(compose.activity)
        val source = AudioSource("navigation-empty", "file:///unused.wav", "Empty result.wav", 1000L)
        val runId = store.create(source, RunRequest("auto"))
        try {
            store.complete(
                CompletedRun(runId, source.id, emptyList(), null, emptyList(), emptyMap(), emptyMap(), emptyList()),
            )
            compose.onNodeWithTag("tab_transcription").performClick()
            openRun(runId)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Распознанного текста нет").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("result_empty").assertIsDisplayed()
            compose.onNodeWithTag("result_save").assertIsDisplayed()
        } finally {
            store.deleteRun(runId)
        }
    }

    @Test
    fun modelSelectorsDismissOnSelectionAndBack() {
        compose.onNodeWithTag("tab_transcription").performClick()
        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag("model_option_auto").assertIsDisplayed().performClick()
        compose.onNodeWithTag("model_option_auto").assertDoesNotExist()
        compose.onNodeWithTag("model_selector").assertTextContains("Автоматически по языку")
        compose.onNodeWithTag("diarization_selector").performClick()
        Espresso.pressBack()
        compose.onNodeWithTag("diarization_selector").assertIsDisplayed()
        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag("model_option_auto").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("model_option_auto").assertDoesNotExist()
    }

    @Test
    fun preparedModelSelectionSurvivesTabSwitch() {
        val id = ModelCatalog.WHISPER_TINY
        assumeTrue("Prepare Whisper tiny for the model selection probe", AppGraph.modelStore(compose.activity).isReady(id))
        lateinit var vm: AppViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(5000) { id in vm.ui.value.readyBundles }
        compose.onNodeWithTag("tab_transcription").performClick()
        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag("model_option_$id").performClick()
        compose.onNodeWithTag("model_selector").assertTextContains(ModelCatalog.byId(id).displayName)
        compose.onNodeWithTag("tab_recorder").performClick()
        compose.onNodeWithTag("tab_transcription").performClick()
        compose.onNodeWithTag("model_selector").assertTextContains(ModelCatalog.byId(id).displayName)
    }

    @Test
    fun tabsSwitchContent() {
        compose.onNodeWithTag("tab_transcription").performClick()
        compose.onNodeWithTag("transcription_screen").assertIsDisplayed()
        compose.onNodeWithTag("tab_recorder").performClick()
        compose.onNodeWithTag("recorder_screen").assertIsDisplayed()
    }
    @Test
    fun editedTurnSurvivesReloadAndIsUsedByExport() {
        val store = AppGraph.runStore(compose.activity)
        lateinit var vm: AppViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val source = AudioSource("edit-result", "file:///unused.wav", "Editable result.wav", 1000L)
        val id = store.create(source, RunRequest("auto"))
        try {
            store.complete(CompletedRun(id, source.id, listOf(Turn("S0", 0, 1000, "Исходный текст")), "ru", emptyList(), emptyMap(), emptyMap(), emptyList()))
            compose.onNodeWithTag("tab_transcription").performClick()
            openRun(id)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Исходный текст").fetchSemanticsNodes().isNotEmpty() }
            val cached = runBlocking { vm.loadResult(id) }
            assertSame(cached, runBlocking { vm.loadResult(id) })
            compose.onNodeWithTag("turn_edit_0").performScrollTo().performClick()
            compose.onNodeWithTag("turn_edit_text").performTextReplacement("Исправленный текст")
            compose.onNodeWithTag("turn_edit_end").performTextReplacement("2000")
            compose.onNodeWithTag("turn_edit_save").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Время должно быть в пределах записи, конец — позже начала").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("turn_edit_text").assertTextContains("Исправленный текст")
            compose.onNodeWithTag("turn_edit_end").performTextReplacement("1000")
            compose.onNodeWithTag("turn_edit_save").performClick()
            compose.waitUntil(5000) { store.readCompleted(id).turns.single().text == "Исправленный текст" }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("turn_edit_save").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Назад").performClick()
            openRun(id)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Исправленный текст").fetchSemanticsNodes().isNotEmpty() }
            val reloaded = runBlocking { vm.loadResult(id) }!!.completed!!
            assertEquals("Исправленный текст", reloaded.turns.single().text)
            val exported = JsonExport.encodeFor(reloaded.turns)
            assertTrue(exported.contains("Исправленный текст"))
            assertTrue(!exported.contains("Исходный текст"))
            store.deleteRun(id)
            assertEquals(null, runBlocking { vm.loadResult(id) })
        } finally { store.deleteRun(id) }
    }

}
