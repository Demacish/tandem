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
import androidx.compose.ui.test.hasClickAction
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
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.ui.R as UiR
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.navigation.TimersKey
import casa.moller.tandem.feature.shell.navigation.AboutKey
import casa.moller.tandem.feature.shell.navigation.AppearanceSettingsKey
import casa.moller.tandem.feature.shell.navigation.CookingSettingsKey
import casa.moller.tandem.feature.shell.navigation.DiagnosticsKey
import casa.moller.tandem.feature.shell.navigation.LanguageKey
import casa.moller.tandem.feature.shell.navigation.LicensesKey
import casa.moller.tandem.feature.shell.navigation.MoreKey
import casa.moller.tandem.feature.shell.navigation.PermissionsKey
import casa.moller.tandem.feature.shell.navigation.RecipesKey
import casa.moller.tandem.feature.shell.navigation.ShellNavigationState
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.ShoppingKey
import casa.moller.tandem.feature.shell.navigation.SyncSettingsKey
import casa.moller.tandem.feature.shell.navigation.TodayKey
import casa.moller.tandem.feature.shell.navigation.WeekKey
import casa.moller.tandem.feature.shell.settings.FixedSettingsPages
import casa.moller.tandem.feature.shell.settings.SettingsAction
import casa.moller.tandem.feature.shell.settings.SystemSetting
import casa.moller.tandem.feature.shell.status.SYNC_HELD_BACK_BANNER_TAG
import casa.moller.tandem.feature.shell.timer.TIMER_HUD_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Behaviour of the shell: adaptive navigation, tabs, "Mer" and its settings pages, banners, logout and demo mode. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class ShellScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingActions()
    private val settingsPages = FixedSettingsPages()
    private val languages = mutableListOf<AppLanguage>()
    private var connectClicks = 0

    private fun string(@StringRes id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun setShell(state: ShellUiState = CONNECTED_STATE, navigation: ShellNavigationState = navigationAt(ShellTab.TODAY)) {
        composeRule.setContent {
            TandemTheme {
                FrozenTimers {
                    ShellScaffold(
                        state = state,
                        navigation = navigation,
                        actions = actions,
                        language = AppLanguage.SYSTEM,
                        onLanguageSelected = { languages += it },
                        onConnectTandoor = { connectClicks++ },
                        features = PlaceholderShellFeatures(state),
                        settingsPages = settingsPages,
                    )
                }
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
        composeRule.onNodeWithText(tabStubText(ShellTab.TODAY)).assertIsDisplayed()

        tab(R.string.shell_tab_shopping).performClick()
        tab(R.string.shell_tab_shopping).assertIsSelected()
        composeRule.onNodeWithText(tabStubText(ShellTab.SHOPPING)).assertIsDisplayed()
    }

    @Test
    fun moreOpensLanguageAndComesBack() {
        val navigation = navigationAt(ShellTab.TODAY)
        setShell(navigation = navigation)
        tab(R.string.shell_tab_more).performClick()
        composeRule.onNodeWithText(string(R.string.shell_account_signed_in_as, "anna")).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.shell_language)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.shell_language_name_sv)).performClick()
        assertEquals(listOf(AppLanguage.SWEDISH), languages)

        composeRule.onNode(hasContentDescription(string(casa.moller.tandem.core.designsystem.R.string.tandem_action_back))).performClick()
        composeRule.onNodeWithText(string(R.string.shell_about)).performScrollTo().assertIsDisplayed()
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

    // --- Phase 1b (inställningar lane): the settings sub-screens, the held-back sync banner ---

    @Test
    fun everySettingsRowOpensItsPageAndBackReturnsToMore() {
        val navigation = navigationAt(ShellTab.MORE)
        setShell(navigation = navigation)
        val rows = listOf(
            R.string.settings_sync to SyncSettingsKey,
            R.string.settings_appearance to AppearanceSettingsKey,
            R.string.settings_cooking to CookingSettingsKey,
            R.string.settings_permissions to PermissionsKey,
            R.string.shell_language to LanguageKey,
            R.string.settings_diagnostics to DiagnosticsKey,
            R.string.shell_about to AboutKey,
        )
        rows.forEach { (title, key) ->
            composeRule.onNode(hasText(string(title)) and hasClickAction()).performScrollTo().performClick()
            composeRule.waitForIdle()
            assertEquals(listOf(MoreKey, key), navigation.stackOf(ShellTab.MORE))
            composeRule.runOnIdle { navigation.goBack() }
        }
    }

    @Test
    fun theThemeIsChangedUnderAppearance() {
        setShell(navigation = navigationAt(ShellTab.MORE, AppearanceSettingsKey))
        composeRule.onNodeWithText(string(R.string.shell_theme_dark)).performClick()
        assertEquals(listOf<SettingsAction>(SettingsAction.SetThemeMode(ThemeMode.DARK)), settingsPages.actions)
    }

    @Test
    fun cookModeSettingsLinkToPermissionsAndBack() {
        val navigation = navigationAt(ShellTab.MORE, CookingSettingsKey)
        setShell(navigation = navigation)
        composeRule.onNodeWithText(string(R.string.settings_permissions)).performScrollTo().performClick()
        assertEquals(listOf(MoreKey, CookingSettingsKey, PermissionsKey), navigation.stackOf(ShellTab.MORE))
        composeRule.onNode(hasContentDescription(string(casa.moller.tandem.core.designsystem.R.string.tandem_action_back))).performClick()
        assertEquals(listOf(MoreKey, CookingSettingsKey), navigation.stackOf(ShellTab.MORE))
    }

    @Test
    fun aPermissionRowOpensAndroidsSettings() {
        setShell(navigation = navigationAt(ShellTab.MORE, PermissionsKey))
        val notifications = string(R.string.settings_permission_notifications)
        composeRule.onNode(hasContentDescription(string(R.string.settings_permission_open_description, notifications)))
            .performScrollTo()
            .performClick()
        assertEquals(listOf(SystemSetting.NOTIFICATIONS), settingsPages.openedSystemSettings)
    }

    @Test
    @Config(qualifiers = TABLET)
    fun onATabletThePagesOpenBesideTheListAndAnotherRowReplacesThem() {
        val navigation = navigationAt(ShellTab.MORE, AboutKey, LicensesKey)
        setShell(navigation = navigation)
        // The list and the open page side by side (design mer_inst_llningar_surfplatta).
        composeRule.onNodeWithText("Anna Andersson").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.shell_licenses_fonts)).assertIsDisplayed()

        composeRule.onNode(hasText(string(R.string.settings_diagnostics)) and hasClickAction()).performScrollTo().performClick()

        assertEquals(listOf(MoreKey, DiagnosticsKey), navigation.stackOf(ShellTab.MORE))
        composeRule.onNode(hasText(string(R.string.settings_diagnostics)) and hasClickAction()).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.settings_diagnostics_export)).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = TABLET)
    fun onATabletMoreWithoutAPageShowsAHint() {
        setShell(navigation = navigationAt(ShellTab.MORE))
        composeRule.onNodeWithText(string(R.string.settings_pick_one)).assertIsDisplayed()
    }

    @Test
    fun aHeldBackSyncIsShownOnEveryTabWithTryAgain() {
        val navigation = navigationAt(ShellTab.TODAY)
        setShell(state = CONNECTED_STATE.copy(syncHeldBack = SyncPause.CIRCUIT_BREAKER), navigation = navigation)
        composeRule.onNodeWithText(string(R.string.sync_breaker_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.sync_retry)).performClick()
        assertEquals(1, actions.syncRetries)

        tab(R.string.shell_tab_recipes).performClick()
        composeRule.onNodeWithTag(SYNC_HELD_BACK_BANNER_TAG).assertIsDisplayed()
    }

    @Test
    fun aBlockedSyncSaysSo() {
        setShell(state = CONNECTED_STATE.copy(syncHeldBack = SyncPause.BLOCKED))
        composeRule.onNodeWithText(string(R.string.sync_blocked_title)).assertIsDisplayed()
    }

    @Test
    fun theTimerBarIsOnEveryTabAndOpensCookModeAtTheTimersStep() {
        val navigation = navigationAt(ShellTab.TODAY)
        setShell(state = CONNECTED_STATE.copy(timers = listOf(SAUCE_TIMER, PASTA_TIMER)), navigation = navigation)
        composeRule.onNodeWithTag(TIMER_HUD_TAG).assertIsDisplayed()
        tab(R.string.shell_tab_more).performClick()
        composeRule.onNodeWithTag(TIMER_HUD_TAG).assertIsDisplayed()

        // The oldest running or paused timer leads; Pasta was started first.
        composeRule.onNode(hasClickAction() and hasContentDescription("Pasta", substring = true)).performClick()

        assertEquals(CookKey(101, startStep = 2), navigation.currentKey)
        // Cook mode shows the timers itself.
        composeRule.onNodeWithTag(TIMER_HUD_TAG).assertDoesNotExist()
    }

    @Test
    fun theOtherTimersOpenTheTimerList() {
        val navigation = navigationAt(ShellTab.RECIPES)
        setShell(state = CONNECTED_STATE.copy(timers = listOf(PASTA_TIMER, SAUCE_TIMER)), navigation = navigation)
        composeRule.onNode(hasContentDescription(string(R.string.timer_hud_show_all, 2))).performClick()
        assertEquals(TimersKey, navigation.currentKey)
        composeRule.onNodeWithTag(TIMER_HUD_TAG).assertDoesNotExist()
    }

    @Test
    fun aTimerWithoutARecipeOpensTheTimerList() {
        val navigation = navigationAt(ShellTab.TODAY)
        setShell(state = CONNECTED_STATE.copy(timers = listOf(SAUCE_TIMER)), navigation = navigation)
        composeRule.onNode(hasClickAction() and hasContentDescription("Sås", substring = true)).performClick()
        assertEquals(TimersKey, navigation.currentKey)
    }

    @Test
    fun aRingingTimerLeadsAndStopsFromTheBar() {
        setShell(state = CONNECTED_STATE.copy(timers = listOf(PASTA_TIMER, OVEN_TIMER)))
        composeRule.onNode(hasContentDescription(string(R.string.timer_hud_stop_description, "Ugnen"))).performClick()
        assertEquals(listOf(OVEN_TIMER.id), actions.stoppedTimers)
    }

    @Test
    fun noTimerBarWithoutTimersOrWithOnlyFinishedOnes() {
        setShell(state = CONNECTED_STATE.copy(timers = listOf(OVEN_TIMER.stopRinging())))
        composeRule.onNodeWithTag(TIMER_HUD_TAG).assertDoesNotExist()
    }

    @Test
    fun logInAgainHidesTheHeldBackBanner() {
        setShell(state = CONNECTED_STATE.copy(syncHeldBack = SyncPause.BLOCKED, loginExpired = true))
        composeRule.onNodeWithTag(SYNC_HELD_BACK_BANNER_TAG).assertDoesNotExist()
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

    // --- komplettering-3: the one-time "Tandoor was updated" banner (plan B18 fallback) ---

    @Test
    fun aServerUpdateIsShownAboveTheTabsUntilItIsClosed() {
        setShell(state = CONNECTED_STATE.copy(serverUpdatedTo = "2.6.15"))
        composeRule.onNodeWithText(string(R.string.shell_server_updated_title, "2.6.15")).assertIsDisplayed()
        composeRule.onNode(hasContentDescription(string(DesignR.string.tandem_action_close))).performClick()
        assertEquals(1, actions.serverUpdateDismissals)
    }

    @Test
    fun aServerUpdateIsNeverShownNextToTheSafeModeBanner() {
        val untested = LOGGED_IN.copy(serverVersion = "2.7.0", compatibility = ServerCompatibility.UNTESTED_NEWER)
        setShell(state = CONNECTED_STATE.copy(mode = untested.toShellMode(), serverUpdatedTo = "2.7.0"))
        composeRule.onNodeWithText(string(UiR.string.ui_safe_mode_headline_newer, "2.7.0")).assertExists()
        composeRule.onNodeWithText(string(R.string.shell_server_updated_title, "2.7.0")).assertDoesNotExist()
    }
}
