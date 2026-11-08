@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.neurostenografist.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.neurostenografist.core.AUTO_MODEL_ID
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.core.SpeechLanguage
import dev.neurostenografist.data.RunRecord
import dev.neurostenografist.inference.BundleKind
import dev.neurostenografist.inference.ModelCatalog
import java.util.Locale

/**
 * Transcription tab: sources, model choice (Auto or exact manual ASR), the active
 * run with progress and cancel, and the latest completed transcript.
 * Processing progress lives here — there is no seventh screen for it.
 */
@Composable
fun TranscriptionScreen(
    vm: AppViewModel,
    state: AppViewModel.UiState,
    selectedSourceId: String?,
    onSelectedSource: (String) -> Unit,
    onOpenRun: (String) -> Unit,
) {
    var chosenModel by rememberSaveable { mutableStateOf(ModelCatalog.WHISPER_SMALL) }
    var chosenDiarizer by rememberSaveable { mutableStateOf(ModelCatalog.DIARIZATION) }
    var language by rememberSaveable { mutableStateOf(SpeechLanguage.RUSSIAN) }
    var punctuation by rememberSaveable { mutableStateOf(false) }
    var denoise by rememberSaveable { mutableStateOf(false) }
    var separateOverlappingSpeech by rememberSaveable { mutableStateOf(false) }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.importAudio(it) }
        }
    val conflict = state.recording || state.activeRunId != null || state.installing != null

    val latest = state.runs.firstOrNull { it.status == RunStatus.COMPLETED }
    val latestError = state.runs.firstOrNull()?.takeIf { it.status == RunStatus.FAILED || it.status == RunStatus.INTERRUPTED }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().testTag("transcription_screen"),
    ) {
        state.activeRunId?.let { ActiveRunPanel(state, it, vm) }
        latestError?.message?.let { Text(userMessage(it), color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).testTag("transcription_content"),
        ) {
            item("import") {
                OutlinedButton(
                    enabled = !state.recording,
                    onClick = { import.launch(arrayOf("audio/*")) },
                    modifier = Modifier.testTag("import_audio"),
                ) { Text("Выбрать аудио") }
            }
            item("language") {
                LanguageSelector(language) { selected ->
                    language = selected
                    val code = selected.code
                    if (code != null && chosenModel != AUTO_MODEL_ID && code !in ModelCatalog.byId(chosenModel).languages) {
                        chosenModel = AUTO_MODEL_ID
                    }
                }
            }
            item("asr") { ModelSelector(state, chosenModel, language = language.code) { chosenModel = it } }
            item("diarization") { ModelSelector(state, chosenDiarizer, BundleKind.DIARIZATION) { chosenDiarizer = it } }
            latest?.let { run ->
                item("latest") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Последняя расшифровка", style = MaterialTheme.typography.titleSmall)
                        RunRow(run, onOpenRun)
                    }
                }
            }
            item("optional_processing") {
                Column {
                    Text("Дополнительная обработка", style = MaterialTheme.typography.titleSmall)
                    ProcessingCheckbox("Пунктуация (Qwen 3.5)", punctuation) { punctuation = it }
                    ProcessingCheckbox("Подавление шума (GTCRN)", denoise) { denoise = it }
                    ProcessingCheckbox("Разделение одновременной речи (VoxLock)", separateOverlappingSpeech) {
                        separateOverlappingSpeech = it
                    }
                }
            }
            item("sources") { Text("Аудиозаписи", style = MaterialTheme.typography.titleSmall) }
            items(state.sources, key = { "source-${it.id}" }) { source ->
                val selected = source.id == selectedSourceId
                Card(
                    onClick = { onSelectedSource(source.id) },
                    colors =
                        if (selected) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            CardDefaults.cardColors()
                        },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("source_item"),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text =
                                    buildString {
                                        if (selected) append("выбрано · ")
                                        append(
                                            source.durationMs?.let { "длительность %.1f с".format(Locale.US, it / 1000.0) }
                                                ?: "длительность определится при обработке",
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            enabled = !conflict,
                            onClick = {
                                onSelectedSource(source.id)
                                vm.startRun(
                                    source.id,
                                    RunRequest(
                                        modelId = chosenModel,
                                        diarizationModelId = chosenDiarizer,
                                        language = language,
                                        punctuation = punctuation,
                                        denoise = denoise,
                                        separateOverlappingSpeech = separateOverlappingSpeech,
                                    ),
                                )
                            },
                            modifier = Modifier.testTag("transcribe_button"),
                        ) { Text("Расшифровать") }
                        TextButton(
                            enabled = !conflict,
                            onClick = { vm.deleteSource(source.id) },
                            modifier = Modifier.testTag("delete_source"),
                        ) { Text("Удалить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LanguageSelector(language: SpeechLanguage, onChosen: (SpeechLanguage) -> Unit) {
    Column {
        Text("Язык записи", style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("language_selector")) {
            SpeechLanguage.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == language,
                    onClick = { onChosen(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, SpeechLanguage.entries.size),
                    icon = {},
                ) {
                    Text(when (option) {
                        SpeechLanguage.AUTO -> "Авто"
                        SpeechLanguage.RUSSIAN -> "Русский"
                        SpeechLanguage.ENGLISH -> "Английский"
                    })
                }
            }
        }
    }
}

@Composable
private fun ModelSelector(
    state: AppViewModel.UiState,
    chosenModel: String,
    kind: BundleKind = BundleKind.ASR,
    language: String? = null,
    onChosen: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (kind == BundleKind.ASR) "Распознавание речи" else "Разделение спикеров"
    val selectedName = if (chosenModel == AUTO_MODEL_ID) "Автоматически по языку" else ModelCatalog.byId(chosenModel).displayName
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
                    .testTag(if (kind == BundleKind.ASR) "model_selector" else "diarization_selector")
                    .semantics { contentDescription = "$label: $selectedName" },
            ) {
                Text(
                    selectedName,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(max = 360.dp),
            ) {
                if (kind == BundleKind.ASR) {
                    DropdownMenuItem(
                        text = { Text("Автоматически по языку") },
                        modifier = Modifier.testTag("model_option_auto"),
                        onClick = {
                            onChosen(AUTO_MODEL_ID)
                            expanded = false
                        },
                    )
                }
                ModelCatalog.selectableBundles.filter { it.kind == kind && (language == null || language in it.languages) }.forEach { bundle ->
                    val ready = bundle.id in state.readyBundles
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = bundle.displayName + if (ready) "" else " — загрузите в разделе «Модели»",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        enabled = ready,
                        onClick = {
                            onChosen(bundle.id)
                            expanded = false
                        },
                        modifier = Modifier.testTag("model_option_${bundle.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveRunPanel(
    state: AppViewModel.UiState,
    activeRun: String,
    vm: AppViewModel,
) {
    val record = state.runs.firstOrNull { it.id == activeRun }
    Card(Modifier.fillMaxWidth().testTag("active_run_panel")) {
        Column(Modifier.padding(10.dp), Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.testTag("active_progress"))
                // Honest stage: real stage name when the backend reports one, otherwise the
                // factual run status — never a fabricated "50%".
                val stage = state.activeStage
                val label =
                    when {
                        state.activeRunId == null -> null
                        !stage.isNullOrBlank() -> stageLabel(stage)
                        else -> "Обработка: ${stageLabel(record?.status)}"
                    }
                Text(label ?: "", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { vm.cancelRun() }, modifier = Modifier.testTag("cancel_run")) {
                    Text("Отмена")
                }
            }
            val fraction = state.activeFraction
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            record?.message?.let { Text(userMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RunRow(
    run: RunRecord,
    onOpenRun: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(run.source.displayName, style = MaterialTheme.typography.titleSmall)
            Text(stageLabel(run.status), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("run_status"))
            run.message?.let { Text(userMessage(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (run.status == RunStatus.COMPLETED) {
                Button(
                    onClick = { onOpenRun(run.id) },
                    modifier = Modifier.testTag("open_result_${run.id}"),
                ) { Text("Открыть результат") }
            }
        }
    }
}
