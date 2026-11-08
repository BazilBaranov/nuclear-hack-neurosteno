@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.neurostenografist.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

const val TAB_RECORDER = "recorder"
const val TAB_TRANSCRIPTION = "transcription"

/** Routes of the six-screen product: home(tabs), models, history, settings, result/{runId}. */
const val ROUTE_HOME = "home"
const val ROUTE_MODELS = "models"
const val ROUTE_HISTORY = "history"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_RESULT = "result/{runId}"
const val ARG_RUN_ID = "runId"

fun resultRoute(runId: String) = "result/$runId"

@Composable
fun AppRoot(
    vm: AppViewModel,
    state: AppViewModel.UiState,
) {
    val nav = rememberNavController()
    var tab by rememberSaveable { mutableStateOf(TAB_RECORDER) }
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var visitTranscriptionRequested by rememberSaveable { mutableStateOf(false) }
    // Destinations read live state without recreating the navigation graph on progress ticks.
    val screenState by rememberUpdatedState(state)
    val settingsEpoch by rememberUpdatedState(rememberSettingsEpoch(vm.settings))

    // Recording finish must select the saved source and lead into Transcription.
    // Detected by diffing the source list around the armed window (no new backend API).
    var recordingArmed by remember { mutableStateOf(false) }
    val seenSources = remember { mutableStateListOf<String>() }

    LaunchedEffect(state.sources, recordingArmed) {
        val ids = state.sources.map { it.id }
        if (recordingArmed) {
            val published = state.sources.firstOrNull { it.id !in seenSources }
            if (published != null) {
                recordingArmed = false
                selectedSourceId = published.id
                visitTranscriptionRequested = true
            }
        }
        seenSources.clear()
        seenSources.addAll(ids)
    }

    LaunchedEffect(visitTranscriptionRequested) {
        if (visitTranscriptionRequested) {
            tab = TAB_TRANSCRIPTION
            if (nav.currentDestination?.route != ROUTE_HOME) {
                nav.popBackStack(ROUTE_HOME, inclusive = false)
            }
            visitTranscriptionRequested = false
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(userMessage(it))
            vm.clearMessage()
        }
    }

    NavHost(
        navController = nav,
        startDestination = ROUTE_HOME,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
    ) {
        composable(ROUTE_HOME) {
            HomeScreen(
                vm = vm,
                state = screenState,
                nav = nav,
                tab = tab,
                onTab = { tab = it },
                settingsEpoch = settingsEpoch,
                selectedSourceId = selectedSourceId,
                onSelectedSource = { selectedSourceId = it },
                onRecordStop = { recordingArmed = true },
                snackbar = snackbar,
            )
        }
        composable(ROUTE_MODELS) {
            ModelsScreen(vm, screenState, onBack = { nav.popBackStack() })
        }
        composable(ROUTE_HISTORY) {
            HistoryScreen(
                vm = vm,
                state = screenState,
                onOpenRun = { runId -> nav.navigate(resultRoute(runId)) },
                onOpenSource = { sourceId ->
                    selectedSourceId = sourceId
                    visitTranscriptionRequested = true
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(vm, screenState, settingsEpoch, onBack = { nav.popBackStack() })
        }
        composable(ROUTE_RESULT) { entry ->
            ResultScreen(
                vm = vm,
                state = screenState,
                runId = entry.arguments?.getString(ARG_RUN_ID),
                onBack = { nav.popBackStack() },
            )
        }
    }
}

@Composable
fun HomeScreen(
    vm: AppViewModel,
    state: AppViewModel.UiState,
    nav: NavHostController,
    tab: String,
    onTab: (String) -> Unit,
    settingsEpoch: Int,
    selectedSourceId: String?,
    onSelectedSource: (String) -> Unit,
    onRecordStop: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val tabState = rememberSaveableStateHolder()
    fun openFromDrawer(route: String) {
        scope.launch {
            drawerState.snapTo(DrawerValue.Closed)
            nav.navigate(route) { launchSingleTop = true }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.testTag("nav_drawer"),
            ) {
                NavigationDrawerItem(
                    label = { Text("Запись") },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.testTag("nav_recorder"),
                )
                NavigationDrawerItem(
                    label = { Text("Модели") },
                    selected = false,
                    onClick = { openFromDrawer(ROUTE_MODELS) },
                    modifier = Modifier.testTag("nav_models"),
                )
                NavigationDrawerItem(
                    label = { Text("История") },
                    selected = false,
                    onClick = { openFromDrawer(ROUTE_HISTORY) },
                    modifier = Modifier.testTag("nav_history"),
                )
                NavigationDrawerItem(
                    label = { Text("Настройки") },
                    selected = false,
                    onClick = { openFromDrawer(ROUTE_SETTINGS) },
                    modifier = Modifier.testTag("nav_settings"),
                )
                HorizontalDivider()
                Text(
                    "Расшифровка работает на устройстве. Перед первым запуском загрузите модели в разделе «Модели». После подготовки интернет не нужен.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        },
        gesturesEnabled = drawerState.isOpen,
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("Нейро-стенографист") },
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("menu_button"),
                        ) {
                            Icon(
                                painterResource(android.R.drawable.ic_menu_sort_by_size),
                                contentDescription = "Открыть меню",
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(12.dp), Arrangement.spacedBy(12.dp)) {
                TabRow(
                    selectedTabIndex = if (tab == TAB_RECORDER) 0 else 1,
                    modifier = Modifier.testTag("top_tabs"),
                ) {
                    Tab(selected = tab == TAB_RECORDER, onClick = {
                        onTab(TAB_RECORDER)
                    }, text = { Text("Запись") }, modifier = Modifier.testTag("tab_recorder"))
                    Tab(selected = tab == TAB_TRANSCRIPTION, onClick = {
                        onTab(TAB_TRANSCRIPTION)
                    }, text = { Text("Расшифровка") }, modifier = Modifier.testTag("tab_transcription"))
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    tabState.SaveableStateProvider(tab) {
                        if (tab == TAB_RECORDER) {
                            RecorderScreen(vm, state, settingsEpoch, onRecordStop)
                        } else {
                            TranscriptionScreen(
                                vm = vm,
                                state = state,
                                selectedSourceId = selectedSourceId,
                                onSelectedSource = onSelectedSource,
                                onOpenRun = { runId -> nav.navigate(resultRoute(runId)) },
                            )
                        }
                    }
                }
            }
        }
    }
}
