package casa.moller.tandem.feature.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.ui.OnboardingScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h915dp-port-xhdpi"
private const val MEDIUM = "w800dp-h1280dp-port-mdpi"
private const val TABLET = "w1280dp-h800dp-land-mdpi"

/**
 * The steps after a login (Stitch intro_f_rsta_synk): the first sync running, failed and done, and
 * the Samsung battery tip, on a phone, a medium tablet in portrait and an expanded tablet, light
 * and dark, English and Swedish, and at font scale 2.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class OnboardingFirstSyncScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val connected = ConnectedSummary("tandoor.example.com", "2.6.13", "Anna", safeMode = false)
    private val running = OnboardingUiState(
        step = OnboardingStep.FIRST_SYNC,
        connected = connected,
        firstSync = FirstSyncState(progress = SyncProgress(SyncPhase.RECIPE_INDEX, done = 214, total = 312), recipeCount = 312),
    )
    private val failed = running.copy(
        firstSync = FirstSyncState(
            progress = SyncProgress(SyncPhase.RECIPE_INDEX, done = 40, total = 312),
            recipeCount = 312,
            error = TandemError.Timeout,
        ),
    )
    private val batteryTip = OnboardingUiState(step = OnboardingStep.BATTERY_TIP, connected = connected)

    @Test
    fun firstSync() = capture("phone_first_sync_light", running)

    @Test
    fun firstSyncDark() = capture("phone_first_sync_dark", running, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun firstSyncSwedish() = capture("phone_first_sync_light_sv", running)

    @Test
    fun firstSyncFontScale2() = capture("phone_first_sync_font2_light", running, fontScale = 2f)

    @Test
    fun firstSyncStarting() = capture("phone_first_sync_starting_light", running.copy(firstSync = FirstSyncState()))

    @Test
    fun firstSyncFailed() = capture("phone_first_sync_failed_light", failed)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun firstSyncFailedSwedishDark() = capture("phone_first_sync_failed_dark_sv", failed, darkTheme = true)

    @Test
    fun batteryTipStep() = capture("phone_battery_tip_light", batteryTip)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun batteryTipSwedish() = capture("phone_battery_tip_light_sv", batteryTip)

    @Test
    @Config(qualifiers = MEDIUM)
    fun firstSyncMedium() = capture("medium_first_sync_light", running)

    @Test
    @Config(qualifiers = TABLET)
    fun firstSyncTablet() = capture("tablet_first_sync_light", running)

    @Test
    @Config(qualifiers = TABLET)
    fun firstSyncTabletDark() = capture("tablet_first_sync_dark", running, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun batteryTipTablet() = capture("tablet_battery_tip_light", batteryTip)

    private fun capture(name: String, state: OnboardingUiState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    OnboardingScreen(state = state, actions = NoOpActions, language = AppLanguage.SYSTEM, onLanguageSelected = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/onboarding/$name.png")
    }
}
