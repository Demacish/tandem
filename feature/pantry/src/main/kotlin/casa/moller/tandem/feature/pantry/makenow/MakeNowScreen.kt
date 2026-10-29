package casa.moller.tandem.feature.pantry.makenow

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.pantry.expiryText
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.pantry.R
import casa.moller.tandem.feature.pantry.ui.PantryIcons

/** Test tag of the list of one group (`make-now:list:HAS_ALL`). */
fun makeNowListTag(group: MakeNowGroup): String = "make-now:list:${group.name}"

/** From this width (and [MakeNowColumnsMinHeight]) the groups stand side by side (plan §10 Fas S "Klart när"). */
internal val MakeNowColumnsWidth = 600.dp

/** From this width all three groups get a column of their own. */
internal val MakeNowThreeColumnsWidth = 1000.dp

/** Side by side needs some height for the filters above the columns (a phone in landscape keeps the tabs). */
internal val MakeNowColumnsMinHeight = 560.dp

/**
 * "Kan laga nu" (#23, #28), stateless. Phones: tabs Har allt / Saknar 1 / Saknar 2 with counts.
 * Tablets: the groups side by side (Har allt beside Saknar 1 and 2; three columns from
 * [MakeNowThreeColumnsWidth]). Above: the staples prompt the first time, "Använd upp" (what
 * expires soon), the filters (måltidstyp, nyckelord, tid) and "Överraska mig".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakeNowScreen(
    state: MakeNowUiState,
    onAction: (MakeNowAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    ReportContentReady(ready = !state.loading)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.pantry_make_now_title),
                onNavigateBack = onBack,
                actions = { AppChromeStatusChip(Modifier.padding(end = TandemTheme.spacing.md)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(MakeNowAction.Refresh) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = when {
                    maxHeight < MakeNowColumnsMinHeight || maxWidth < MakeNowColumnsWidth -> 1
                    maxWidth >= MakeNowThreeColumnsWidth -> 3
                    else -> 2
                }
                if (columns == 1) PhoneLayout(state, onAction) else ColumnsLayout(state, onAction, columns)
            }
        }
    }
    if (state.keywordPickerOpen) KeywordSheet(state = state, onAction = onAction)
}

/** One list: the header items, the tabs, then the chosen group. */
@Composable
private fun PhoneLayout(state: MakeNowUiState, onAction: (MakeNowAction) -> Unit) {
    val spacing = TandemTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(makeNowListTag(state.selected)),
        contentPadding = PaddingValues(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "header") { Header(state, onAction, Modifier.padding(horizontal = spacing.screenHorizontal)) }
        item(key = "tabs") { GroupTabs(state = state, onAction = onAction) }
        groupItems(state = state, group = state.selected, onAction = onAction, horizontal = spacing.screenHorizontal)
    }
}

/** Tablets: the header across, then the groups as columns (Har allt | Saknar 1 + 2, or one column each). */
@Composable
private fun ColumnsLayout(state: MakeNowUiState, onAction: (MakeNowAction) -> Unit, columns: Int) {
    val spacing = TandemTheme.spacing
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = spacing.xl)) {
        Header(state, onAction, Modifier.padding(vertical = spacing.sm))
        val lanes: List<List<MakeNowGroup>> = if (columns >= 3) {
            MakeNowGroup.entries.map { listOf(it) }
        } else {
            listOf(listOf(MakeNowGroup.HAS_ALL), listOf(MakeNowGroup.MISSING_1, MakeNowGroup.MISSING_2))
        }
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
            lanes.forEach { groups ->
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag(makeNowListTag(groups.first())),
                    contentPadding = PaddingValues(bottom = spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    groups.forEach { group ->
                        item(key = "heading-${group.name}") { GroupHeading(state, group, onAction) }
                        groupItems(state = state, group = group, onAction = onAction, horizontal = 0.dp)
                    }
                }
            }
        }
    }
}

/** Notices, the staples prompt, "Använd upp", the filters and "Överraska mig" (phones). */
@Composable
private fun Header(state: MakeNowUiState, onAction: (MakeNowAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth * 2),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        AppChromeDemoBanner()
        if (state.stale) {
            InfoCard(
                title = stringResource(R.string.pantry_make_now_stale_title),
                body = stringResource(R.string.pantry_make_now_stale_body),
                icon = TandemIcons.CloudOff,
            )
        }
        if (state.staplesPrompt) {
            CalloutCard(
                title = stringResource(R.string.pantry_staples_prompt_title),
                body = stringResource(R.string.pantry_staples_prompt_body),
                tone = TandemTone.Accent,
                onDismiss = { onAction(MakeNowAction.DismissStaples) },
                actions = {
                    TandemTextButton(
                        text = stringResource(R.string.pantry_staples_prompt_open),
                        onClick = { onAction(MakeNowAction.OpenStaples) },
                    )
                    TandemTextButton(
                        text = stringResource(R.string.pantry_staples_not_now),
                        onClick = { onAction(MakeNowAction.DismissStaples) },
                    )
                },
            )
        }
        UseUpLine(state = state)
        Filters(state = state, onAction = onAction)
    }
}

/** "Använd upp: grädde går ut i morgon, mjölk går ut om 3 dagar" (the recipes using them come first). */
@Composable
private fun UseUpLine(state: MakeNowUiState) {
    val today = state.today ?: return
    if (state.expiring.isEmpty()) return
    val locale = currentLocale()
    val items = state.expiring.take(MAX_USE_UP).joinToString(", ") { entry ->
        val day = entry.expires
        val name = foodTitle(entry.food.name, locale)
        if (day != null) "$name ${expiryText(day, today)}" else name
    }
    InfoCard(
        title = stringResource(R.string.pantry_use_up_title),
        body = stringResource(R.string.pantry_use_up_body, items),
        icon = TandemIcons.Schedule,
        tone = TandemTone.Warning,
    )
}

private const val MAX_USE_UP = 4

/** Meal type, keywords and time, and clearing them. */
@Composable
private fun Filters(state: MakeNowUiState, onAction: (MakeNowAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.mealTypes.isNotEmpty()) MealTypeChip(state = state, onAction = onAction)
        if (state.keywords.isNotEmpty()) {
            val count = state.filter.keywordIds.size
            FilterChip(
                selected = count > 0,
                onClick = { onAction(MakeNowAction.OpenKeywords) },
                label = {
                    Text(
                        if (count > 0) {
                            pluralStringResource(R.plurals.pantry_make_now_keywords_chosen, count, count)
                        } else {
                            stringResource(R.string.pantry_make_now_keywords)
                        },
                    )
                },
                trailingIcon = { Icon(TandemIcons.ExpandMore, contentDescription = null) },
            )
        }
        TimeFilter.entries.filter { it != TimeFilter.ANY }.forEach { time ->
            FilterChip(
                selected = state.time == time,
                onClick = { onAction(MakeNowAction.SetTime(if (state.time == time) TimeFilter.ANY else time)) },
                label = { Text(stringResource(R.string.pantry_make_now_time, time.maxMinutes ?: 0)) },
            )
        }
        if (state.filtered) {
            TandemTextButton(
                text = stringResource(R.string.pantry_make_now_clear_filters),
                onClick = { onAction(MakeNowAction.ClearFilters) },
            )
        }
    }
}

@Composable
private fun MealTypeChip(state: MakeNowUiState, onAction: (MakeNowAction) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val chosen = state.mealTypes.firstOrNull { it.id == state.filter.mealTypeId }
    Column {
        FilterChip(
            selected = chosen != null,
            onClick = { open = true },
            label = { Text(chosen?.name ?: stringResource(R.string.pantry_make_now_meal_type)) },
            trailingIcon = { Icon(TandemIcons.ExpandMore, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pantry_make_now_meal_type_any)) },
                onClick = {
                    open = false
                    onAction(MakeNowAction.SetMealType(null))
                },
            )
            state.mealTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.name) },
                    onClick = {
                        open = false
                        onAction(MakeNowAction.SetMealType(type.id))
                    },
                )
            }
        }
    }
}

/** The phone's tabs with how many recipes each group has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupTabs(state: MakeNowUiState, onAction: (MakeNowAction) -> Unit) {
    PrimaryTabRow(selectedTabIndex = state.selected.ordinal, containerColor = MaterialTheme.colorScheme.background) {
        MakeNowGroup.entries.forEach { group ->
            Tab(
                selected = state.selected == group,
                onClick = { onAction(MakeNowAction.SelectGroup(group)) },
                text = { Text(groupTitle(group, state.rows(group).size)) },
            )
        }
    }
}

/** A column's heading on tablets: the group, its count and "Överraska mig". */
@Composable
private fun GroupHeading(state: MakeNowUiState, group: MakeNowGroup, onAction: (MakeNowAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
        Text(
            text = groupTitle(group, state.rows(group).size),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(groupHint(group)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.rows(group).size > 1) SurpriseButton(state = state, group = group, onAction = onAction)
    }
}

/** The rows of [group] (or why it is empty), with "Överraska mig" first on phones. */
private fun LazyListScope.groupItems(
    state: MakeNowUiState,
    group: MakeNowGroup,
    onAction: (MakeNowAction) -> Unit,
    horizontal: Dp,
) {
    val rows = state.rows(group)
    val phone = horizontal > 0.dp
    if (phone) {
        item(key = "hint-${group.name}") {
            Column(
                modifier = Modifier.padding(horizontal = horizontal),
                verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
            ) {
                Text(
                    text = stringResource(groupHint(group)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rows.size > 1) SurpriseButton(state = state, group = group, onAction = onAction)
            }
        }
    }
    if (rows.isEmpty()) {
        item(key = "empty-${group.name}") {
            EmptyGroup(state = state, group = group, onAction = onAction, modifier = Modifier.padding(horizontal = horizontal))
        }
        return
    }
    items(rows, key = { "${group.name}-${it.recipe.id}" }) { row ->
        MakeNowRecipeRow(
            row = row,
            today = state.today,
            canAddToList = !state.readOnly,
            onAction = onAction,
            modifier = Modifier.padding(horizontal = horizontal),
        )
    }
}

@Composable
private fun SurpriseButton(state: MakeNowUiState, group: MakeNowGroup, onAction: (MakeNowAction) -> Unit) {
    TandemSecondaryButton(
        text = stringResource(R.string.pantry_make_now_surprise),
        onClick = { onAction(MakeNowAction.Surprise(group)) },
        enabled = !state.surprising,
        leadingIcon = PantryIcons.Surprise,
    )
}

@Composable
private fun EmptyGroup(state: MakeNowUiState, group: MakeNowGroup, onAction: (MakeNowAction) -> Unit, modifier: Modifier = Modifier) {
    val (title, body) = when {
        state.filtered -> R.string.pantry_make_now_empty_filtered_title to R.string.pantry_make_now_empty_filtered_body
        group == MakeNowGroup.HAS_ALL -> R.string.pantry_make_now_empty_all_title to R.string.pantry_make_now_empty_all_body
        else -> R.string.pantry_make_now_empty_missing_title to R.string.pantry_make_now_empty_missing_body
    }
    EmptyState(icon = TandemIcons.Restaurant, title = stringResource(title), body = stringResource(body), modifier = modifier) {
        if (state.filtered) {
            TandemTextButton(
                text = stringResource(R.string.pantry_make_now_clear_filters),
                onClick = { onAction(MakeNowAction.ClearFilters) },
            )
        } else if (group == MakeNowGroup.HAS_ALL && !state.readOnly) {
            TandemTextButton(text = stringResource(R.string.pantry_staples_prompt_open), onClick = { onAction(MakeNowAction.OpenStaples) })
        }
    }
}

/** "Har allt (3)", "Saknar 1 (5)", "Saknar 2 (2)". */
@Composable
internal fun groupTitle(group: MakeNowGroup, count: Int): String = stringResource(
    when (group) {
        MakeNowGroup.HAS_ALL -> R.string.pantry_make_now_has_all
        MakeNowGroup.MISSING_1 -> R.string.pantry_make_now_missing_1
        MakeNowGroup.MISSING_2 -> R.string.pantry_make_now_missing_2
    },
    count,
)

private fun groupHint(group: MakeNowGroup): Int = when (group) {
    MakeNowGroup.HAS_ALL -> R.string.pantry_make_now_has_all_hint
    MakeNowGroup.MISSING_1 -> R.string.pantry_make_now_missing_1_hint
    MakeNowGroup.MISSING_2 -> R.string.pantry_make_now_missing_2_hint
}
