package casa.moller.tandem

import android.app.UiModeManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.diagnostics.DiagnosticsRepository
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.diagnostics.ColdStart
import casa.moller.tandem.navigation.AppDestination
import casa.moller.tandem.navigation.AppViewModel
import casa.moller.tandem.ui.TandemApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    /** Settings → Diagnostics: where the cold start is stored (Phase 1b). */
    @Inject
    lateinit var diagnostics: DiagnosticsRepository

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: switches from Theme.Tandem.Starting to Theme.Tandem.
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        measureColdStart()
        // Keep the splash until the stored session is read, so the intro never flashes for a
        // user who is already logged in.
        splashScreen.setKeepOnScreenCondition { appViewModel.uiState.value.destination == AppDestination.Loading }
        setContent {
            val state by appViewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The window background and the splash screen come from resources (values-night),
            // so the system must know the app's own choice too. It persists it, so the next
            // cold start already shows the right splash colours.
            LaunchedEffect(state.themeMode, state.themeModeLoaded) {
                if (state.themeModeLoaded) applyApplicationNightMode(state.themeMode)
            }
            // The app's own theme choice (Mer -> Tema) can differ from the system's, so the
            // status and navigation bar icons follow the app theme, not the system.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            // Settings → Appearance (Phase 1b): theme and "Dynamisk färg" for the whole app at once.
            TandemTheme(darkTheme = darkTheme, dynamicColor = state.dynamicColor) {
                TandemApp(viewModel = appViewModel)
            }
        }
    }

    /**
     * The cold start up to the first screen with content (Today or the recipe list call
     * `reportFullyDrawn` through core:ui's `ReportContentReady`), stored for Settings →
     * Diagnostics. Only for the activity the process was started for ([ColdStart]).
     */
    private fun measureColdStart() {
        if (!ColdStart.takeForFirstActivity()) return
        fullyDrawnReporter.addOnReportDrawnListener {
            val duration = ColdStart.elapsedSinceProcessStart() ?: return@addOnReportDrawnListener
            applicationScope.launch { diagnostics.recordColdStart(duration) }
        }
    }

    /**
     * `UiModeManager.setApplicationNightMode` (API 31+): the night mode of this app's
     * configuration. Changing it recreates the activity once; the state lives in [AppViewModel].
     * Setting the value it already has changes no configuration, so this is safe on every start.
     */
    private fun applyApplicationNightMode(mode: ThemeMode) {
        val uiModeManager = getSystemService(UiModeManager::class.java) ?: return
        uiModeManager.setApplicationNightMode(
            when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    private companion object {
        // androidx.activity's defaults for the three-button navigation bar scrim.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
