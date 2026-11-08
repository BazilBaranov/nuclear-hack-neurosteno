package dev.neurostenografist.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for original finding #8: custom schemes overrode only
 * primary/secondary, leaving the default (white) onPrimary/onSecondary, so a white
 * custom primary rendered invisible white button text. The derived foreground must
 * contrast with the actual custom background on the actual ColorScheme.
 */
class CustomColorSchemeTest {
    private val white = Color(0xFFFFFFFF)
    private val black = Color(0xFF000000)

    @Test
    fun `white custom primary keeps dark foreground in light mode`() {
        val scheme = customColorScheme(primary = white, secondary = black, dark = false)
        assertTrue(
            "onPrimary contrast=%.2f".format(contrastRatio(scheme.onPrimary, scheme.primary)),
            contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5,
        )
    }

    @Test
    fun `black custom primary keeps light foreground in dark mode`() {
        val scheme = customColorScheme(primary = black, secondary = white, dark = true)
        assertTrue(
            "onPrimary contrast=%.2f".format(contrastRatio(scheme.onPrimary, scheme.primary)),
            contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5,
        )
    }

    @Test
    fun `derived onSecondary contrasts with the custom secondary on both modes`() {
        listOf(false, true).forEach { dark ->
            listOf(white, black).forEach { secondary ->
                val scheme = customColorScheme(primary = black, secondary = secondary, dark = dark)
                assertTrue(
                    "dark=$dark secondaryContrast=%.2f".format(contrastRatio(scheme.onSecondary, scheme.secondary)),
                    contrastRatio(scheme.onSecondary, scheme.secondary) >= 4.5,
                )
            }
        }
    }

    @Test
    fun `derived foregrounds contrast with unrealistic arbitrary custom colors`() {
        listOf(Color(0xFF808080), Color(0xFF7F7F7F)).forEach { gray ->
            listOf(false, true).forEach { dark ->
                val scheme = customColorScheme(primary = gray, secondary = gray, dark = dark)
                assertTrue(
                    "gray primary foreground contrast=%.2f".format(contrastRatio(scheme.onPrimary, scheme.primary)),
                    contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5,
                )
            }
        }
    }
}
