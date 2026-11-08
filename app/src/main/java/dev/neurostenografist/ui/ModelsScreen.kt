@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.neurostenografist.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.neurostenografist.inference.BundleKind
import dev.neurostenografist.inference.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Models screen: real catalog sizes and the genuine preparation state of each
 * bundle (ready / incomplete / error / downloading), with individual HTTPS downloads
 * and SHA-256 verification before atomic publication.
 */
@Composable
fun ModelsScreen(
    vm: AppViewModel,
    state: AppViewModel.UiState,
    onBack: () -> Unit,
) {
    val modelStore = vm.modelStore
    val bundles = ModelCatalog.selectableBundles.filter {
        it.kind != BundleKind.ACCELERATION || Build.VERSION.SDK_INT >= 34
    }

    // Disk state per bundle on the IO dispatcher: ready / partial leftovers / absent.
    // Kept as mutableStateOf + LaunchedEffect instead of produceState: the compose
    // runtime lint (1.7.5) under this toolchain cannot resolve `value =` setter
    // references inside produceState lambdas and false-flags even the canonical
    // documented shape; this pattern is semantically identical, and the lint check
    // stays active for future produceState callers.
    val diskStates = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(state.readyBundles) {
        diskStates.value =
            withContext(Dispatchers.IO) {
                bundles.associate { bundle ->
                    bundle.id to
                        when {
                            modelStore.isReady(bundle.id) -> "ready"
                            modelStore.bundleDir(bundle.id).exists() -> "incomplete"
                            else -> "absent"
                        }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Модели") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .testTag("models_screen")
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Скачайте нужные модели по Wi-Fi. После установки обработка работает без интернета. Whisper tiny нужен для автоматического определения языка. Qwen 3.5, GTCRN и VoxLock нужны только для включённых функций на вкладке «Расшифровка».",
                style = MaterialTheme.typography.bodySmall,
            )
            state.message?.let { Text(userMessage(it), color = MaterialTheme.colorScheme.error) }
            state.installing?.let { id ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("models_preparing"),
                ) {
                    CircularProgressIndicator(Modifier.testTag("models_preparing_progress"))
                    Text("Подготовка ${bundleName(id)}…")
                }
            }
            bundles.forEach { bundle ->
                val ready = bundle.id in state.readyBundles
                val preparing = state.installing == bundle.id
                val incomplete = diskStates.value[bundle.id] == "incomplete"
                val failedInstall = state.message?.startsWith("Не удалось загрузить") == true
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("model_item_${bundle.id}")) {
                    Text(bundle.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${bundle.id} · размер ${(bundle.totalBytes + 999_999) / 1_000_000} МБ · языки: ${bundle.languages.map(::languageLabel).joinToString(
                            ", ",
                        )}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (bundle.kind == BundleKind.ACCELERATION) {
                        Text("Дополнительные веса для Android 14+. Установите также основную модель для CPU-фолбека.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        preparing -> {
                            val fraction = state.downloadFraction
                            if (fraction == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                "Подготовка и проверка целостности${fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        ready -> {
                            Text(
                                "Готова к работе",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("model_state_ready_${bundle.id}"),
                            )
                            OutlinedButton(
                                enabled = state.activeRunId == null && state.installing == null && !state.recording,
                                onClick = { vm.deleteBundle(bundle.id) },
                                modifier = Modifier.testTag("model_delete_${bundle.id}"),
                            ) { Text("Удалить") }
                        }

                        else -> {
                            Text(
                                when {
                                    failedInstall -> "Ошибка последней подготовки: повторите попытку"
                                    incomplete -> "Обнаружены неполные данные — подготовьте заново"
                                    state.installing != null -> "Ожидание очереди подготовки"
                                    else -> "Не подготовлена"
                                },
                                color =
                                    if (failedInstall || incomplete) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                modifier = Modifier.testTag("model_state_notready_${bundle.id}"),
                            )
                            Button(
                                enabled = bundle.downloadable && state.installing == null && state.activeRunId == null && !state.recording,
                                onClick = { vm.installBundle(bundle.id) },
                                modifier = Modifier.testTag("model_install_${bundle.id}"),
                            ) { Text(if (bundle.downloadable) "Скачать" else "Только ранее установленная копия") }
                        }
                    }
                }
            }
            Text(
                "Диаризация говоривших не откалибрована: количество спикеров может быть неточным. Определение языка честное: короткая или неоднозначная запись может привести к ошибке вместо угадывания.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun bundleName(id: String): String =
    try {
        ModelCatalog.byId(id).displayName
    } catch (_: Exception) {
        id
    }
