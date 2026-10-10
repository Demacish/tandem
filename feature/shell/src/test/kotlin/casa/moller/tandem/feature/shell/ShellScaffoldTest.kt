package casa.moller.tandem.feature.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.ui.R as UiR
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.navigation.MoreKey
import casa.moller.tandem.feature.shell.navigation.RecipesKey
import casa.moller.tandem.feature.shell.navigation.ShellNavigationState
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.ShoppingKey
import casa.moller.tandem.feature.shell.navigation.TodayKey
import casa.moller.tandem.feature.shell.navigation.WeekKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Behaviour of the shell: adaptive navigation, tabs, "Mer" pages, theme, logout and demo mode. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class ShellScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingActions()
    private val languages = mutableListOf<AppLanguage>()
    private var connectClicks = 0

    private fun string(@StringRes id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun setShell(state: ShellUiState = CONNECTED_STATE, navigation: ShellNavigationState = navigationAt(ShellTab.TODAY)) {
        composeRule.setContent {
            TandemTheme {
                ShellScaffold(
                    state = state,
                    navigation = navigation,
                    actions = actions,
                    language = AppLanguage.SYSTEM,
                    onLanguageSelected = { languages += it },
                    onConnectTandoor = { connectClicks++ },
                )
            }
        }
    }

    private fun tab(@StringRes label: Int) = composeRule.onNode(hasText(string(label)) and isSelectable())

    @Test
    fun phoneGetsABottomBar() {
        setShell()
        composeRule.onNodeWithTag(SHELL_TEST_TAG_PREFIX + NavigationSuiteType.ShortNavigationBarCompact).assertExists()
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletGetsAnExpandedRail() {
        setShell()
        composeRule.onNodeWithTag(SHELL_TEST_TAG_PREFIX + NavigationSuiteType.WideNavigationRailExpanded).assertExists()
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp-port-hdpi")
    fun mediumWidthGetsARail() {
        setShell()
        composeRule.onNodeWithTag(SHELL_TEST_TAG_PREFIX + NavigationSuiteType.WideNavigationRailCollapsed).assertExists()
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneInLandscapeGetsARailNotABottomBar() {
        setShell()
        composeRule.onNodeWithTag(SHELL_TEST_TAG_PREFIX + NavigationSuiteType.WideNavigationRailCollapsed).assertExists()
    }

    @Test
    @Config(qualifiers = "w900dp-h800dp-land-hdpi")
    fun below1000dpTheRailStaysCollapsed() {
        setShell()
        composeRule.onNodeWithTag(SHELL_TEST_TAG_PREFIX + NavigationSuiteType.WideNavigationRailCollapsed).assertExists()
    }

    @Test
    @Config(qualifiers = "w1100dp-h800dp-land-hdpi")
    fun from1000dpTheRailIsExpanded() {
        setShell()
        composeRule.onNodeWithTag(SHELL_TEST_TAG_PREFIX + NavigationSuiteType.WideNavigationRailExpanded).assertExists()
    }

    @Test
    fun navigationTypeFollowsTheWindowSize() {
        fun type(width: Int, height: Int) = shellNavigationSuiteType(DpSize(width.dp, height.dp))
        assertEquals(NavigationSuiteType.ShortNavigationBarCompact, type(412, 915))
        assertEquals(NavigationSuiteType.ShortNavigationBarCompact, type(599, 400))
        assertEquals(NavigationSuiteType.WideNavigationRailCollapsed, type(600, 900))
        assertEquals(NavigationSuiteType.WideNavigationRailCollapsed, type(915, 412))
        assertEquals(NavigationSuiteType.WideNavigationRailCollapsed, type(999, 800))
        assertEquals(NavigationSuiteType.WideNavigationRailExpanded, type(1000, 800))
        assertEquals(NavigationSuiteType.WideNavigationRailExpanded, type(1024, 768))
        assertEquals(NavigationSuiteType.WideNavigationRailExpanded, type(1280, 800))
        assertEquals("too low for five labelled items", NavigationSuiteType.WideNavigationRailCollapsed, type(1100, 400))
    }

    @Test
    fun hiddenTabsKeepTheirSavedState() {
        val navigation = navigationAt(ShellTab.TODAY)
        composeRule.setContent {
            ShellNavDisplay(
                navigation = navigation,
                entryProvider = entryProvider<NavKey> {
                    entry<TodayKey> { Text("today") }
                    entry<RecipesKey> { Text("recipes") }
                    entry<WeekKey> { Text("week") }
                    entry<ShoppingKey> { Text("shopping") }
                    entry<MoreKey> {
                        var taps by rememberSaveable { mutableIntStateOf(0) }
                        Text("more taps=$taps", Modifier.clickable { taps++ })
                    }
                },
            )
        }
        composeRule.runOnIdle { navigation.selectTab(ShellTab.MORE) }
        composeRule.onNodeWithText("more taps=0").performClick()
        composeRule.onNodeWithText("more taps=1").assertExists()

        composeRule.runOnIdle { navigation.selectTab(ShellTab.RECIPES) }
        composeRule.onNodeWithText("recipes").assertExists()
        composeRule.onNodeWithText("more taps=1").assertDoesNotExist()

        composeRule.runOnIdle { navigation.selectTab(ShellTab.MORE) }
        composeRule.onNodeWithText("more taps=1").assertExists()
    }

    @Test
    fun allFiveTabsAreThereAndSwitch() {
        setShell()
        listOf(
            R.string.shell_tab_today,
            R.string.shell_tab_recipes,
            R.string.shell_tab_week,
            R.string.shell_tab_shopping,
            R.string.shell_tab_more,
        ).forEach { tab(it).assertExists() }
        tab(R.string.shell_tab_today).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.shell_today_title)).assertIsDisplayed()

        tab(R.string.shell_tab_shopping).performClick()
        tab(R.string.shell_tab_shopping).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.shell_shopping_title)).assertIsDisplayed()
    }

    @Test
    fun moreOpensLanguageAndComesBack() {
        val navigation = navigationAt(ShellTab.TODAY)
        setShell(navigation = navigation)
        tab(R.string.shell_tab_more).performClick()
        composeRule.onNodeWithText(string(R.string.shell_account_signed_in_as, "anna")).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.shell_language)).performClick()
        composeRule.onNodeWithText(string(R.string.shell_language_name_sv)).performClick()
        assertEquals(listOf(AppLanguage.SWEDISH), languages)

        composeRule.onNode(hasContentDescription(string(casa.moller.tandem.core.designsystem.R.string.tandem_action_back))).performClick()
        composeRule.onNodeWithText(string(R.string.shell_about)).assertIsDisplayed()
        assertEquals(ShellTab.MORE, navigation.currentTab)
    }

    @Test
    fun accountCardShowsServerAndVersion() {
        setShell(navigation = navigationAt(ShellTab.MORE))
        composeRule.onNodeWithText("Anna Andersson").assertIsDisplayed()
        composeRule.onNodeWithText("https://tandoor.example.com").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.shell_account_version, "2.6.13")).assertIsDisplayed()
        composeRule.onNode(hasContentDescription(string(R.string.shell_compat_supported_description))).assertExists()
    }

    @Test
    fun themeChoiceIsSent() {
        setShell(navigation = navigationAt(ShellTab.MORE))
        composeRule.onNodeWithText(string(R.string.shell_theme_dark)).performScrollTo().performClick()
        assertEquals(listOf(ThemeMode.DARK), actions.themes)
    }

    @Test
    fun logoutRevokesTheDeviceTokenByDefault() {
        setShell(navigation = navigationAt(ShellTab.MORE))
        composeRule.onNodeWithText(string(R.string.shell_log_out_of, "tandoor.example.com")).performScrollTo().performClick()
        composeRule.onNode(isToggleable()).assertIsOn()
        composeRule.onNodeWithText(string(R.string.shell_logout_confirm)).performClick()
        assertEquals(listOf(true), actions.logouts)
    }

    @Test
    fun logoutCanKeepTheToken() {
        setShell(navigation = navigationAt(ShellTab.MORE))
        composeRule.onNodeWithText(string(R.string.shell_log_out_of, "tandoor.example.com")).performScrollTo().performClick()
        composeRule.onNode(isToggleable()).performClick().assertIsOff()
        composeRule.onNodeWithText(string(R.string.shell_logout_confirm)).performClick()
        assertEquals(listOf(false), actions.logouts)
    }

    @Test
    fun pastedTokenIsNeverOfferedForRevocation() {
        setShell(state = PASTED_STATE, navigation = navigationAt(ShellTab.MORE))
        composeRule.onNodeWithText(string(R.string.shell_log_out_of, "tandoor.example.com")).performScrollTo().performClick()
        composeRule.onNode(isToggleable()).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.shell_logout_pasted_token_note)).assertExists()
        composeRule.onNodeWithText(string(R.string.shell_logout_confirm)).performClick()
        assertEquals(listOf(false), actions.logouts)
    }

    @Test
    fun demoModeShowsChipAndBannerOnEveryTab() {
        setShell(state = DEMO_STATE)
        listOf(R.string.shell_tab_today, R.string.shell_tab_recipes, R.string.shell_tab_week, R.string.shell_tab_shopping)
            .forEach { label ->
                tab(label).performClick()
                composeRule.onNode(hasContentDescription(string(UiR.string.ui_status_demo_description))).assertExists()
                composeRule.onNodeWithText(string(UiR.string.ui_demo_banner_title)).assertExists()
            }
        composeRule.onNodeWithText(string(UiR.string.ui_demo_banner_connect)).performClick()
        assertEquals(1, connectClicks)
    }

    @Test
    fun demoModeHasNoLogout() {
        setShell(state = DEMO_STATE, navigation = navigationAt(ShellTab.MORE))
        composeRule.onNodeWithText(string(R.string.shell_log_out_of, "tandoor.example.com")).assertDoesNotExist()
        composeRule.onNodeWithText(string(UiR.string.ui_demo_banner_connect)).assertExists()
    }
}
