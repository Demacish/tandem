package casa.moller.tandem.feature.shell

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.core.ui.language.LanguageController
import casa.moller.tandem.core.ui.language.rememberLanguageController
import casa.moller.tandem.feature.shell.more.AboutScreen
import casa.moller.tandem.feature.shell.more.LanguageScreen
import casa.moller.tandem.feature.shell.more.LicenseTextScreen
import casa.moller.tandem.feature.shell.more.LicensesScreen
import casa.moller.tandem.feature.shell.more.MoreScreen
import casa.moller.tandem.feature.shell.navigation.AboutKey
import casa.moller.tandem.feature.shell.navigation.LanguageKey
import casa.moller.tandem.feature.shell.navigation.LicenseTextKey
import casa.moller.tandem.feature.shell.navigation.LicensesKey
import casa.moller.tandem.feature.shell.navigation.MoreKey
import casa.moller.tandem.feature.shell.navigation.RecipesKey
import casa.moller.tandem.feature.shell.navigation.ShellNavigationState
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.navigation.ShoppingKey
import casa.moller.tandem.feature.shell.navigation.TodayKey
import casa.moller.tandem.feature.shell.navigation.WeekKey
import casa.moller.tandem.feature.shell.navigation.rememberShellNavigationState
import casa.moller.tandem.feature.shell.ui.PlaceholderTab
import casa.moller.tandem.feature.shell.ui.icon
import casa.moller.tandem.feature.shell.ui.label
import casa.moller.tandem.feature.shell.ui.selectedIcon

/**
 * The app after the intro (logged in or demo mode): five tabs in a [NavigationSuiteScaffold]
 * (plan 6.1/6.5) with one Navigation 3 back stack per tab.
 *
 * @param onConnectTandoor "Anslut din Tandoor" in demo mode: the app leaves demo mode and opens
 *   the intro at the server step (plan 6.2).
 * @param crashNotice shown at the top of Mer: the app's "Tandem crashed last time" card while a
 *   crash log is stored (plan 4.2 p7); emits nothing otherwise.
 */
@Composable
fun TandemShell(
    onConnectTandoor: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShellViewModel = hiltViewModel(),
    languageController: LanguageController = rememberLanguageController(),
    crashNotice: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Plan 3.6 point 3: check the server version again when the app comes back to the
    // foreground (throttled to once per 15 minutes in the ViewModel).
    LifecycleResumeEffect(viewModel) {
        viewModel.onAppResumed()
        onPauseOrDispose { }
    }
    val navigation = rememberShellNavigationState()
    var language by remember(languageController) { mutableStateOf(languageController.current) }
    ShellScaffold(
        state = state,
        navigation = navigation,
        actions = viewModel,
        language = language,
        onLanguageSelected = {
            language = it
            languageController.select(it)
        },
        onConnectTandoor = onConnectTandoor,
        modifier = modifier,
        crashNotice = crashNotice,
    )
}

/** Test tag prefix of the shell root; the suffix is the [NavigationSuiteType] in use. */
const val SHELL_TEST_TAG_PREFIX = "tandem_shell:"

/** From this window width the rail is expanded with labels beside the icons (plan 6.5). */
const val EXPANDED_RAIL_MIN_WIDTH_DP: Int = 1000

/** Below this width (compact, i.e. phones in portrait) the shell uses a bottom bar. */
const val RAIL_MIN_WIDTH_DP: Int = 600

/** An expanded rail lists five tabs with labels; below this height it stays collapsed. */
const val EXPANDED_RAIL_MIN_HEIGHT_DP: Int = 480

/**
 * Navigation component for the window (plan 6.5, decided from the window size, never from "is
 * it a tablet"):
 * - width below 600 dp (phones in portrait): a short bottom bar;
 * - from 600 dp (phones in landscape, small tablets, split screen): a collapsed navigation rail,
 *   which keeps the full height for content (a propped-up phone in the kitchen);
 * - from 1000 dp, if the window is at least 480 dp tall: the rail expanded with labels beside the
 *   icons. The M3 Expressive expanded rail takes the place of the permanent navigation drawer
 *   from the plan's table.
 *
 * The raw window width is used, not the size class: the V2 size classes have no bucket at
 * 1000 dp (only 600/840/1200/1600). There is deliberately no exception for the tabletop posture.
 */
fun shellNavigationSuiteType(windowSize: DpSize): NavigationSuiteType = when {
    windowSize.width < RAIL_MIN_WIDTH_DP.dp -> NavigationSuiteType.ShortNavigationBarCompact

    windowSize.width >= EXPANDED_RAIL_MIN_WIDTH_DP.dp && windowSize.height >= EXPANDED_RAIL_MIN_HEIGHT_DP.dp ->
        NavigationSuiteType.WideNavigationRailExpanded

    else -> NavigationSuiteType.WideNavigationRailCollapsed
}

/** The current window size in dp (from the window's container size, like the adaptive library). */
@Composable
internal fun currentShellWindowSize(): DpSize = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.toSize().toDpSize() }

/** Stateless shell for previews and screenshot tests. */
@Composable
internal fun ShellScaffold(
    state: ShellUiState,
    navigation: ShellNavigationState,
    actions: ShellActions,
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onConnectTandoor: () -> Unit,
    modifier: Modifier = Modifier,
    navigationSuiteType: NavigationSuiteType = shellNavigationSuiteType(currentShellWindowSize()),
    crashNotice: @Composable () -> Unit = {},
) {
    // The demo banner can be put away for this session; the "Demo" chip in the top bar stays.
    var demoBannerDismissed by rememberSaveable { mutableStateOf(false) }

    NavigationSuiteScaffold(
        navigationItems = {
            ShellTab.entries.forEach { tab ->
                val selected = tab == navigation.currentTab
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { navigation.selectTab(tab) },
                    icon = { Icon(imageVector = if (selected) tab.selectedIcon() else tab.icon(), contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                    navigationSuiteType = navigationSuiteType,
                )
            }
        },
        modifier = modifier.testTag(SHELL_TEST_TAG_PREFIX + navigationSuiteType),
        navigationSuiteType = navigationSuiteType,
    ) {
        val placeholder: @Composable (ShellTab) -> Unit = { tab ->
            PlaceholderTab(
                tab = tab,
                state = state,
                showDemoBanner = !demoBannerDismissed,
                onDismissDemoBanner = { demoBannerDismissed = true },
                onConnectTandoor = onConnectTandoor,
            )
        }
        val entryProvider = entryProvider<NavKey> {
            entry<TodayKey> { placeholder(ShellTab.TODAY) }
            entry<RecipesKey> { placeholder(ShellTab.RECIPES) }
            entry<WeekKey> { placeholder(ShellTab.WEEK) }
            entry<ShoppingKey> { placeholder(ShellTab.SHOPPING) }
            entry<MoreKey> {
                MoreScreen(
                    state = state,
                    actions = actions,
                    language = language,
                    onOpenLanguage = { navigation.navigate(LanguageKey) },
                    onOpenAbout = { navigation.navigate(AboutKey) },
                    onConnectTandoor = onConnectTandoor,
                    crashNotice = crashNotice,
                )
            }
            entry<LanguageKey> {
                LanguageScreen(
                    language = language,
                    onLanguageSelected = onLanguageSelected,
                    onNavigateBack = { navigation.goBack() },
                )
            }
            entry<AboutKey> {
                AboutScreen(
                    version = state.appVersion,
                    onOpenLicenses = { navigation.navigate(LicensesKey) },
                    onNavigateBack = { navigation.goBack() },
                )
            }
            entry<LicensesKey> {
                LicensesScreen(
                    onOpenLicense = { navigation.navigate(LicenseTextKey(it)) },
                    onNavigateBack = { navigation.goBack() },
                )
            }
            entry<LicenseTextKey> { key ->
                LicenseTextScreen(license = key.license, onNavigateBack = { navigation.goBack() })
            }
        }
        ShellNavDisplay(navigation = navigation, entryProvider = entryProvider)
    }
}

/**
 * Shows [navigation]'s visible entries (Today's stack, then the current tab's stack on top).
 *
 * Every tab's stack is decorated on its own and stays decorated while the shell is shown, also
 * when the tab is hidden (the Navigation 3 "multiple back stacks" pattern). A hidden tab's
 * entries are therefore still "in the back stack" for their decorators: their saved UI state
 * (scroll position, an open dialog, later a search query) is kept until the entry is really popped
 * from its own tab, instead of being thrown away on every tab switch.
 */
@Composable
internal fun ShellNavDisplay(
    navigation: ShellNavigationState,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    modifier: Modifier = Modifier,
) {
    val entriesByTab: Map<ShellTab, List<NavEntry<NavKey>>> = ShellTab.entries.associateWith { tab ->
        key(tab) {
            // Phase 1 adds the ViewModelStore decorator here, also per tab.
            val decorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>())
            rememberDecoratedNavEntries(navigation.stackOf(tab), decorators, entryProvider)
        }
    }
    val today = entriesByTab.getValue(ShellTab.TODAY)
    val entries = if (navigation.currentTab == ShellTab.TODAY) {
        today
    } else {
        today + entriesByTab.getValue(navigation.currentTab)
    }
    NavDisplay(
        entries = entries,
        modifier = modifier,
        // Calm cross-fades: tabs are siblings, not a forward/backward journey.
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        popTransitionSpec = { fadeIn() togetherWith fadeOut() },
        onBack = { navigation.goBack() },
    )
}
