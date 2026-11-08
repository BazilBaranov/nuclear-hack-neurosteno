@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.neurostenografist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.neurostenografist.core.RunStatus
import dev.neurostenografist.data.RunRecord
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Left-navigation History destination. A source-only item opens a preselected
 * Transcription tab; a finished run opens its Result screen; retry starts a NEW
 * run (old results are never overwritten).
 */
@Composable
fun HistoryScreen(
    vm: AppViewModel,
    state: AppViewModel.UiState,
    onOpenRun: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onBack: () -> Unit,
) {
    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()) }
    val conflict = state.recording || state.activeRunId != null

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("История") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
    ) { insets ->
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(insets).consumeWindowInsets(insets)
                    .fillMaxSize().padding(12.dp).testTag("history_screen"),
        ) {
            state.message?.let { Text(userMessage(it), color = MaterialTheme.colorScheme.error) }
            if (state.runs.isEmpty() && state.sources.isEmpty()) {
                Text(
                    "История пока пуста: запишите или импортируйте аудио на вкладке «Запись».",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn {
                items(state.runs, key = { "run-${it.id}" }) { run ->
                    val printable = printableRun(run, dateFmt)
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("history_run_item")) {
                        Column(Modifier.padding(10.dp)) {
                            Text(printable.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                printable.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("history_run_status"),
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                when (printable.kind) {
                                    RunEntryKind.COMPLETED -> {
                                        Button(onClick = { onOpenRun(printable.id!!) }, modifier = Modifier.testTag("history_open_result")) {
                                            Text("Открыть результат")
                                        }
                                    }

                                    RunEntryKind.FAILED, RunEntryKind.INTERRUPTED -> {
                                        Text(
                                            printable.subtitle,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }

                                    else -> {}
                                }
                                TextButton(
                                    enabled = !conflict,
                                    onClick = { vm.startRun(run.source.id, run.request) },
                                    modifier = Modifier.testTag("history_retry"),
                                ) { Text("Повторить") }
                                TextButton(
                                    enabled = !conflict,
                                    onClick = { vm.deleteRun(run.id) },
                                    modifier = Modifier.testTag("history_delete_run"),
                                ) { Text("Удалить результат") }
                            }
                        }
                    }
                }
                items(state.sources, key = { "src-${it.id}" }) { source ->
                    Card(
                        onClick = { onOpenSource(source.id) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("history_source_item"),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(source.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Источник аудио — открыть во вкладке «Расшифровка»",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class RunEntryKind { COMPLETED, RUNNING, CANCELLED, FAILED, INTERRUPTED, UNKNOWN }

private data class PrintableRun(
    val kind: RunEntryKind,
    val title: String,
    val subtitle: String,
    val id: String?,
)

private fun printableRun(
    run: RunRecord,
    dateFmt: DateFormat,
): PrintableRun {
    val when_ = dateFmt.format(Date(run.createdAtMs))
    return if (run.status == RunStatus.COMPLETED) {
        PrintableRun(
            RunEntryKind.COMPLETED,
            "${run.source.displayName} · $when_",
            "Готово — открыть расшифровку",
            run.id,
        )
    } else {
        val kind =
            when (run.status) {
                RunStatus.CREATED, RunStatus.RUNNING -> RunEntryKind.RUNNING
                RunStatus.CANCELLED -> RunEntryKind.CANCELLED
                RunStatus.FAILED -> RunEntryKind.FAILED
                RunStatus.INTERRUPTED -> RunEntryKind.INTERRUPTED
                else -> RunEntryKind.UNKNOWN
            }
        val subtitle =
            when (kind) {
                RunEntryKind.RUNNING -> {
                    if (run.message != null) "Обработка: ${userMessage(run.message)}" else "Обработка…"
                }

                RunEntryKind.COMPLETED -> {
                    "Готово"
                }

                RunEntryKind.CANCELLED -> {
                    "Отменено"
                }

                RunEntryKind.FAILED -> {
                    "Ошибка: ${userMessage(run.message)}"
                }

                RunEntryKind.INTERRUPTED -> {
                    "Прервано: ${userMessage(run.message)}"
                }

                RunEntryKind.UNKNOWN -> {
                    userMessage(run.message ?: run.status)
                }
            }
        PrintableRun(kind, "${run.source.displayName} · $when_", subtitle, null)
    }
}
