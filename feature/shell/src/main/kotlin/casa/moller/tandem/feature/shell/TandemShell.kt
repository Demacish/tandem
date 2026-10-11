package casa.moller.tandem.feature.shell

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.core.ui.language.LanguageController
import casa.moller.tandem.core.ui.language.rememberLanguageController
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.feature.recipes.navigation.RecipeDetailKey
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
import casa.moller.tandem.feature.shell.navigation.RouteShellFeatures
import casa.moller.tandem.feature.shell.navigation.ShellFeatures
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
        features = RouteShellFeatures,
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

/** The status chip feature screens show next to their title ([LocalAppChrome]). */
internal val ShellUiState.appStatus: AppStatus?
    get() = when {
        isDemo -> AppStatus.Demo
        isSafeMode -> AppStatus.SafeMode
        else -> null
    }

/**
 * Stateless shell for previews and screenshot tests.
 *
 * Navigation (plan 6.1, docs/BYGGE.md "Navigering"):
 * - Every tab has its own back stack ([ShellNavigationState]); feature routes come from
 *   [features] and their callbacks become keys here.
 * - Recipes tab: the list is the list pane of a Navigation 3 list-detail scene
 *   ([ListDetailSceneStrategy]). On medium/expanded windows a recipe opened from the list shows
 *   in the detail pane beside it (a new pick replaces it); on compact windows the detail is a
 *   single pane.
 * - A [casa.moller.tandem.core.ui.navigation.FullScreenDestination] on top (recipe detail, later
 *   cook mode) hides the bottom bar on compact windows; the rail stays on wider windows unless
 *   the destination hides it on all widths.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ShellScaffold(
    state: ShellUiState,
    navigation: ShellNavigationState,
    actions: ShellActions,
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onConnectTandoor: () -> Unit,
    features: ShellFeatures,
    modifier: Modifier = Modifier,
    navigationSuiteType: NavigationSuiteType = shellNavigationSuiteType(currentShellWindowSize()),
    crashNotice: @Composable () -> Unit = {},
) {
    // The demo banner can be put away for this session; the "Demo" chip in the top bar stays.
    var demoBannerDismissed by rememberSaveable { mutableStateOf(false) }
    val showDemoBanner = state.isDemo && !demoBannerDismissed
    val chrome = remember(state.appStatus, showDemoBanner, onConnectTandoor) {
        AppChrome(
            status = state.appStatus,
            showDemoBanner = showDemoBanner,
            onConnectTandoor = onConnectTandoor,
            onDismissDemoBanner = { demoBannerDismissed = true },
        )
    }

    // Full-screen destinations (recipe detail, later cook mode) cover the tab bar on phones.
    val compactWidth = navigationSuiteType == NavigationSuiteType.ShortNavigationBarCompact
    val hideNavigation = navigation.hidesNavigation(compactWidth)
    val suiteState = rememberNavigationSuiteScaffoldState(
        if (hideNavigation) NavigationSuiteScaffoldValue.Hidden else NavigationSuiteScaffoldValue.Visible,
    )
    LaunchedEffect(suiteState, hideNavigation) {
        if (hideNavigation) suiteState.hide() else suiteState.show()
    }
    val uriHandler = LocalUriHandler.current
    val openOnWeb: (String) -> Unit = { url ->
        // No browser installed must not crash the app.
        runCatching { uriHandler.openUri(url) }
    }

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
        state = suiteState,
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
            entry<TodayKey> {
                features.Today(
                    onOpenRecipe = { navigation.navigate(RecipeDetailKey(it)) },
                    onOpenRecipes = { navigation.selectTab(ShellTab.RECIPES) },
                    onOpenWeek = { navigation.selectTab(ShellTab.WEEK) },
                )
            }
            entry<RecipesKey>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { features.EmptyRecipePane() })) {
                features.Recipes(
                    onOpenRecipe = { id ->
                        navigation.navigate(RecipeDetailKey(id, inListDetail = true), replace = { it is RecipeDetailKey })
                    },
                )
            }
            entry<RecipeDetailKey>(
                metadata = { key -> if (key.inListDetail) ListDetailSceneStrategy.detailPane() else emptyMap() },
            ) { key ->
                features.RecipeDetail(
                    recipeId = key.recipeId,
                    onBack = { navigation.goBack() },
                    // A sub-recipe opens on top, in the same pane as the recipe that links to it.
                    onOpenRecipe = { navigation.navigate(RecipeDetailKey(it, inListDetail = key.inListDetail)) },
                    onOpenOnWeb = openOnWeb,
                )
            }
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
        val listDetail = rememberListDetailSceneStrategy<NavKey>(backNavigationBehavior = BackNavigationBehavior.PopLatest)
        CompositionLocalProvider(LocalAppChrome provides chrome) {
            ShellNavDisplay(navigation = navigation, entryProvider = entryProvider, sceneStrategy = listDetail)
        }
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
    sceneStrategy: SceneStrategy<NavKey> = SinglePaneSceneStrategy(),
) {
    val entriesByTab: Map<ShellTab, List<NavEntry<NavKey>>> = ShellTab.entries.associateWith { tab ->
        key(tab) {
            // Saved UI state and a ViewModelStore per entry, per tab: a screen's ViewModel
            // (hiltViewModel()) lives while its entry is on its tab's stack, also while the tab
            // is hidden, and is cleared when the entry is popped.
            val decorators: List<NavEntryDecorator<NavKey>> = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            )
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
        sceneStrategies = listOf(sceneStrategy),
        // Calm cross-fades: tabs are siblings, not a forward/backward journey.
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        popTransitionSpec = { fadeIn() togetherWith fadeOut() },
        onBack = { navigation.goBack() },
    )
}
