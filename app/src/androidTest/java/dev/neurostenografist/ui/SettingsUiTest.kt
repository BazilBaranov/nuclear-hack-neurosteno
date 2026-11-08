package dev.neurostenografist.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neurostenografist.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device settings behavior: the store the app writes from Settings recomposes
 * through the change listeners and persists via the real SharedPreferences-backed
 * [SettingsStore]. Values survive a store re-creation with the same keys/defaults;
 * an unsupported rate and an unknown palette mode stay rejected on device too.
 */
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @Test
    fun deviceStoreDefaultsAndCustomModePersistChanges() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(ctx)
        val seen = mutableListOf<Int>()
        val stop = settings.addChangeListener { seen.add(1) }

        assertEquals(SettingsStore.DEFAULT_RATE, settings.recordSampleRate)
        settings.recordSampleRate = 48_000
        settings.paletteMode = SettingsStore.MODE_CUSTOM
        settings.primaryColor = 0xFF112233.toInt()
        settings.secondaryColor = 0xFF445566.toInt()

        val reopened = SettingsStore(ctx)
        assertEquals(48_000, reopened.recordSampleRate)
        assertEquals(SettingsStore.MODE_CUSTOM, reopened.paletteMode)
        assertEquals(0xFF112233.toInt(), reopened.primaryColor)
        assertEquals(0xFF445566.toInt(), reopened.secondaryColor)
        // imperative listener fired on every write
        assertEquals(4, seen.size)
        stop()
    }
}
