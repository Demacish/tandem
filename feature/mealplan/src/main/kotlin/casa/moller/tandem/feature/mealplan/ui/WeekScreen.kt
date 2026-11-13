package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.MoreVert
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.household.HouseholdMissingCard
import casa.moller.tandem.core.ui.household.SharedWithRow
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.WeekAction
import casa.moller.tandem.feature.mealplan.WeekUiState

/** From this width the week is a list of days beside the chosen day (plan 6.5, list-detail). */
internal val TwoPaneMinWidth = 600.dp

/** From this size the week is a grid: days as columns, meal types as rows (plan 6.5, Stitch veckan_matsedel_surfplatta). */
internal val GridMinWidth = 840.dp
internal val GridMinHeight = 560.dp

/**
 * A window taller than wide gets the grid from this width (fix-ui, plan §10 "veckorutnätet … i
 * båda orienteringarna"): a tablet in portrait, about 700–800 dp wide beside the rail. The grid
 * scrolls sideways there.
 */
internal val PortraitGridMinWidth = 680.dp

/** How the week is laid out for the window it gets (plan 6.5). */
internal enum class WeekLayout {
    /** Phones in portrait: one column, a section per day (Stitch veckan_matsedel). */
    AGENDA,

    /** Phones in landscape (too low for the grid), narrow tablets: the days beside the chosen day. */
    TWO_PANE,

    /** Tablets in both orientations: the week grid. */
    GRID,
}

/**
 * The Veckan tab (plan 6.2 "Veckan", 6.5; Stitch veckan_matsedel, veckan_matsedel_m_rkt_l_ge,
 * veckan_matsedel_surfplatta with the corrections of docs/design/README.md: no clipped Sunday,
 * no image placeholders without a recipe), stateless.
 *
 * - **Header:** week number and dates, previous/next week, "Idag" outside this week, how many days
 *   are planned. Pull to refresh reads the plan again.
 * - **Layouts** ([WeekLayout]): the agenda (today first), two panes, or the week grid (tablets in
 *   both orientations, fix-ui).
 * - **Meals:** tapping opens the recipe; the menu has "Laga" (today), "Ändra", "Flytta" and "Ta
 *   bort" (TalkBack: custom actions). "Flytta" (also a long press, fix-ui) opens a sheet with days
 *   and meal types; "Ta bort" asks first when the meal has rows on the shopping list.
 * - **States:** empty week, guest (#42: no plan/change controls at all), demo (the shell's banner),
 *   offline (the status chip; changes wait and show "väntar på att synkas"), a conflict dialog.
 * - **Fas 4:** "Föreslå veckans matsedel" (a card after the days) opens a sheet with a recipe per
 *   free day ([WeekSuggestionsPanel]); "Lägg i veckan" plans them with "Ångra".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(
    state: WeekUiState,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    ReportContentReady(ready = !state.loading)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { MealPlanFormat(locale) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.mealplan_title),
                actions = {
                    AppChromeStatusChip()
                    // Fas 5 (kalender lane, plan §7 #36): "Matsedeln i kalendern".
                    WeekMenu(onAction = onAction)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { onAction(WeekAction.Refresh) },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val portraitGrid = maxHeight > maxWidth && maxWidth >= PortraitGridMinWidth
                    val layout = when {
                        maxHeight >= GridMinHeight && (maxWidth >= GridMinWidth || portraitGrid) -> WeekLayout.GRID
                        maxWidth >= TwoPaneMinWidth -> WeekLayout.TWO_PANE
                        else -> WeekLayout.AGENDA
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        AppChromeDemoBanner()
                        // 0.3.0 komplettering (plan 8.6): who else sees this plan.
                        val spacing = TandemTheme.spacing
                        SharedWithRow(
                            names = state.sharedWith,
                            modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.xs),
                        )
                        // 0.3.0 review (fix-data, plan 8.6): nothing in this plan is shared without a household.
                        if (state.householdMissing) {
                            HouseholdMissingCard(modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm))
                        }
                        val content = Modifier.weight(1f)
                        when (layout) {
                            WeekLayout.AGENDA -> WeekAgenda(state = state, format = format, onAction = onAction, modifier = content)
                            WeekLayout.TWO_PANE -> WeekTwoPane(state = state, format = format, onAction = onAction, modifier = content)
                            WeekLayout.GRID -> WeekGrid(state = state, format = format, onAction = onAction, modifier = content)
                        }
                    }
                }
            }
        }
    }
    val move = state.move
    if (move != null) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(onDismissRequest = { onAction(WeekAction.CancelMove) }, sheetState = sheetState) {
            MoveMealPanel(
                state = move,
                mealTypes = state.mealTypes,
                today = state.today,
                format = format,
                onAction = onAction,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TandemTheme.spacing.screenHorizontal)
                    .padding(bottom = TandemTheme.spacing.xl),
            )
        }
    }
    // Fas 4: "Föreslå veckans matsedel".
    val suggestions = state.suggestions
    if (suggestions != null) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(onDismissRequest = { onAction(WeekAction.CancelSuggestions) }, sheetState = sheetState) {
            // fas4-kvar: the buttons stay below the scrolling content (WeekSuggestionsSheetLayout).
            WeekSuggestionsSheetLayout(
                state = suggestions,
                format = format,
                onAction = onAction,
                modifier = Modifier
                    .padding(horizontal = TandemTheme.spacing.screenHorizontal)
                    .padding(bottom = TandemTheme.spacing.xl),
            )
        }
    }
    state.conflict?.let { conflict -> ConflictDialog(state = conflict, format = format, onAction = onAction) }
    state.deleting?.let { deleting ->
        DeleteMealDialog(
            mealTitle = deleting.meal.displayTitle,
            rowsOnList = deleting.rows,
            onConfirm = { onAction(WeekAction.ConfirmDelete(true)) },
            onDismiss = { onAction(WeekAction.ConfirmDelete(false)) },
        )
    }
}

/** Test tag of Veckan's menu button. */
const val WEEK_MENU_TAG = "mealplan:week:menu"

/** Fas 5 (kalender lane, plan §7 #36): Veckan's menu ("⋮"): "Matsedeln i kalendern". */
@Composable
private fun WeekMenu(onAction: (WeekAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(WEEK_MENU_TAG)) {
            Icon(imageVector = TandemIcons.MoreVert, contentDescription = stringResource(R.string.calendar_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.calendar_menu)) },
                leadingIcon = { Icon(imageVector = TandemIcons.CalendarMonth, contentDescription = null) },
                onClick = {
                    open = false
                    onAction(WeekAction.OpenCalendar)
                },
            )
        }
    }
}
