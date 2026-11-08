package dev.neurostenografist

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AppSmokeTest {
    @Test fun launchesOriginalApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<android.app.Activity>(Intent().setClassName(context, "dev.neurostenografist.MainActivity")).use {
            it.onActivity { activity -> check(activity.window.decorView.isShown) }
        }
    }
}
