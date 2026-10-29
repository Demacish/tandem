package casa.moller.tandem.feature.pantry.unpack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.pantry.formatPantryAmount
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.pantry.R
import casa.moller.tandem.feature.pantry.entry.expiryChoiceLabel
import casa.moller.tandem.feature.pantry.ui.PantryCard
import casa.moller.tandem.feature.pantry.ui.PantryIcons

/** Test tag of "Lägg in n varor". */
const val UNPACK_SAVE_TAG = "unpack:save"

/** Test tag of one item's row. */
fun unpackRowTag(id: Long): String = "unpack:row:$id"

/**
 * "Packa upp" (#46), stateless: a place and a day for all items at the top, then each item (tick
 * it off to skip it, "Ändra" for its own place and day); "Lägg in n varor" and "Hoppa över" stay
 * pinned below the list. Empty: explains that items checked in Handla today show up here.
 */
@Composable
fun UnpackScreen(
    state: UnpackUiState,
    onAction: (UnpackAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.pantry_unpack_title),
                onNavigateBack = { onAction(UnpackAction.Close) },
            )
        },
        bottomBar = { if (!state.loading && state.rows.isNotEmpty() && !state.readOnly) UnpackActions(state = state, onAction = onAction) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))

            state.rows.isEmpty() -> EmptyState(
                icon = PantryIcons.Unpack,
                title = stringResource(R.string.pantry_unpack_empty_title),
                body = stringResource(R.string.pantry_unpack_empty_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> UnpackList(state = state, onAction = onAction, contentPadding = padding)
        }
    }
}

@Composable
private fun UnpackList(state: UnpackUiState, onAction: (UnpackAction) -> Unit, contentPadding: PaddingValues) {
    val spacing = TandemTheme.spacing
    val width = Modifier
        .widthIn(max = TandemTheme.sizes.maxReadingWidth)
        .fillMaxWidth()
        .padding(horizontal = spacing.screenHorizontal)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + spacing.sm,
            bottom = contentPadding.calculateBottomPadding() + spacing.xl,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "intro") {
            Column(modifier = width, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                if (state.readOnly) {
                    InfoCard(
                        title = stringResource(R.string.pantry_guest_title),
                        body = stringResource(R.string.pantry_guest_body),
                        icon = TandemIcons.Lock,
                    )
                }
                Text(
                    text = pluralStringResource(R.plurals.pantry_unpack_intro, state.rows.size, state.rows.size),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.locations.isEmpty()) {
                    InfoCard(
                        title = stringResource(R.string.pantry_unpack_no_locations_title),
                        body = stringResource(R.string.pantry_unpack_no_locations_body),
                        icon = PantryIcons.Kitchen,
                    )
                } else if (!state.readOnly) {
                    AllItemsCard(state = state, onAction = onAction)
                }
            }
        }
        item(key = "items-heading") {
            Text(
                text = stringResource(R.string.pantry_unpack_items),
                style = MaterialTheme.typography.titleMedium,
                modifier = width.semantics { heading() },
            )
        }
        items(state.rows, key = { it.item.shoppingEntryId }) { row ->
            UnpackItemRow(row = row, state = state, onAction = onAction, modifier = width)
        }
    }
}

/** "För alla": one place and one day for every item. */
@Composable
private fun AllItemsCard(state: UnpackUiState, onAction: (UnpackAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.pantry_unpack_all),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            LocationChips(
                locations = state.locations,
                selected = state.allLocationId,
                onPick = { onAction(UnpackAction.AllLocation(it)) },
            )
            ExpiryChips(choices = state.allExpiryChoices, selected = state.allExpiry, onPick = { onAction(UnpackAction.AllExpiry(it)) })
        }
    }
}

/** One item: ticked = goes in the pantry; its place and day, "Ändra" for its own choice. */
@Composable
private fun UnpackItemRow(row: UnpackRow, state: UnpackUiState, onAction: (UnpackAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val locale = currentLocale()
    val id = row.item.shoppingEntryId
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val place = state.locations.firstOrNull { it.id == row.locationId }?.name
    val summary = listOfNotNull(place, expiryChoiceLabel(row.expiry).takeIf { row.expiry != ExpiryChoice.NONE })
        .ifEmpty { listOf(stringResource(R.string.pantry_unpack_no_place)) }
        .joinToString(" · ")
    PantryCard(modifier = modifier.testTag(unpackRowTag(id))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                .toggleable(
                    value = row.include,
                    role = Role.Checkbox,
                    enabled = !state.readOnly,
                    onValueChange = { onAction(UnpackAction.ToggleItem(id)) },
                )
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Checkbox(checked = row.include, onCheckedChange = null, enabled = !state.readOnly)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = foodTitle(row.item.food.name, locale), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = formatPantryAmount(row.item.amount, row.item.unit?.name, locale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (row.include) summary else stringResource(R.string.pantry_unpack_skipped),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (row.include && !state.readOnly && state.locations.isNotEmpty()) {
            TandemTextButton(
                text = stringResource(if (expanded) R.string.pantry_unpack_item_done else R.string.pantry_unpack_item_change),
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .padding(start = spacing.sm)
                    .heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    LocationChips(state.locations, row.locationId) { onAction(UnpackAction.ItemLocation(id, it)) }
                    val location = state.locations.firstOrNull { it.id == row.locationId }
                    ExpiryChips(ExpiryChoice.forLocation(location), row.expiry) { onAction(UnpackAction.ItemExpiry(id, it)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationChips(locations: List<InventoryLocation>, selected: Long?, onPick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        locations.forEach { location ->
            FilterChip(
                selected = selected == location.id,
                onClick = { onPick(location.id) },
                label = { Text(location.name) },
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpiryChips(choices: List<ExpiryChoice>, selected: ExpiryChoice?, onPick: (ExpiryChoice) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        (choices + listOfNotNull(selected)).distinct().forEach { choice ->
            FilterChip(
                selected = selected == choice,
                onClick = { onPick(choice) },
                label = { Text(expiryChoiceLabel(choice)) },
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}

/** "Lägg in n varor i skafferiet" and "Hoppa över", pinned below the list. */
@Composable
private fun UnpackActions(state: UnpackUiState, onAction: (UnpackAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            val count = state.chosenCount
            TandemPrimaryButton(
                text = if (count > 0) {
                    pluralStringResource(R.plurals.pantry_unpack_save, count, count)
                } else {
                    stringResource(R.string.pantry_unpack_save_none)
                },
                onClick = { onAction(UnpackAction.Save) },
                enabled = state.canSave,
                leadingIcon = PantryIcons.Unpack,
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                    .fillMaxWidth()
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                    .testTag(UNPACK_SAVE_TAG),
            )
            TandemTextButton(
                text = stringResource(R.string.pantry_unpack_skip),
                onClick = { onAction(UnpackAction.Skip) },
                enabled = !state.saving,
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}
