package dev.neurostenografist.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette contract itself: speaker colors are deterministic, and every entry
 * keeps ≥3:1 contrast (WCAG-style, via the production [contrastRatio]) against
 * both Material3 default surfaces — the surfaced text sits on these backgrounds.
 */
class ThemeTest {
    private val lightSurface = Color(0xFFFBFE)
    private val darkSurface = Color(0xFF1C1B1F)

    @Test
    fun `speaker color is deterministic per speaker id`() {
        assertEquals(speakerColor("S0"), speakerColor("S0"))
        assertEquals(speakerColor("speaker_1"), speakerColor("speaker_1"))
    }

    @Test
    fun `palette wraps cyclically for more speakers than entries`() {
        val drawn = (0 until 12).map { speakerColor("S$it") }
        assertTrue("palette must be non-trivial: ${drawn.distinct().size}", drawn.distinct().size >= 2)
        assertEquals(drawn[0], drawn[SPEAKER_COLORS.size])
        assertEquals(drawn[1], drawn[SPEAKER_COLORS.size + 1])
    }

    @Test
    fun `every palette entry keeps at least 3 ratio contrast on both surfaces`() {
        SPEAKER_COLORS.forEachIndexed { index, argb ->
            val color = Color(argb.toInt())
            val onLight = contrastRatio(color, lightSurface)
            val onDark = contrastRatio(color, darkSurface)
            assertTrue(
                "palette[$index] contrast fails: light=%.2f dark=%.2f".format(onLight, onDark),
                onLight >= 3.0 && onDark >= 3.0,
            )
        }
    }
}
