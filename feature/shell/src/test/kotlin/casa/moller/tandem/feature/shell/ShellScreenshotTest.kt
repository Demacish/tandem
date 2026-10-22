package casa.moller.tandem.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.recipes.navigation.RecipeDetailKey
import casa.moller.tandem.feature.shell.navigation.AboutKey
import casa.moller.tandem.feature.shell.navigation.LanguageKey
import casa.moller.tandem.feature.shell.navigation.LicensesKey
import casa.moller.tandem.feature.shell.navigation.ShellNavigationState
import casa.moller.tandem.feature.shell.navigation.ShellTab
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact phone (Pixel 9 class, portrait) and expanded tablet (Galaxy Tab S10+ class, landscape). */
internal const val PHONE = "w412dp-h915dp-port-xhdpi"
internal const val TABLET = "w1280dp-h800dp-land-hdpi"
internal const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
internal const val PHONE_SV = "sv-$PHONE"
internal const val TABLET_SV = "sv-$TABLET"

/**
 * Screenshots of the shell (Fas 0 "Klart när" #2: bottom bar on phones, rail on tablets) for
 * every tab, "Mer" and its pages, demo mode and the logout dialog, in light and dark. Phase 0
 * only records; CI uploads build/outputs/roborazzi as the "screenshots" artifact.
 *
 * The device is chosen by the subclass's @Config; Swedish tests set their own qualifiers.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ShellScreenshotTest(private val device: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun today() = capture("today_light", CONNECTED_STATE, ShellTab.TODAY)

    @Test
    fun todayDark() = capture("today_dark", CONNECTED_STATE, ShellTab.TODAY, darkTheme = true)

    @Test
    fun recipes() = capture("recipes_light", CONNECTED_STATE, ShellTab.RECIPES)

    @Test
    fun week() = capture("week_light", CONNECTED_STATE, ShellTab.WEEK)

    @Test
    fun shopping() = capture("shopping_light", CONNECTED_STATE, ShellTab.SHOPPING)

    @Test
    fun shoppingDark() = capture("shopping_dark", CONNECTED_STATE, ShellTab.SHOPPING, darkTheme = true)

    @Test
    fun more() = capture("more_light", CONNECTED_STATE, ShellTab.MORE)

    @Test
    fun moreDark() = capture("more_dark", CONNECTED_STATE.copy(themeMode = ThemeMode.DARK), ShellTab.MORE, darkTheme = true)

    @Test
    fun moreSafeModeWithFailedCheck() = capture(
        "more_safe_mode_check_failed_light",
        CONNECTED_STATE.copy(
            mode = LOGGED_IN.copy(serverVersion = "2.7.1", compatibility = ServerCompatibility.UNTESTED_NEWER).toShellMode(),
            refreshError = TandemError.Timeout,
        ),
        ShellTab.MORE,
    )

    @Test
    fun moreDemo() = capture("more_demo_light", DEMO_STATE, ShellTab.MORE)

    /** Tandoor refused the token: the "Log in again" banner above the tab (integration lane). */
    @Test
    fun todayLoginExpired() = capture("today_login_expired_light", CONNECTED_STATE.copy(loginExpired = true), ShellTab.TODAY)

    /** The server was updated and no notification could tell: the one-time banner (komplettering-3, B18). */
    @Test
    fun todayServerUpdated() = capture("today_server_updated_light", CONNECTED_STATE.copy(serverUpdatedTo = "2.6.15"), ShellTab.TODAY)

    @Test
    fun todayDemo() = capture("today_demo_light", DEMO_STATE, ShellTab.TODAY)

    @Test
    fun todayDemoDark() = capture("today_demo_dark", DEMO_STATE, ShellTab.TODAY, darkTheme = true)

    @Test
    fun language() = capture("language_light", CONNECTED_STATE, ShellTab.MORE, LanguageKey)

    /** Phones: the detail covers the bottom bar. Tablets: list and detail side by side. */
    @Test
    fun recipeDetail() = capture("recipe_detail_light", CONNECTED_STATE, ShellTab.RECIPES, RecipeDetailKey(101, inListDetail = true))

    /** Opened from Today: full screen on phones, beside the rail (no list) on tablets. */
    @Test
    fun recipeDetailFromToday() = capture("recipe_detail_from_today_light", DEMO_STATE, ShellTab.TODAY, RecipeDetailKey(103))

    @Test
    fun about() = capture("about_light", CONNECTED_STATE, ShellTab.MORE, AboutKey)

    @Test
    fun licensesDark() = capture("licenses_dark", CONNECTED_STATE, ShellTab.MORE, LicensesKey, darkTheme = true)

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun logoutDialog() {
        setShell(CONNECTED_STATE, ShellTab.MORE)
        val logOut = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.shell_log_out_of, "tandoor.example.com")
        composeRule.onNodeWithText(logOut).performScrollTo().performClick()
        composeRule.onNode(isDialog()).assertExists()
        captureScreenRoboImage(path("logout_dialog_light"))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun logoutDialogPastedToken() = captureLogoutDialog("logout_dialog_pasted_light", PASTED_STATE)

    @OptIn(ExperimentalRoborazziApi::class)
    protected fun captureLogoutDialog(name: String, state: ShellUiState) {
        setShell(state, ShellTab.MORE)
        val logOut = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.shell_log_out_of, "tandoor.example.com")
        composeRule.onNodeWithText(logOut).performScrollTo().performClick()
        composeRule.onNode(isDialog()).assertExists()
        captureScreenRoboImage(path(name))
    }

    protected fun capture(
        name: String,
        state: ShellUiState,
        tab: ShellTab,
        vararg pages: NavKey,
        darkTheme: Boolean = false,
        language: AppLanguage = AppLanguage.SYSTEM,
    ) {
        setShell(state, tab, *pages, darkTheme = darkTheme, language = language)
        composeRule.onRoot().captureRoboImage(path(name))
    }

    private fun setShell(
        state: ShellUiState,
        tab: ShellTab,
        vararg pages: NavKey,
        darkTheme: Boolean = false,
        language: AppLanguage = AppLanguage.SYSTEM,
    ) {
        val navigation = navigationAt(tab, *pages)
        composeRule.setContent {
            Shell(state, navigation, darkTheme, language)
        }
    }

    @Composable
    private fun Shell(state: ShellUiState, navigation: ShellNavigationState, darkTheme: Boolean, language: AppLanguage) {
        TandemTheme(darkTheme = darkTheme) {
            ShellScaffold(
                state = state,
                navigation = navigation,
                actions = RecordingActions(),
                language = language,
                onLanguageSelected = {},
                onConnectTandoor = {},
                features = PlaceholderShellFeatures(state),
            )
        }
    }

    private fun path(name: String) = "$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/${device}_$name.png"
}

/** Navigation state that shows [tab] with [pages] opened on top of its first screen. */
internal fun navigationAt(tab: ShellTab, vararg pages: NavKey): ShellNavigationState = ShellNavigationState(
    stacks = ShellTab.entries.associateWith { entry ->
        NavBackStack<NavKey>(entry.root).apply { if (entry == tab) addAll(pages) }
    },
    initialTab = tab,
)

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class ShellPhoneScreenshotTest : ShellScreenshotTest("phone") {
    @Test
    @Config(qualifiers = PHONE_SV)
    fun moreSwedish() = capture("more_light_sv", CONNECTED_STATE, ShellTab.MORE)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun moreDarkSwedish() = capture("more_dark_sv", CONNECTED_STATE, ShellTab.MORE, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun todayDemoSwedish() = capture("today_demo_light_sv", DEMO_STATE, ShellTab.TODAY)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun languageSwedish() = capture("language_light_sv", CONNECTED_STATE, ShellTab.MORE, LanguageKey, language = AppLanguage.SWEDISH)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun aboutSwedish() = capture("about_light_sv", CONNECTED_STATE, ShellTab.MORE, AboutKey)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun logoutDialogSwedish() = captureLogoutDialog("logout_dialog_light_sv", CONNECTED_STATE)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun logoutDialogPastedTokenSwedish() = captureLogoutDialog("logout_dialog_pasted_light_sv", PASTED_STATE)

    @Test
    @Config(qualifiers = PHONE_SV)
    fun todayServerUpdatedSwedish() =
        capture("today_server_updated_light_sv", CONNECTED_STATE.copy(serverUpdatedTo = "2.6.15"), ShellTab.TODAY)
}

/**
 * A phone in landscape (plan 6.5: medium width gets a navigation rail, so the short window
 * height stays for content).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LANDSCAPE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellPhoneLandscapeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun today() = capture("today_light", ShellTab.TODAY)

    @Test
    fun moreDark() = capture("more_dark", ShellTab.MORE, darkTheme = true)

    @Test
    fun demoDark() = capture("today_demo_dark", ShellTab.TODAY, state = DEMO_STATE, darkTheme = true)

    private fun capture(name: String, tab: ShellTab, state: ShellUiState = CONNECTED_STATE, darkTheme: Boolean = false) {
        val navigation = navigationAt(tab)
        composeRule.setContent {
            TandemTheme(darkTheme = darkTheme) {
                ShellScaffold(
                    state = state,
                    navigation = navigation,
                    actions = RecordingActions(),
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = {},
                    onConnectTandoor = {},
                    features = PlaceholderShellFeatures(state),
                )
            }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/phone_landscape_$name.png")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class ShellTabletScreenshotTest : ShellScreenshotTest("tablet") {
    @Test
    @Config(qualifiers = TABLET_SV)
    fun moreSwedish() = capture("more_light_sv", CONNECTED_STATE, ShellTab.MORE)

    @Test
    @Config(qualifiers = TABLET_SV)
    fun moreDarkSwedish() = capture("more_dark_sv", CONNECTED_STATE, ShellTab.MORE, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET_SV)
    fun todayDemoSwedish() = capture("today_demo_light_sv", DEMO_STATE, ShellTab.TODAY)
}
