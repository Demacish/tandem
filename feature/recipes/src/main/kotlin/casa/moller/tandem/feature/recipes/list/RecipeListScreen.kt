package casa.moller.tandem.feature.recipes.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.DownloadForOffline
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.GridView
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.Sell
import casa.moller.tandem.core.designsystem.icon.Sort
import casa.moller.tandem.core.designsystem.icon.StarFilled
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.ViewAgenda
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.ErrorState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.recipe.IconLabel
import casa.moller.tandem.core.ui.recipe.RecipeCard
import casa.moller.tandem.core.ui.recipe.RecipeListItem
import casa.moller.tandem.feature.recipes.R

/**
 * The recipe list (plan 6.2 "Receptlista", designs recept_alla_recept and
 * recept_s_k_filter_samlingar with the corrections in docs/design/README.md). Stateless.
 *
 * Search field, filter chips (keywords, under 30 min, rating 4+, only offline) and sort above a
 * grid of photo cards or a list of rows; pull to refresh syncs. Offline, recipes without a
 * stored detail are dimmed with a cloud badge.
 *
 * The keyword picker is a bottom sheet on compact and medium windows and a side panel on
 * expanded ones (plan 6.5): beside the recipes when the list is wide enough, otherwise in their
 * place inside the list pane, so the recipe open beside the list stays visible. The content
 * keeps clear of the keyboard (edge-to-edge: the window is not resized for it).
 *
 * @param selectedRecipeId the recipe open in the detail pane beside the list (wide windows), highlighted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(
    state: RecipeListUiState,
    onAction: (RecipeListAction) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selectedRecipeId: Long? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showKeywordPicker by rememberSaveable { mutableStateOf(false) }
    val keywordPanel = isExpandedWindow()
    // Hoisted, so the scroll position survives while the side panel replaces the grid.
    val gridState = rememberLazyGridState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.recipes_title),
                subtitle = if (state.loaded && !state.isFiltered && state.recipes.isNotEmpty()) {
                    pluralStringResource(R.plurals.recipes_count, state.recipes.size, state.recipes.size)
                } else {
                    null
                },
                actions = {
                    AppChromeStatusChip(Modifier.padding(end = TandemTheme.spacing.xs))
                    LayoutToggle(layout = state.layout, onToggle = { onAction(RecipeListAction.ToggleLayout) })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        // The keyboard as part of the content insets: the last results can scroll above it and
        // snackbars appear above it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            val panelOpen = keywordPanel && showKeywordPicker
            val panelBeside = maxWidth >= PANEL_BESIDE_MIN_WIDTH
            Row(modifier = Modifier.fillMaxSize()) {
                if (panelOpen) {
                    KeywordPickerPanel(
                        keywords = state.keywords,
                        selectedIds = state.query.keywordIds,
                        onToggle = { onAction(RecipeListAction.ToggleKeyword(it)) },
                        onClear = { onAction(RecipeListAction.ClearKeywords) },
                        onDismiss = { showKeywordPicker = false },
                        modifier = Modifier
                            .then(if (panelBeside) Modifier.width(PANEL_WIDTH) else Modifier.weight(1f))
                            .fillMaxHeight()
                            .padding(start = TandemTheme.spacing.sm, end = TandemTheme.spacing.sm, bottom = TandemTheme.spacing.sm),
                    )
                }
                if (!panelOpen || panelBeside) {
                    PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = { onAction(RecipeListAction.Refresh) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        RecipeGrid(
                            state = state,
                            gridState = gridState,
                            onAction = onAction,
                            onOpenRecipe = onOpenRecipe,
                            onOpenKeywordPicker = { showKeywordPicker = !(keywordPanel && showKeywordPicker) },
                            selectedRecipeId = selectedRecipeId,
                        )
                    }
                }
            }
        }
    }
    if (showKeywordPicker && !keywordPanel) {
        KeywordPickerSheet(
            keywords = state.keywords,
            selectedIds = state.query.keywordIds,
            onToggle = { onAction(RecipeListAction.ToggleKeyword(it)) },
            onClear = { onAction(RecipeListAction.ClearKeywords) },
            onDismiss = { showKeywordPicker = false },
        )
    }
}

@Composable
private fun LayoutToggle(layout: RecipeListLayout, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        if (layout == RecipeListLayout.GRID) {
            Icon(imageVector = TandemIcons.ViewAgenda, contentDescription = stringResource(R.string.recipes_show_as_list))
        } else {
            Icon(imageVector = TandemIcons.GridView, contentDescription = stringResource(R.string.recipes_show_as_grid))
        }
    }
}

/** Expanded window (at least 840 dp wide): filters open as a side panel, not a bottom sheet. */
@Composable
private fun isExpandedWindow(): Boolean {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return width >= EXPANDED_WINDOW_MIN_WIDTH
}

@Composable
private fun RecipeGrid(
    state: RecipeListUiState,
    gridState: LazyGridState,
    onAction: (RecipeListAction) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onOpenKeywordPicker: () -> Unit,
    selectedRecipeId: Long?,
) {
    val spacing = TandemTheme.spacing
    val columns = if (state.layout == RecipeListLayout.GRID) GridCells.Adaptive(GRID_MIN_CELL) else GridCells.Adaptive(LIST_MIN_CELL)
    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, bottom = spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        fullWidth("demo") { AppChromeDemoBanner(Modifier.padding(top = spacing.sm)) }
        fullWidth("search") {
            SearchField(text = state.query.text, onTextChange = { onAction(RecipeListAction.Search(it)) })
        }
        fullWidth("filters") { FilterChips(state = state, onAction = onAction, onOpenKeywordPicker = onOpenKeywordPicker) }
        fullWidth("status") { StatusLine(state) }
        when {
            !state.loaded -> fullWidth("loading") { LoadingState() }

            state.recipes.isEmpty() -> fullWidth("empty") { EmptyContent(state = state, onAction = onAction) }

            else -> items(state.recipes, key = { it.id }) { recipe ->
                if (state.layout == RecipeListLayout.GRID) {
                    RecipeCard(
                        recipe = recipe,
                        onClick = { onOpenRecipe(recipe.id) },
                        dimmed = state.isDimmed(recipe),
                        selected = recipe.id == selectedRecipeId,
                    )
                } else {
                    RecipeListItem(
                        recipe = recipe,
                        onClick = { onOpenRecipe(recipe.id) },
                        dimmed = state.isDimmed(recipe),
                        selected = recipe.id == selectedRecipeId,
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SearchField(text: String, onTextChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TandemTheme.spacing.sm),
        placeholder = { Text(stringResource(R.string.recipes_search_hint)) },
        leadingIcon = { Icon(imageVector = TandemIcons.Search, contentDescription = null) },
        trailingIcon = if (text.isNotEmpty()) {
            {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(imageVector = TandemIcons.Close, contentDescription = stringResource(R.string.recipes_search_clear))
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun FilterChips(state: RecipeListUiState, onAction: (RecipeListAction) -> Unit, onOpenKeywordPicker: () -> Unit) {
    val query = state.query
    val selectedKeywords = state.selectedKeywords
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(
            label = when (selectedKeywords.size) {
                0 -> stringResource(R.string.recipes_filter_keywords)
                1 -> selectedKeywords.single().name
                else -> pluralStringResource(R.plurals.recipes_filter_keywords_count, selectedKeywords.size, selectedKeywords.size)
            },
            selected = query.keywordIds.isNotEmpty(),
            onClick = onOpenKeywordPicker,
            icon = TandemIcons.Sell,
            trailingIcon = TandemIcons.ExpandMore,
        )
        Chip(
            label = stringResource(R.string.recipes_filter_quick, QUICK_MAX_MINUTES),
            selected = query.maxTotalMinutes != null,
            onClick = { onAction(RecipeListAction.ToggleQuick) },
            icon = TandemIcons.Schedule,
        )
        Chip(
            label = stringResource(R.string.recipes_filter_rating, GOOD_MIN_RATING.toInt()),
            selected = query.minRating != null,
            onClick = { onAction(RecipeListAction.ToggleGoodRating) },
            icon = TandemIcons.StarFilled,
        )
        Chip(
            label = stringResource(R.string.recipes_filter_offline),
            selected = query.onlyOffline,
            onClick = { onAction(RecipeListAction.ToggleOnlyOffline) },
            icon = TandemIcons.DownloadForOffline,
        )
        SortChip(sort = query.sort, onSort = { onAction(RecipeListAction.Sort(it)) })
    }
}

/** A filter chip: its icon turns into a check mark while selected (state is not shown by colour alone). */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector, trailingIcon: ImageVector? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { ChipIcon(if (selected) TandemIcons.Check else icon) },
        trailingIcon = if (trailingIcon != null) ({ ChipIcon(trailingIcon) }) else null,
        modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
    )
}

@Composable
private fun ChipIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
}

@Composable
private fun SortChip(sort: RecipeSort, onSort: (RecipeSort) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(stringResource(R.string.recipes_sort_label, stringResource(sort.label())), maxLines = 1) },
            leadingIcon = { ChipIcon(TandemIcons.Sort) },
            trailingIcon = { ChipIcon(TandemIcons.ExpandMore) },
            modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ListSortOrders.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    trailingIcon = if (option == sort) ({ Icon(TandemIcons.Check, contentDescription = null) }) else null,
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
        }
    }
}

private fun RecipeSort.label(): Int = when (this) {
    RecipeSort.NAME -> R.string.recipes_sort_name
    RecipeSort.NEWEST -> R.string.recipes_sort_newest
    RecipeSort.RATING -> R.string.recipes_sort_rating
    RecipeSort.LAST_COOKED -> R.string.recipes_sort_last_cooked
    RecipeSort.TIMES_COOKED -> R.string.recipes_sort_times_cooked
    RecipeSort.TOTAL_TIME -> R.string.recipes_sort_total_time
}

/** Result count and what the server search is doing ("Searching Tandoor…", offline hint). */
@Composable
private fun StatusLine(state: RecipeListUiState) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isFiltered && state.loaded) {
            Text(
                text = pluralStringResource(R.plurals.recipes_result_count, state.recipes.size, state.recipes.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(Modifier.weight(1f))
        }
        when (state.serverSearch) {
            ServerSearch.SEARCHING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.recipes_searching_server),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ServerSearch.OFFLINE -> IconLabel(icon = TandemIcons.CloudOff, text = stringResource(R.string.recipes_search_offline))

            ServerSearch.FAILED -> IconLabel(icon = TandemIcons.Warning, text = stringResource(R.string.recipes_search_failed))

            ServerSearch.DONE -> if (state.serverOnlyCount > 0) {
                IconLabel(
                    icon = TandemIcons.Dns,
                    text = pluralStringResource(R.plurals.recipes_from_server, state.serverOnlyCount, state.serverOnlyCount),
                )
            }

            ServerSearch.IDLE -> if (state.offline && !state.isFiltered) {
                IconLabel(icon = TandemIcons.CloudOff, text = stringResource(R.string.recipes_offline_hint))
            }
        }
    }
}

/** Empty states (docs/design/README.md "Tomma lägen"): no recipes yet, offline, nothing matches. */
@Composable
private fun EmptyContent(state: RecipeListUiState, onAction: (RecipeListAction) -> Unit) {
    when {
        state.isFiltered && state.serverSearch == ServerSearch.SEARCHING ->
            LoadingState(message = stringResource(R.string.recipes_searching_server))

        state.isFiltered -> EmptyState(
            icon = TandemIcons.Search,
            title = stringResource(R.string.recipes_empty_no_match_title),
            body = stringResource(
                if (state.offline) R.string.recipes_empty_no_match_offline_body else R.string.recipes_empty_no_match_body,
            ),
        ) {
            TandemSecondaryButton(
                text = stringResource(R.string.recipes_clear_filters),
                onClick = { onAction(RecipeListAction.ClearFilters) },
                leadingIcon = TandemIcons.Close,
            )
        }

        state.syncing -> LoadingState(message = stringResource(R.string.recipes_loading_first_sync))

        state.offline -> EmptyState(
            icon = TandemIcons.CloudOff,
            title = stringResource(R.string.recipes_empty_offline_title),
            body = stringResource(R.string.recipes_empty_offline_body),
        )

        // The sync failed (blocked, server error, unexpected answer): say why instead of
        // "No recipes yet", which is wrong for a library that could not be read (plan 5.4).
        state.syncError != null -> ErrorState(error = state.syncError, onRetry = { onAction(RecipeListAction.Refresh) })

        else -> EmptyState(
            icon = TandemIcons.MenuBook,
            title = stringResource(R.string.recipes_empty_title),
            body = stringResource(R.string.recipes_empty_body),
        ) {
            TandemPrimaryButton(
                text = stringResource(R.string.recipes_refresh),
                onClick = { onAction(RecipeListAction.Refresh) },
                leadingIcon = TandemIcons.Refresh,
            )
        }
    }
}

/** Window width from which filters open as a side panel (Material's expanded width class). */
private val EXPANDED_WINDOW_MIN_WIDTH = 840.dp

/** The list itself is wide enough for the side panel beside the recipes. */
private val PANEL_BESIDE_MIN_WIDTH = 640.dp
private val PANEL_WIDTH = 320.dp

/** Two columns on phones and in the 360 dp list pane beside a recipe, more on wide windows. */
private val GRID_MIN_CELL = 150.dp
private val LIST_MIN_CELL = 340.dp
