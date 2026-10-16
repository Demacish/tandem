package casa.moller.tandem.feature.cook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.data.FakeCookLogRepository
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.ui.timer.LocalTimerClock
import casa.moller.tandem.core.ui.timer.LocalTimerTicks
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.ui.CookLayout
import casa.moller.tandem.feature.cook.ui.CookScreen
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

/** Compact phone (Pixel 9 class, portrait). */
internal const val PHONE = "w412dp-h915dp-port-xhdpi"

/** A tall phone: a whole step page with its ingredients. */
internal const val PHONE_TALL = "w412dp-h1400dp-port-xhdpi"

/** Phone in landscape: two panes. */
internal const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"

/** Expanded tablet in landscape (Galaxy Tab S10+ class). */
internal const val TABLET = "w1280dp-h800dp-land-hdpi"

/** Tablet in portrait: still two panes (plan 10 "Klart när"). */
internal const val TABLET_PORTRAIT = "w800dp-h1280dp-port-hdpi"

/** A foldable half open on the table (layout forced, Robolectric has no hinge). */
internal const val FOLDABLE = "w412dp-h915dp-port-xhdpi"

/** Fakes behind a real [CookViewModel], so tests and screenshots show what the app computes. */
class CookFakes(
    val clock: TestClock = TestClock(),
    val recipes: FakeRecipeRepository = FakeRecipeRepository(),
    val preferences: FakePreferencesRepository = FakePreferencesRepository(),
    val settings: FakeSettingsRepository = FakeSettingsRepository(),
    val timers: FakeTimerController = FakeTimerController(clock),
    val cookLog: FakeCookLogRepository = FakeCookLogRepository(),
    val sync: FakeSyncRepository = FakeSyncRepository(),
    val handle: SavedStateHandle = SavedStateHandle(),
) {
    internal fun viewModel(key: CookKey): CookViewModel =
        CookViewModel(key, handle, recipes, preferences, settings, timers, cookLog, sync, clock)

    /** Starts a timer from step [stepIndex]'s first suggestion of the sample pasta, like a tap on its button. */
    fun startTimer(stepIndex: Int, label: String, duration: Duration) {
        runBlocking { timers.start(label, duration, SampleRecipes.MushroomPasta.id, stepIndex) }
    }
}

/** Theme, a frozen timer clock and an optional font scale around a screen under test. */
@Composable
internal fun TestFrame(
    darkTheme: Boolean = false,
    fontScale: Float? = null,
    clock: TestClock = TestClock(),
    content: @Composable () -> Unit,
) {
    TandemTheme(darkTheme = darkTheme) {
        val density = LocalDensity.current
        val scaled = if (fontScale == null) density else Density(density.density, fontScale)
        CompositionLocalProvider(
            LocalDensity provides scaled,
            LocalTimerClock provides clock,
            LocalTimerTicks provides false,
            content = content,
        )
    }
}

/** Cook mode driven by a real ViewModel over [fakes]. */
@Composable
internal fun CookWithFakes(
    key: CookKey = CookKey(SampleRecipes.MushroomPasta.id),
    fakes: CookFakes = remember { CookFakes() },
    layout: CookLayout? = null,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    prepare: (CookViewModel) -> Unit = {},
) {
    val viewModel = remember(key) { fakes.viewModel(key).also(prepare) }
    val state by viewModel.uiState.collectAsState()
    // Like CookRoute: leave after "Klart!" was saved.
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect -> if (effect is CookEffect.Finished) onClose() }
    }
    CookScreen(state = state, onAction = viewModel::onAction, onClose = onClose, layout = layout, modifier = modifier)
}
