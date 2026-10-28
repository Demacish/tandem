package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.household.HouseholdMissingCard
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
        topBar = { TandemTopAppBar(title = stringResource(R.string.mealplan_title), actions = { AppChromeStatusChip() }) },
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
                        // 0.3.0 review (fix-data, plan 8.6): nothing in this plan is shared without a household.
                        if (state.householdMissing) {
                            val spacing = TandemTheme.spacing
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
