package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.WineBar
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.recipes.R

/** Test tag of the picker's list. */
const val PAIRING_PICKER_TAG: String = "pairing_picker"

/**
 * The pairing picker (plan §8.8). Stateless. For a dish: search, its drinks (photo, name, type) and
 * the kinds of drink ("Kraftigt rött"), each a checkbox row (one TalkBack item), and "Lägg till
 * ”…”" for a new kind from the search text. For a drink: the dishes. Every change is saved at
 * once; "Klar" goes back. Two columns on wide windows. A guest sees the read-only banner and
 * disabled rows.
 *
 * @param onCreateDrink "Ny dryck" from a dish without drinks (paired with it when saved).
 */
@Composable
fun PairingPickerScreen(
    state: PairingPickerUiState,
    onAction: (PairingPickerAction) -> Unit,
    onBack: () -> Unit,
    onCreateDrink: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(if (state.isDrink) R.string.drinks_pairs_to_heading else R.string.drinks_pairing_picker_title),
                subtitle = state.name.ifBlank { null },
                onNavigateBack = onBack,
                actions = {
                    AppChromeStatusChip(Modifier.padding(end = TandemTheme.spacing.xs))
                    TandemTextButton(text = stringResource(R.string.drinks_picker_done), onClick = onBack)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).imePadding()
        if (!state.loaded) {
            LoadingState(modifier = contentModifier)
            return@Scaffold
        }
        PickerGrid(state = state, onAction = onAction, onCreateDrink = onCreateDrink, modifier = contentModifier)
    }
}

@Composable
private fun PickerGrid(
    state: PairingPickerUiState,
    onAction: (PairingPickerAction) -> Unit,
    onCreateDrink: () -> Unit,
    modifier: Modifier,
) {
    val spacing = TandemTheme.spacing
    val enabled = !state.readOnly
    LazyVerticalGrid(
        columns = GridCells.Adaptive(ROW_MIN_CELL),
        modifier = modifier.testTag(PAIRING_PICKER_TAG),
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.readOnly) full("guest") { ReadOnlyBanner(compact = true) }
        full("search") {
            DrinkSearchField(
                text = state.query,
                onTextChange = { onAction(PairingPickerAction.Search(it)) },
                hint = stringResource(if (state.isDrink) R.string.drinks_picker_search_dishes else R.string.drinks_picker_search_drinks),
            )
        }
        if (state.nothingMatches) full("no-match") { Hint(stringResource(R.string.drinks_picker_no_match, state.query.trim())) }
        if (state.canCreateKind) {
            full("create") {
                TandemTonalButton(
                    text = stringResource(R.string.drinks_picker_create_kind, state.query.trim()),
                    onClick = { onAction(PairingPickerAction.CreateKind(state.query)) },
                    leadingIcon = TandemIcons.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.isDrink) {
            full("dishes") { Heading(stringResource(R.string.drinks_picker_dishes)) }
            items(state.dishes, key = { "dish-${it.item.id}" }) { choice ->
                ChoiceRow(choice.item.name, null, choice.item.imageUrl, choice.selected, enabled) {
                    onAction(PairingPickerAction.ToggleDish(choice.item, it))
                }
            }
        } else {
            drinkSections(state, onAction, onCreateDrink)
        }
    }
}

private fun LazyGridScope.drinkSections(state: PairingPickerUiState, onAction: (PairingPickerAction) -> Unit, onCreateDrink: () -> Unit) {
    val enabled = !state.readOnly
    full("drinks") { Heading(stringResource(R.string.drinks_picker_drinks)) }
    if (!state.hasDrinks) {
        full("no-drinks") {
            Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
                Hint(stringResource(R.string.drinks_picker_no_drinks))
                if (enabled) {
                    TandemTonalButton(text = stringResource(R.string.drinks_new), onClick = onCreateDrink, leadingIcon = TandemIcons.Add)
                }
            }
        }
    }
    items(state.drinks, key = { "drink-${it.item.id}" }) { choice ->
        val types = choice.item.types.map { drinkTypeLabel(it) }.joinToString(", ").ifEmpty { null }
        ChoiceRow(choice.item.name, types, choice.item.imageUrl, choice.selected, enabled) {
            onAction(PairingPickerAction.ToggleDrink(choice.item, it))
        }
    }
    full("kinds") {
        Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
            Heading(stringResource(R.string.drinks_picker_kinds))
            Hint(stringResource(R.string.drinks_picker_kinds_hint))
        }
    }
    items(state.kinds, key = { "kind-${it.item.keywordId}" }) { choice ->
        ChoiceRow(choice.item.name, null, null, choice.selected, enabled, icon = true) {
            onAction(PairingPickerAction.ToggleKind(choice.item, it))
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = TandemTheme.spacing.md).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One checkbox row: the whole row toggles and is ONE TalkBack item ("Rioja Crianza, Vin, checkbox, checked"). */
@Composable
private fun ChoiceRow(
    name: String,
    supporting: String?,
    imageUrl: String?,
    selected: Boolean,
    enabled: Boolean,
    icon: Boolean = false,
    onToggle: (Boolean) -> Unit,
) {
    val spacing = TandemTheme.spacing
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
                .heightIn(min = 64.dp)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            if (icon) {
                Icon(TandemIcons.WineBar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                RecipeImage(
                    imageUrl = imageUrl,
                    placeholderIconSize = TandemTheme.sizes.iconSmall,
                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.medium),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supporting?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val ROW_MIN_CELL = 360.dp
