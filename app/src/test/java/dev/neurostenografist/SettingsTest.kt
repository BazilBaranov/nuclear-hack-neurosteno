package dev.neurostenografist

import dev.neurostenografist.data.PrefsBacking
import dev.neurostenografist.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * JVM tests of settings persistence and the explicit system/custom palette choice.
 * Uses the in-memory [PrefsBacking] backing constructor so the shared-preferences
 * behavior (same schema, same keys) is verified without an Android device.
 */
class SettingsTest {
    private lateinit var backing: InMemoryPrefs

    @Before
    fun setUp() {
        backing = InMemoryPrefs()
    }

    private fun newStore() = SettingsStore(backing)

    @Test
    fun defaultsMatchBackendExpectations() {
        val settings = newStore()
        assertEquals(16_000, settings.recordSampleRate)
        assertEquals(SettingsStore.MODE_SYSTEM, settings.paletteMode)
        assertEquals(SettingsStore.DEFAULT_PRIMARY, settings.primaryColor)
        assertEquals(SettingsStore.DEFAULT_SECONDARY, settings.secondaryColor)
        assertTrue(settings.isDefaultPalette())
    }

    @Test
    fun recordedChoicesPersistAcrossStoreRecreation() {
        newStore().apply {
            recordSampleRate = 48_000
            paletteMode = SettingsStore.MODE_CUSTOM
            primaryColor = 0xFFAA1234.toInt()
            secondaryColor = 0xFF5678EF.toInt()
        }
        val reopened = newStore()
        assertEquals(48_000, reopened.recordSampleRate)
        assertEquals(SettingsStore.MODE_CUSTOM, reopened.paletteMode)
        assertEquals(0xFFAA1234.toInt(), reopened.primaryColor)
        assertEquals(0xFF5678EF.toInt(), reopened.secondaryColor)
        assertFalse(reopened.isDefaultPalette())
    }

    @Test
    fun rateValidationAcceptsRecordRatesOnly() {
        val settings = newStore()
        assertEquals(SettingsStore.RECORD_RATES, setOf(16_000, 48_000))
        settings.recordSampleRate = 48_000
        assertEquals(48_000, settings.recordSampleRate)
        for (bad in listOf(44_100, 8_000, 0, -1)) {
            try {
                settings.recordSampleRate = bad
                fail("accepted unsupported rate $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(48_000, settings.recordSampleRate) // rejected values change nothing
    }

    @Test
    fun paletteModeValidationAcceptsSystemAndCustomOnly() {
        val settings = newStore()
        settings.paletteMode = SettingsStore.MODE_CUSTOM
        assertEquals(SettingsStore.MODE_CUSTOM, settings.paletteMode)
        settings.paletteMode = SettingsStore.MODE_SYSTEM
        assertEquals(SettingsStore.MODE_SYSTEM, settings.paletteMode)
        try {
            settings.paletteMode = "retro"
            fail("accepted unknown palette mode")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals(SettingsStore.MODE_SYSTEM, settings.paletteMode)
    }

    @Test
    fun customSelectionSwitchesBackToSystemAndRecomposes() {
        val settings = newStore()
        settings.paletteMode = SettingsStore.MODE_CUSTOM
        val customPrimary = settings.primaryColor
        settings.primaryColor = 0xFF123456.toInt()
        assertEquals(0xFF123456.toInt(), settings.primaryColor)
        // explicit return to the system palette keeps the stored custom colors
        settings.paletteMode = SettingsStore.MODE_SYSTEM
        assertEquals(SettingsStore.MODE_SYSTEM, settings.paletteMode)
        assertEquals(0xFF123456.toInt(), settings.primaryColor)
        assertEquals(customPrimary, SettingsStore.DEFAULT_PRIMARY) // original default untouched on the fresh store
        assertFalse(settings.isDefaultPalette()) // colors persisted, mode explicit not derived
        settings.primaryColor = SettingsStore.DEFAULT_PRIMARY
        settings.secondaryColor = SettingsStore.DEFAULT_SECONDARY
        assertTrue(settings.isDefaultPalette()) // legacy helper still reports defaults
    }

    /** Same key schema and read-after-write semantics as SharedPreferences. */
    class InMemoryPrefs : PrefsBacking {
        val ints = mutableMapOf<String, Int>()
        val strings = mutableMapOf<String, String>()

        override fun getInt(
            key: String,
            def: Int,
        ): Int = ints.getOrDefault(key, def)

        override fun putInt(
            key: String,
            value: Int,
        ) {
            ints[key] = value
        }

        override fun getString(
            key: String,
            def: String,
        ): String = strings.getOrDefault(key, def)

        override fun putString(
            key: String,
            value: String,
        ) {
            strings[key] = value
        }
    }
}
