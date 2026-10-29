package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.MoreVert
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.WineBar
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.feature.recipes.R

/** Test tag of the drinks list. */
const val DRINKS_LIST_TAG: String = "drinks_list"

/**
 * "Drycker" (#43, plan §8.8; no Stitch screen, the recipe list's patterns). Stateless: [state]
 * in, [onAction] out. A search field, the types as filter chips, and the drinks as rows (two
 * columns on wide windows, the open drink highlighted beside the list). "Ny dryck" is the FAB
 * (never for a guest); the menu holds the "Sök på Systembolaget" switch.
 *
 * @param onBack null hides the back arrow.
 * @param selectedDrinkId the drink open beside the list (wide windows), null when none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrinksScreen(
    state: DrinksUiState,
    onAction: (DrinksAction) -> Unit,
    onOpenDrink: (Long) -> Unit,
    onCreateDrink: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selectedDrinkId: Long? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.drinks_title),
                onNavigateBack = onBack,
                actions = {
                    AppChromeStatusChip(Modifier.padding(end = TandemTheme.spacing.xs))
                    SettingsMenu(state = state, onAction = onAction)
                },
            )
        },
        floatingActionButton = {
            if (!state.readOnly && !state.loading) {
                ExtendedFloatingActionButton(
                    onClick = onCreateDrink,
                    icon = { Icon(TandemIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.drinks_new)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        if (state.loading) {
            LoadingState(modifier = contentModifier)
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { onAction(DrinksAction.Refresh) }, modifier = contentModifier) {
            DrinkGrid(state = state, onAction = onAction, onOpenDrink = onOpenDrink, selectedDrinkId = selectedDrinkId)
        }
    }
}

@Composable
private fun DrinkGrid(state: DrinksUiState, onAction: (DrinksAction) -> Unit, onOpenDrink: (Long) -> Unit, selectedDrinkId: Long?) {
    val spacing = TandemTheme.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(ROW_MIN_CELL),
        modifier = Modifier.fillMaxSize().testTag(DRINKS_LIST_TAG),
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = FAB_CLEARANCE),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        full("demo") { AppChromeDemoBanner() }
        if (state.readOnly) full("guest") { ReadOnlyBanner(compact = true) }
        if (state.totalCount > 0) {
            full("search") {
                DrinkSearchField(
                    text = state.query,
                    onTextChange = { onAction(DrinksAction.Search(it)) },
                    hint = stringResource(R.string.drinks_search),
                )
            }
            full("types") { TypeChips(state = state, onAction = onAction) }
        }
        when {
            state.totalCount == 0 -> full("empty") {
                EmptyState(
                    icon = TandemIcons.WineBar,
                    title = stringResource(R.string.drinks_empty_title),
                    body = stringResource(if (state.readOnly) R.string.drinks_empty_guest_body else R.string.drinks_empty_body),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.drinks.isEmpty() -> full("no-match") {
                EmptyState(
                    icon = TandemIcons.WineBar,
                    title = stringResource(R.string.drinks_no_match_title),
                    body = stringResource(R.string.drinks_no_match_body),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> items(state.drinks, key = { it.id }) { drink ->
                DrinkRow(
                    drink = drink,
                    selected = drink.id == selectedDrinkId,
                    onClick = { if (drink.pending) onAction(DrinksAction.OpenPending) else onOpenDrink(drink.id) },
                )
            }
        }
    }
}

/** "Alla" and one chip per type; the chosen one is filled and checked (not colour alone). */
@Composable
private fun TypeChips(state: DrinksUiState, onAction: (DrinksAction) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs),
    ) {
        TypeChip(label = stringResource(R.string.drinks_filter_all), selected = state.selectedType == null) {
            onAction(DrinksAction.SelectType(null))
        }
        state.types.forEach { type ->
            val label = drinkTypeLabel(type)
            val description = stringResource(R.string.drinks_filter_label, label)
            TypeChip(label = label, selected = state.selectedType == type, description = description) {
                onAction(DrinksAction.SelectType(if (state.selectedType == type) null else type))
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(TandemIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}

/** The overflow menu: "Visa Sök på Systembolaget" (plan §8.8, on by default for a Swedish UI). */
@Composable
private fun SettingsMenu(state: DrinksUiState, onAction: (DrinksAction) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(TandemIcons.MoreVert, contentDescription = stringResource(R.string.drinks_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                modifier = Modifier
                    .widthIn(max = MENU_MAX_WIDTH)
                    .toggleable(
                        value = state.systembolagetShown,
                        role = Role.Switch,
                        onValueChange = { onAction(DrinksAction.SetSystembolaget(it)) },
                    )
                    .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
                    Text(stringResource(R.string.drinks_setting_systembolaget), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.drinks_setting_systembolaget_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.systembolagetShown, onCheckedChange = null)
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private val ROW_MIN_CELL = 360.dp
private val FAB_CLEARANCE = 96.dp
private val MENU_MAX_WIDTH = 320.dp
