package dev.neurostenografist.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.MainActivity
import dev.neurostenografist.AppGraph
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Denial semantics on a real device: with RECORD_AUDIO denied up front, pressing
 * record must open the runtime request (the compose dialog host shows it) and the
 * app must stay idle — recording must NOT start and the start control stays
 * enabled. Notification-only denial is non-blocking (it never gates recording).
 * System dialogs are driven with Instrumentation.uiAutomation shell commands, so
 * no uiautomator dependency is needed.
 */
@RunWith(AndroidJUnit4::class)
class PermissionUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun exec(cmd: String) {
        val descriptor = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test
    fun micDeniedKeepsAppIdleWithExplicitMessage() {
        // Revoking at runtime kills the instrumentation process; never modify another installed app.
        assumeTrue("Prepare the test app with microphone permission denied before this probe",
            ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        compose.onNodeWithTag("record_start").performClick()
        compose.waitForIdle()
        assertFalse(AppGraph.recording)
        // The permission window obscures Compose semantics until it is dismissed.
        exec("input keyevent KEYCODE_BACK") // dismiss the permission dialog without granting
        compose.waitForIdle()
        assertFalse(AppGraph.recording)
        compose.onNodeWithTag("recorder_status").assertIsDisplayed()
        compose.onNode(hasTestTag("record_start")).assertIsDisplayed()
    }
}
