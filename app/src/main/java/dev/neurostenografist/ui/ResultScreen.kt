@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.neurostenografist.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.core.Turn
import dev.neurostenografist.data.RunRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultScreen(vm: AppViewModel, state: AppViewModel.UiState, runId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by vm.runStore.revision.collectAsState()
    var record by remember(runId) { mutableStateOf<RunRecord?>(null) }
    var loading by remember(runId) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editTarget by remember(runId) { mutableStateOf<Pair<Int, Turn>?>(null) }
    var editError by remember(runId) { mutableStateOf<String?>(null) }
    var detailsExpanded by remember(runId) { mutableStateOf(false) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    val player = remember(runId) {
        lazy(LazyThreadSafetyMode.NONE) {
            SegmentPlayer { active, failure ->
                if (!active) playingIndex = null
                if (failure != null) error = failure
            }
        }
    }
    DisposableEffect(player) { onDispose { if (player.isInitialized()) player.value.close() } }
    LaunchedEffect(runId, revision) {
        record = runId?.let { vm.loadResult(it) }
        loading = false
    }
    val completed = record?.completed
    val busy = state.recording || state.activeRunId != null || saving
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JsonExport.MIME_JSON)) { uri ->
        val snapshot = completed
        if (uri != null && snapshot != null) scope.launch {
            saving = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open output stream")
                    out.use { JsonExport.writeTo(it, JsonExport.encodeFor(snapshot.turns)) }
                }
            } catch (failure: Exception) { error = "Не удалось сохранить: ${userMessage(failure.message)}" }
            finally { saving = false }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Расшифровка") }, navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                loading -> CircularProgressIndicator(Modifier.testTag("result_loading"))
                record == null -> Column(Modifier.testTag("result_not_found")) {
                    Text("Нет такого результата", style = MaterialTheme.typography.titleMedium)
                    Text("Эта расшифровка не найдена — возможно, она была удалена.")
                }
                completed == null -> Column(Modifier.testTag("result_not_completed")) {
                    Text(when (record?.status) {
                        RunStatus.CANCELLED -> "Расшифровка отменена"
                        RunStatus.INTERRUPTED -> "Расшифровка прервана"
                        RunStatus.FAILED -> "Расшифровка не удалась"
                        else -> "Результата пока нет"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(record?.message?.let(::userMessage) ?: "Дождитесь завершения обработки.")
                }
                else -> {
                    Text("Язык: ${when (completed.language) { "ru" -> "русский"; "en" -> "английский"; else -> "не определён" }}", Modifier.testTag("result_meta"))
                    (error ?: state.message)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export_error")) }
                    if (state.activeRunId == runId) Text("Повторное распознавание фрагмента…")
                    LazyColumn(Modifier.weight(1f).testTag("result_turns")) {
                        item {
                            val (qualityWarnings, backendReports) = completed.warnings.partition {
                                it.contains("overlap", true) || it.contains("Одновременная речь", true) ||
                                    it.contains("clustering", true) || it.contains("Диаризация Nemotron", true) ||
                                    it.contains("empty ASR", true) || it.contains("недостаточно чистой речи", true)
                            }
                            qualityWarnings.forEach { Text(warningLabel(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.testTag("processing_details_toggle")) {
                                Text(if (detailsExpanded) "Скрыть подробности" else "Подробности обработки")
                            }
                            if (detailsExpanded) {
                                Text("Модели: ${completed.modelIds.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                                completed.stageMs.forEach { (stage, ms) -> Text("${stageLabel(stage)}: ${ms / 1000.0} с", style = MaterialTheme.typography.bodySmall) }
                                backendReports.forEach { Text(warningLabel(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (completed.turns.isEmpty()) item {
                            Column(Modifier.testTag("result_empty")) {
                                Text("Распознанного текста нет", style = MaterialTheme.typography.titleMedium)
                                Text(if (completed.language == null) "Речь не обнаружена. Проверьте исходное аудио." else "Проверьте исходное аудио и предупреждения выше.")
                            }
                        }
                        itemsIndexed(completed.turns) { index, turn ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.padding(top = 4.dp).size(12.dp).background(speakerColor(turn.speakerId), CircleShape))
                                    Text("${turn.speakerId}  ${clock(turn.startMs)}–${clock(turn.endMs)}", style = MaterialTheme.typography.labelLarge)
                                }
                                Text(turn.text.ifBlank { "—" })
                                Row {
                                    TextButton(onClick = {
                                        if (playingIndex == index) { player.value.stop(); playingIndex = null }
                                        else { playingIndex = index; player.value.play(context, record!!.source.uri, turn) }
                                    }, enabled = !state.recording, modifier = Modifier.testTag("turn_play_$index")) { Text(if (playingIndex == index) "Остановить" else "Слушать") }
                                    TextButton(onClick = { editTarget = index to turn; editError = null }, enabled = !busy, modifier = Modifier.testTag("turn_edit_$index")) { Text("Изменить") }
                                }
                                TextButton(onClick = { if (player.isInitialized()) player.value.stop(); vm.retryTurn(completed.id, index) }, enabled = !busy && state.installing == null, modifier = Modifier.testTag("turn_retry_$index")) { Text("Распознать фрагмент заново") }
                                HorizontalDivider()
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveLauncher.launch(exportFileName(record)) }, enabled = !saving, modifier = Modifier.testTag("result_save")) { Text("Сохранить JSON") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                saving = true
                                error = null
                                try {
                                    val uri = withContext(Dispatchers.IO) { JsonExport.writeShareFile(context, completed.turns) }
                                    val send = Intent(Intent.ACTION_SEND).setType(JsonExport.MIME_JSON).putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("transcript", uri) }
                                    context.startActivity(Intent.createChooser(send, "Поделиться расшифровкой"))
                                } catch (failure: Exception) { error = "Не удалось поделиться: ${userMessage(failure.message)}" }
                                finally { saving = false }
                            }
                        }, enabled = !saving, modifier = Modifier.testTag("result_share")) { Text("Поделиться") }
                    }
                    TextButton(onClick = { vm.deleteRun(completed.id); onBack() }, enabled = !busy, modifier = Modifier.testTag("result_delete")) { Text("Удалить результат") }
                }
            }
        }
    }
    val target = editTarget
    if (target != null && completed != null) {
        EditTurnDialog(target.second, onDismiss = { editTarget = null }, onSave = { replacement ->
            scope.launch {
                saving = true
                editError = null
                try { vm.editTurn(completed.id, target.first, target.second, replacement); editTarget = null }
                catch (failure: Exception) { editError = userMessage(failure.message) }
                finally { saving = false }
            }
        }, saving = saving, error = editError)
    }
}

@Composable
private fun EditTurnDialog(turn: Turn, onDismiss: () -> Unit, onSave: (Turn) -> Unit, saving: Boolean, error: String?) {
    var text by remember(turn) { mutableStateOf(turn.text) }
    var speaker by remember(turn) { mutableStateOf(turn.speakerId) }
    var start by remember(turn) { mutableStateOf(turn.startMs.toString()) }
    var end by remember(turn) { mutableStateOf(turn.endMs.toString()) }
    val startMs = start.toLongOrNull()
    val endMs = end.toLongOrNull()
    val valid = speaker.isNotBlank() && startMs != null && endMs != null && startMs >= 0 && endMs > startMs
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Изменить реплику") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(speaker, { speaker = it }, label = { Text("Участник") }, singleLine = true, enabled = !saving)
            OutlinedTextField(start, { start = it }, label = { Text("Начало, мс") }, singleLine = true, enabled = !saving)
            OutlinedTextField(end, { end = it }, label = { Text("Конец, мс") }, singleLine = true, enabled = !saving, modifier = Modifier.testTag("turn_edit_end"))
            OutlinedTextField(text, { text = it }, label = { Text("Текст") }, modifier = Modifier.testTag("turn_edit_text"), minLines = 2, maxLines = 6, enabled = !saving)
            if (!valid) Text("Укажите участника и корректные границы времени", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(Turn(speaker.trim(), startMs!!, endMs!!, text)) }, enabled = valid && !saving, modifier = Modifier.testTag("turn_edit_save")) { Text("Сохранить") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") } })
}

private fun exportFileName(record: RunRecord?): String {
    val base = record?.source?.displayName?.substringBeforeLast('.')?.replace(Regex("[^A-Za-zА-Яа-яЁё0-9_-]+"), "_")?.take(64)?.ifBlank { "transcript" } ?: "transcript"
    return "$base-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
}

private fun clock(ms: Long): String = "%02d:%02d.%03d".format(Locale.US, ms / 60_000, ms / 1000 % 60, ms % 1000)
