package casa.moller.tandem.feature.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.recipes.navigation.RecipeDetailKey
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import casa.moller.tandem.feature.shell.timer.TIMER_HUD_STATUS_TAG
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the timer bar on every tab (Phase 1b, plan 8.1 "Timer-HUD"): running with
 * others, paused, ringing, above the bottom bar and over a full-screen recipe on phones, beside
 * the rail on tablets. Only recorded; CI uploads build/outputs/roborazzi.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TimerHudScreenshotTest(private val device: String) {

    @get:Rule
    val composeRule = createComposeRule()

    private val running = CONNECTED_STATE.copy(timers = listOf(PASTA_TIMER, SAUCE_TIMER))
    private val ringing = CONNECTED_STATE.copy(timers = listOf(PASTA_TIMER, OVEN_TIMER, SAUCE_TIMER))

    @Test
    fun todayRunning() = capture("today_timer_running_light", running, ShellTab.TODAY)

    @Test
    fun todayRunningDark() = capture("today_timer_running_dark", running, ShellTab.TODAY, darkTheme = true)

    @Test
    fun moreRinging() = capture("more_timer_ringing_light", ringing, ShellTab.MORE)

    @Test
    fun moreRingingDark() = capture("more_timer_ringing_dark", ringing, ShellTab.MORE, darkTheme = true)

    @Test
    fun recipesPaused() = capture("recipes_timer_paused_light", CONNECTED_STATE.copy(timers = listOf(SAUCE_TIMER)), ShellTab.RECIPES)

    /** Phones: the recipe covers the bottom bar; the timer bar keeps clear of the system's. */
    @Test
    fun recipeDetailRunning() = capture("recipe_detail_timer_running_light", running, ShellTab.TODAY, RecipeDetailKey(103))

    // fix-ui (0.2.0 review): a real label never pushes the time out of the bar.
    @Test
    fun longLabelRunningFontScale2() {
        capture(
            "today_timer_long_label_font_scale_2_light",
            CONNECTED_STATE.copy(timers = listOf(LONG_TIMER, SAUCE_TIMER)),
            ShellTab.TODAY,
            fontScale = 2f,
        )
        composeRule.onNodeWithTag(TIMER_HUD_STATUS_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun longLabelRingingFontScale2() {
        capture(
            "today_timer_long_label_ringing_font_scale_2_light",
            CONNECTED_STATE.copy(timers = listOf(LONG_RINGING_TIMER, LONG_TIMER)),
            ShellTab.TODAY,
            fontScale = 2f,
        )
        composeRule.onNodeWithTag(TIMER_HUD_STATUS_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun todayRunningFontScale2() = capture("today_timer_running_font_scale_2_light", ringing, ShellTab.TODAY, fontScale = 2f)

    protected fun capture(
        name: String,
        state: ShellUiState,
        tab: ShellTab,
        vararg pages: NavKey,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val navigation = navigationAt(tab, *pages)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    FrozenTimers {
                        ShellScaffold(
                            state = state,
                            navigation = navigation,
                            actions = RecordingActions(),
                            language = AppLanguage.SYSTEM,
                            onLanguageSelected = {},
                            onConnectTandoor = {},
                            features = PlaceholderShellFeatures(state),
                            settingsPages = FixedSettingsPages(),
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/timer_hud_${device}_$name.png")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class TimerHudPhoneScreenshotTest : TimerHudScreenshotTest("phone") {
    @Test
    @Config(qualifiers = PHONE_SV)
    fun longLabelFontScale2Swedish() = capture(
        "today_timer_long_label_font_scale_2_light_sv",
        CONNECTED_STATE.copy(timers = listOf(LONG_TIMER, SAUCE_TIMER)),
        ShellTab.TODAY,
        fontScale = 2f,
    )

    @Test
    @Config(qualifiers = PHONE_SV)
    fun todayRunningSwedish() =
        capture("today_timer_running_light_sv", CONNECTED_STATE.copy(timers = listOf(PASTA_TIMER, SAUCE_TIMER)), ShellTab.TODAY)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun moreRingingSwedish() =
        capture("more_timer_ringing_light_sv", CONNECTED_STATE.copy(timers = listOf(OVEN_TIMER, PASTA_TIMER)), ShellTab.MORE)
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class TimerHudTabletScreenshotTest : TimerHudScreenshotTest("tablet")
