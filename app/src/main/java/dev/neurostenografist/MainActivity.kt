package dev.neurostenografist

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.neurostenografist.ui.AppRoot
import dev.neurostenografist.ui.AppViewModel
import dev.neurostenografist.ui.appTheme

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION") // System bar colors remain necessary before enforced edge-to-edge on API 35.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.ensureChannels(this)
        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.ui.collectAsState()
            appTheme(vm.settings) {
                val surface = MaterialTheme.colorScheme.surface
                SideEffect {
                    val darkIcons = surface.luminance() > 0.5f
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = darkIcons
                        isAppearanceLightNavigationBars = darkIcons
                    }
                    if (Build.VERSION.SDK_INT < 35) {
                        window.statusBarColor = surface.toArgb()
                        window.navigationBarColor = surface.toArgb()
                    }
                }
                Surface(Modifier.fillMaxSize(), color = surface) {
                    AppRoot(vm = vm, state = state)
                }
            }
        }
    }
}
