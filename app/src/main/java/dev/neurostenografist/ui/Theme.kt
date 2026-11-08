package dev.neurostenografist.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.neurostenografist.data.SettingsStore
import kotlin.math.max

/**
 * Theming: system light/dark is honored everywhere. On API 31+ the system mode uses
 * Material You dynamic colors; on API 26–30 the system mode falls back to the stable
 * baseline Material3 palette. Custom mode overrides primary/secondary with the two
 * arbitrary user colors in both light and dark variants, deriving contrasting
 * onPrimary/onSecondary from the actual custom colors (defaults would leave white
 * text on a white primary).
 */
@Composable
fun appTheme(
    settings: SettingsStore,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val epoch = rememberSettingsEpoch(settings)

    val scheme = remember(settings, epoch, dark, context) {
        when (settings.paletteMode) {
            SettingsStore.MODE_CUSTOM -> {
                customColorScheme(Color(settings.primaryColor), Color(settings.secondaryColor), dark)
            }

            else -> {
                // MODE_SYSTEM
                if (Build.VERSION.SDK_INT >= 31) {
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else if (dark) {
                    darkColorScheme()
                } else {
                    lightColorScheme()
                }
            }
        }
    }

    androidx.compose.material3.MaterialTheme(colorScheme = scheme, content = content)
}

/** Custom palette: primary/secondary are the arbitrary user colors; foregrounds are
 *  derived (white or black, whichever contrasts more) instead of the scheme defaults,
 *  whose onPrimary/onSecondary can be unreadable against a custom background.
 */
fun customColorScheme(
    primary: Color,
    secondary: Color,
    dark: Boolean,
): ColorScheme {
    fun on(background: Color): Color =
        if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

    return if (dark) {
        darkColorScheme(
            primary = primary,
            secondary = secondary,
            onPrimary = on(primary),
            onSecondary = on(secondary),
        )
    } else {
        lightColorScheme(
            primary = primary,
            secondary = secondary,
            onPrimary = on(primary),
            onSecondary = on(secondary),
        )
    }
}

/** Stable per-speaker accent: same speaker -> same color for the whole app run.
 *  Every entry keeps ≥3:1 contrast (see [contrastRatio]) against both the light
 *  (0xFFFBFE) and dark (0xFF1C1B1F) Material3 surfaces — enforced by ThemeTest. */
// internal: unit-test contract (ThemeTest asserts every entry's surface contrast).
internal val SPEAKER_COLORS =
    listOf(
        0xFF4F5BD5,
        0xFF00897B,
        0xFFEF6C00,
        0xFFAB47BC,
        0xFF039BE5,
        0xFF689F38,
        0xFFD81B60,
        0xFF8D6E63,
    )

fun speakerColor(speakerId: String): Color {
    var hash = 0
    for (ch in speakerId) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
    return Color(SPEAKER_COLORS[hash % SPEAKER_COLORS.size].toInt())
}

/** WCAG-style contrast ratio between the two colors (relative luminance based). */
fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    fun luminanceChannel(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    fun luminance(c: Color): Double =
        0.2126 * luminanceChannel(c.red) + 0.7152 * luminanceChannel(c.green) + 0.0722 * luminanceChannel(c.blue)

    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * Bumps on every settings write so compositions that read it re-evaluate the
 * snapshot of [SettingsStore] values immediately (the store is not snapshot state).
 */
@Composable
fun rememberSettingsEpoch(settings: SettingsStore): Int {
    var epoch by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val off = settings.addChangeListener { epoch++ }
        onDispose { off() }
    }
    return epoch
}
