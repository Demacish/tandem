package casa.moller.tandem.feature.shopping.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.recipe.ServingsStepper
import casa.moller.tandem.feature.shopping.R
import java.util.Locale

/** Test tag of the "Lägg på inköpslistan" button. */
const val RECIPE_SHOPPING_BUTTON_TAG = "recipe-shopping:button"

/** Test tag of the sheet's add button. */
const val RECIPE_SHOPPING_ADD_TAG = "recipe-shopping:add"

/** Test tag of one ingredient row in the sheet. */
fun recipeShoppingRowTag(id: Long): String = "recipe-shopping:row:$id"

/**
 * "Lägg på inköpslistan" under a recipe's ingredients or on cook mode's last page (feature #12),
 * stateless: the button, and when the recipe is on the list already how many rows with "Ta bort
 * från listan". Nothing for a guest (#42) or a recipe that cannot go on the list (not stored, or
 * no ingredient with a food).
 *
 * @param servings the servings shown where the button is (the sheet starts with them).
 */
@Composable
fun RecipeShoppingButton(
    state: RecipeShoppingUiState,
    onAction: (RecipeShoppingAction) -> Unit,
    servings: Double?,
    modifier: Modifier = Modifier,
) {
    if (state.readOnly || state.loading) return
    if (!state.available && state.onList == 0) return
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        if (state.available) {
            TandemSecondaryButton(
                text = stringResource(R.string.recipe_shopping_button),
                onClick = { onAction(RecipeShoppingAction.Open(servings)) },
                leadingIcon = TandemIcons.ShoppingCart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TandemTheme.sizes.minTouchTarget)
                    .testTag(RECIPE_SHOPPING_BUTTON_TAG),
            )
        }
        if (state.onList > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = pluralStringResource(R.plurals.recipe_shopping_on_list, state.onList, state.onList),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TandemTextButton(
                    text = stringResource(R.string.recipe_shopping_remove),
                    onClick = { onAction(RecipeShoppingAction.RemoveFromList) },
                    leadingIcon = TandemIcons.Delete,
                )
            }
        }
    }
}

/** The sheet over the recipe or cook mode while [RecipeShoppingUiState.open] (phones and tablets alike). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeShoppingSheet(state: RecipeShoppingUiState, onAction: (RecipeShoppingAction) -> Unit) {
    if (!state.open) return
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = { onAction(RecipeShoppingAction.Dismiss) }, sheetState = sheetState) {
        RecipeShoppingSheetLayout(
            state = state,
            onAction = onAction,
            modifier = Modifier
                .padding(horizontal = TandemTheme.spacing.screenHorizontal)
                .padding(bottom = TandemTheme.spacing.xl),
        )
    }
}

/**
 * The sheet's layout (0.3.0 komplettering): the lists, servings and ingredients scroll, and "Lägg
 * till n varor i inköpslistan" with "Avbryt" stays pinned below them. Before, the button stood at
 * the end of the scrolling content, so with a recipe of nine ingredients it was below the fold on
 * a phone: the user had to scroll past every row to find it (and the release smoke test found no
 * node for it, since Compose leaves fully clipped nodes out of the accessibility tree).
 *
 * Fas S (rester lane): in a sheet lower than [COMPACT_SHEET_HEIGHT] (a phone in landscape) the
 * title and the servings share a row and "Avbryt" stands beside the add button, so the rows get
 * room to scroll; before, the title, servings, chips and the pinned buttons filled the whole sheet.
 */
@Composable
fun RecipeShoppingSheetLayout(state: RecipeShoppingUiState, onAction: (RecipeShoppingAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxHeight < COMPACT_SHEET_HEIGHT
        val spacing = TandemTheme.spacing
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) spacing.xs else spacing.sm)) {
            RecipeShoppingContent(
                state = state,
                onAction = onAction,
                actions = false,
                compact = compact,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )
            RecipeShoppingActions(state = state, onAction = onAction, compact = compact)
        }
    }
}

/** Below this height [RecipeShoppingSheetLayout] uses its compact header (Material's compact window height). */
internal val COMPACT_SHEET_HEIGHT: Dp = 480.dp

/**
 * The sheet's content (Stitch l_gg_p_listan_ark): where the rows go (Tandoor's named lists, when
 * there are any), the servings, "Markera alla" / "Bara det som saknas", the ingredients to buy
 * and those "Finns hemma" (unticked), and "Lägg till n varor i inköpslistan". Each row is one
 * TalkBack item with its checkbox state.
 *
 * @param actions false when the caller pins the buttons below the content ([RecipeShoppingSheetLayout]).
 * @param compact (Fas S, rester lane) a low sheet: the title and the servings share one row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeShoppingContent(
    state: RecipeShoppingUiState,
    onAction: (RecipeShoppingAction) -> Unit,
    modifier: Modifier = Modifier,
    actions: Boolean = true,
    compact: Boolean = false,
) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) spacing.sm else spacing.md)) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                // More room for the stepper: "4 portioner" stays on one line in a 640 dp sheet.
                SheetTitle(state = state, modifier = Modifier.weight(2f))
                Servings(state = state, onAction = onAction, modifier = Modifier.weight(3f))
            }
        } else {
            SheetTitle(state = state)
        }
        if (state.lists.isNotEmpty()) {
            Text(text = stringResource(R.string.recipe_shopping_list_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                FilterChip(
                    selected = state.listId == null,
                    onClick = { onAction(RecipeShoppingAction.SelectList(null)) },
                    label = { Text(stringResource(R.string.recipe_shopping_list_none)) },
                    modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                )
                state.lists.forEach { list ->
                    FilterChip(
                        selected = state.listId == list.id,
                        onClick = { onAction(RecipeShoppingAction.SelectList(list.id)) },
                        label = { Text(list.name) },
                        modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                    )
                }
            }
        }
        if (!compact) Servings(state = state, onAction = onAction)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FilterChip(
                selected = state.allChosen,
                onClick = { onAction(RecipeShoppingAction.SelectAll) },
                label = { Text(stringResource(R.string.recipe_shopping_select_all)) },
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
            if (state.atHome.isNotEmpty()) {
                FilterChip(
                    selected = state.onlyMissing,
                    onClick = { onAction(RecipeShoppingAction.OnlyMissing) },
                    label = { Text(stringResource(R.string.recipe_shopping_only_missing)) },
                    modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                )
            }
        }
        if (state.toBuy.isNotEmpty()) {
            SectionHeading(
                title = stringResource(R.string.recipe_shopping_to_buy),
                end = pluralStringResource(R.plurals.recipe_shopping_chosen, state.chosenCount, state.chosenCount),
            )
            RowGroup(rows = state.toBuy, onAction = onAction)
        }
        if (state.atHome.isNotEmpty()) {
            SectionHeading(title = stringResource(R.string.recipe_shopping_at_home), end = null)
            Text(
                // Fas S (hemma-ui lane): from Kan laga nu, "at home" is what the pantry has.
                text = stringResource(
                    if (state.fromPantry) R.string.recipe_shopping_at_home_pantry_hint else R.string.recipe_shopping_at_home_hint,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 0.3.0 komplettering (finding 22): where "at home" comes from (shopping_add_onhand, mealplan_autoexclude_onhand).
            if (!state.fromPantry) {
                Text(
                    text = stringResource(R.string.recipe_shopping_at_home_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowGroup(rows = state.atHome, onAction = onAction)
        }
        if (actions) RecipeShoppingActions(state = state, onAction = onAction)
    }
}

/** "Lägg på inköpslistan" and the recipe's name. */
@Composable
private fun SheetTitle(state: RecipeShoppingUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xxs)) {
        Text(
            text = stringResource(R.string.recipe_shopping_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = state.recipeName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The servings stepper (the rows' amounts follow it). */
@Composable
private fun Servings(state: RecipeShoppingUiState, onAction: (RecipeShoppingAction) -> Unit, modifier: Modifier = Modifier) {
    val amount = servingsNumber(state.servings, LocalConfiguration.current.locales[0])
    ServingsStepper(
        label = stringResource(R.string.recipe_shopping_servings_label),
        valueText = if (state.servingsText.isNotBlank()) {
            stringResource(R.string.recipe_shopping_servings_with_text, amount, state.servingsText)
        } else {
            pluralStringResource(R.plurals.recipe_shopping_servings, if (state.servings == 1.0) 1 else PLURAL_OTHER, amount)
        },
        amountText = amount,
        onDecrease = { onAction(RecipeShoppingAction.DecreaseServings) },
        onIncrease = { onAction(RecipeShoppingAction.IncreaseServings) },
        decreaseEnabled = state.canDecreaseServings,
        modifier = modifier,
    )
}

/**
 * "Lägg till n varor i inköpslistan" and "Avbryt" (below the content, or pinned in
 * [RecipeShoppingSheetLayout]); [compact]: side by side, "Avbryt" first.
 */
@Composable
private fun RecipeShoppingActions(state: RecipeShoppingUiState, onAction: (RecipeShoppingAction) -> Unit, compact: Boolean = false) {
    val spacing = TandemTheme.spacing
    if (compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            CancelButton(onAction = onAction)
            AddButton(state = state, onAction = onAction, modifier = Modifier.weight(1f))
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            AddButton(state = state, onAction = onAction, modifier = Modifier.fillMaxWidth())
            CancelButton(onAction = onAction, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun AddButton(state: RecipeShoppingUiState, onAction: (RecipeShoppingAction) -> Unit, modifier: Modifier = Modifier) {
    val count = state.chosenCount
    TandemPrimaryButton(
        text = if (count > 0) {
            pluralStringResource(R.plurals.recipe_shopping_add, count, count)
        } else {
            stringResource(R.string.recipe_shopping_add_none)
        },
        onClick = { onAction(RecipeShoppingAction.Add) },
        enabled = count > 0 && !state.adding,
        leadingIcon = TandemIcons.ShoppingCart,
        modifier = modifier
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .testTag(RECIPE_SHOPPING_ADD_TAG),
    )
}

@Composable
private fun CancelButton(onAction: (RecipeShoppingAction) -> Unit, modifier: Modifier = Modifier) {
    TandemTextButton(
        text = stringResource(R.string.recipe_shopping_cancel),
        onClick = { onAction(RecipeShoppingAction.Dismiss) },
        modifier = modifier,
    )
}

@Composable
private fun SectionHeading(title: String, end: String?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (end != null) {
            Text(text = end, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RowGroup(rows: List<RecipeShoppingRow>, onAction: (RecipeShoppingAction) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = TandemTheme.spacing.xs)) {
            rows.forEach { row -> IngredientChoice(row = row, onToggle = { onAction(RecipeShoppingAction.Toggle(row.id)) }) }
        }
    }
}

/** One ingredient: checkbox, food (and note), "Finns hemma" and the amount; one TalkBack item. */
@Composable
private fun IngredientChoice(row: RecipeShoppingRow, onToggle: () -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = row.chosen, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .padding(horizontal = spacing.sm, vertical = spacing.xs)
            .testTag(recipeShoppingRowTag(row.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Checkbox(checked = row.chosen, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(text = row.food, style = MaterialTheme.typography.bodyLarge)
            if (row.note.isNotBlank()) {
                Text(text = row.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.onHand) StatusChip(label = stringResource(R.string.recipe_shopping_on_hand))
        }
        if (row.amount.isNotEmpty()) {
            Text(
                text = row.amount,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** "4" or "2,5" in the app's language. */
private fun servingsNumber(servings: Double, locale: Locale): String =
    if (servings % 1.0 == 0.0) servings.toLong().toString() else String.format(locale, "%.1f", servings)

/** Any count other than one for `pluralStringResource` (servings may be fractional). */
private const val PLURAL_OTHER = 2
