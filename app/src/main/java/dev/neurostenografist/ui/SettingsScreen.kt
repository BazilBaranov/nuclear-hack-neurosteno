@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.neurostenografist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.neurostenografist.data.SettingsStore

/**
 * Settings: recording sample rate (16/48 kHz, validated; affects future
 * recordings/jobs only) and the explicit system-vs-custom palette choice with an
 * arbitrary two-color hex input. Writes go through [SettingsStore], whose change
 * listeners make every screen and the theme recompose immediately; values persist
 * across restarts.
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    state: AppViewModel.UiState,
    settingsEpoch: Int,
    onBack: () -> Unit,
) {
    // Reading the epoch subscribes this screen to immediate settings changes.
    val rev = settingsEpoch
    val settings = vm.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .testTag("settings_screen")
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Частота записи (для будущих записей)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("settings_rate_title"),
            )
            recordRateRow(settings, state)
            Text(
                "Влияет только на новые записи и запуски; текущие и завершённые задачи не меняются. Если микрофон не поддерживает выбранную частоту, приложение сообщит об ошибке оборудования.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings_rate_note"),
            )

            Text("Палитра", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.paletteMode == SettingsStore.MODE_SYSTEM,
                    onClick = { settings.paletteMode = SettingsStore.MODE_SYSTEM },
                    modifier = Modifier.testTag("settings_palette_system"),
                )
                Text("Системная", modifier = Modifier.padding(start = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.paletteMode == SettingsStore.MODE_CUSTOM,
                    onClick = { settings.paletteMode = SettingsStore.MODE_CUSTOM },
                    modifier = Modifier.testTag("settings_palette_custom"),
                )
                Text("Своя палитра", modifier = Modifier.padding(start = 6.dp))
            }

            if (settings.paletteMode == SettingsStore.MODE_CUSTOM) {
                ColorEditor(
                    "Основной цвет (primary)",
                    settings.primaryColor,
                    onCommit = { settings.primaryColor = it },
                    testTag = "settings_primary",
                )
                ColorEditor(
                    "Дополнительный цвет (secondary)",
                    settings.secondaryColor,
                    onCommit = { settings.secondaryColor = it },
                    testTag = "settings_secondary",
                )
            }
        }
    }
}

@Composable
private fun recordRateRow(
    settings: SettingsStore,
    state: AppViewModel.UiState,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsStore.RECORD_RATES.sorted().forEach { rate ->
            val label = "${rate / 1000} кГц"
            OutlinedButton(
                enabled = !state.recording,
                onClick = { settings.recordSampleRate = rate },
                modifier = Modifier.testTag("settings_rate_${rate / 1000}khz"),
            ) { Text(if (settings.recordSampleRate == rate) "• $label" else label) }
        }
    }
}

@Composable
private fun ColorEditor(
    title: String,
    current: Int,
    onCommit: (Int) -> Unit,
    testTag: String,
) {
    var text by rememberSaveable(current) { mutableStateOf(hexOf(current)) }
    var invalid by remember { mutableStateOf(false) }

    fun parse(input: String): Int? = parseHexColor(input)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(testTag)) {
        Text(title)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A real color preview plus a spoken label, so a color is never
            // communicated by color alone.
            val preview = Color(current)
            Box(
                Modifier
                    .size(24.dp)
                    .background(preview, RoundedCornerShape(4.dp)),
            )
            Text(hexOf(current), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    val parsed = parse(it.trim())
                    invalid = parsed == null
                },
                singleLine = true,
                label = { Text("HEX, например #4F5BD5") },
                isError = invalid,
                supportingText = { if (invalid) Text("Введите цвет в формате #RRGGBB или #AARRGGBB") },
                modifier = Modifier.testTag("${testTag}_input"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !invalid && parse(text.trim()) != null,
                onClick = {
                    parse(text.trim())?.let { color ->
                        onCommit(color)
                        text = hexOf(color)
                    }
                },
                modifier = Modifier.testTag("${testTag}_apply"),
            ) { Text("Применить") }
            TextButton(onClick = {
                text = "#4F5BD5"
                invalid = false
                parse(text)?.let(onCommit)
            }) { Text("Пример") }
        }
    }
}

private fun hexOf(color: Int): String = String.format(java.util.Locale.US, "#%08X", color)

/** Accepts #RRGGBB (opaque) and #AARRGGBB; returns null for anything else. */
fun parseHexColor(input: String): Int? =
    runCatching {
        when {
            input.matches(Regex("#[0-9A-Fa-f]{8}")) -> {
                java.lang.Long
                    .decode(input)
                    .toInt()
            }

            input.matches(Regex("#[0-9A-Fa-f]{6}")) -> {
                (0xFF000000L or java.lang.Long.decode(input)).toInt()
            }

            else -> {
                null
            }
        }
    }.getOrNull()
