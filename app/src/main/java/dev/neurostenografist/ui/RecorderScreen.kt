package dev.neurostenografist.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Recorder tab: record a mono WAV into Downloads via RecordingService. Microphone,
 * notification and (on API 26–28) legacy-storage permissions are requested with
 * distinct reasons; denying notifications must not imply microphone denial.
 */
@Composable
fun RecorderScreen(
    vm: AppViewModel,
    state: AppViewModel.UiState,
    settingsEpoch: Int,
    onRecordStop: () -> Unit,
) {
    val context = LocalContext.current

    // Subscribe to immediate settings changes (sample rate text).
    @Suppress("UNUSED_VARIABLE")
    val rev = settingsEpoch
    var micDenied by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(false) }
    var storageDenied by remember { mutableStateOf(false) }
    var startWhenGranted by remember { mutableStateOf(false) }

    fun missingPermissions(): List<String> {
        val activity = context as Activity
        val need =
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        return need.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
    }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val mic = grants[Manifest.permission.RECORD_AUDIO]
            if (mic == false) {
                micDenied = true
                startWhenGranted = false
            } else {
                micDenied = false
                notificationDenied = grants[Manifest.permission.POST_NOTIFICATIONS] == false
                storageDenied = grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false
                if (startWhenGranted) {
                    startWhenGranted = false
                    vm.recordStart()
                }
            }
        }

    fun requestOrStart() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            vm.recordStart()
        } else {
            startWhenGranted = true
            permission.launch(missing.toTypedArray())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("recorder_screen")) {
        Text(
            text =
                if (state.recording) {
                    "Запись… ${formatElapsed(displayedElapsedMs(state))}. WAV сохранится в «Загрузки/neurostenografist»."
                } else {
                    "Запись моно WAV (${vm.settings.recordSampleRate / 1000} кГц) в папку «Загрузки/neurostenografist»."
                },
            modifier = Modifier.testTag("recorder_status"),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !state.recording && state.activeRunId == null,
                onClick = {
                    micDenied = false
                    notificationDenied = false
                    storageDenied = false
                    requestOrStart()
                },
                modifier = Modifier.testTag("record_start"),
            ) { Text("Записать") }
            OutlinedButton(
                enabled = state.recording,
                onClick = {
                    vm.recordStop()
                    onRecordStop()
                },
                modifier = Modifier.testTag("record_stop"),
            ) { Text("Остановить и сохранить") }
        }

        if (state.recording) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (micDenied) {
            Text(
                "Для записи нужен доступ к микрофону. Разрешите его в системных настройках приложения.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (notificationDenied) {
            Text(
                "Уведомления отключены. Запись работает, но уведомление о ней может не отображаться.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (storageDenied && Build.VERSION.SDK_INT <= 28) {
            Text(
                "На этой версии Android для сохранения WAV в «Загрузки» нужен доступ к хранилищу.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Displayed recording elapsed time: UiState.recordingElapsedMs is already the
 *  polled true duration (not a start timestamp), so it is shown as-is; subtracting
 *  it from the wall clock here would reintroduce the epoch-since-1970 bug.
 */
internal fun displayedElapsedMs(state: AppViewModel.UiState): Long = if (state.recording) state.recordingElapsedMs.coerceAtLeast(0) else 0

fun formatElapsed(ms: Long): String =
    String.format(Locale.US, "%d:%02d", (ms / 60000).coerceAtLeast(0), ((ms % 60000) / 1000).coerceAtLeast(0))
