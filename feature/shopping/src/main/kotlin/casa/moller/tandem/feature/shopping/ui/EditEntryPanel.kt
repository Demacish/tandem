package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.shopping.EditEntryState
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingAction
import casa.moller.tandem.feature.shopping.ShoppingFormat

/**
 * Editing one entry (long press, plan 6.2 "Lång tryckning → ändra eller flytta kategori"): food,
 * amount and unit, the food's aisle (for the whole space, Tandoor's `supermarket_category`), the
 * named lists it is on (fix-ui, plan 3.5 "Flytt mellan listor"), "köp senare" / "köp nu", the
 * recipe it came from, and removing it (or, Phase 2 koppling lane, the whole recipe with its rows).
 * Fas 5 (plan §8.2): opened on a merged row, it first lists the row's entries and edits the chosen
 * one ([ShoppingAction.EditPart]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditEntryPanel(
    state: EditEntryState,
    format: ShoppingFormat,
    onAction: (ShoppingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val recipe = state.entry.recipe
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = stringResource(R.string.shopping_edit_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (state.parts.size > 1) MergedParts(state, format, onAction)
        val recipeName = recipe?.recipeName?.takeIf { it.isNotBlank() }
        val recipeId = recipe?.recipeId
        if (recipeName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.shopping_edit_from_recipe, recipeName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (recipeId != null) {
                    TandemTextButton(
                        text = stringResource(R.string.shopping_edit_open_recipe),
                        onClick = { onAction(ShoppingAction.OpenRecipe(recipeId)) },
                    )
                }
            }
        }
        TandemTextField(
            value = state.food,
            onValueChange = { onAction(ShoppingAction.EditFood(it)) },
            label = stringResource(R.string.shopping_edit_food),
            leadingIcon = null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            TandemTextField(
                value = state.amount,
                onValueChange = { onAction(ShoppingAction.EditAmount(it)) },
                label = stringResource(R.string.shopping_edit_amount),
                isError = state.amountError,
                supportingText = if (state.amountError) stringResource(R.string.shopping_edit_amount_error) else null,
                leadingIcon = null,
                modifier = Modifier.weight(1f),
            )
            TandemTextField(
                value = state.unit,
                onValueChange = { onAction(ShoppingAction.EditUnit(it)) },
                label = stringResource(R.string.shopping_edit_unit),
                leadingIcon = null,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { onAction(ShoppingAction.SaveEdit) }),
                modifier = Modifier.weight(1f),
            )
        }
        SmallHeading(stringResource(R.string.shopping_edit_category))
        if (state.canChangeCategory) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                state.categories.forEach { category ->
                    FilterChip(
                        selected = state.categoryId == category.id,
                        onClick = { onAction(ShoppingAction.EditCategory(category.id)) },
                        label = { Text(category.name) },
                    )
                }
                FilterChip(
                    selected = state.categoryId == null,
                    onClick = { onAction(ShoppingAction.EditCategory(null)) },
                    label = { Text(stringResource(R.string.shopping_other_category)) },
                )
            }
            Text(
                text = stringResource(R.string.shopping_edit_category_note, state.entry.food.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.shopping_edit_category_unsynced),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.lists.isNotEmpty()) {
            SmallHeading(stringResource(R.string.shopping_edit_lists))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                state.lists.forEach { list ->
                    FilterChip(
                        selected = list.id in state.listIds,
                        onClick = { onAction(ShoppingAction.EditList(list.id)) },
                        label = { Text(list.name) },
                    )
                }
            }
        }
        TandemPrimaryButton(
            text = stringResource(R.string.shopping_edit_save),
            onClick = { onAction(ShoppingAction.SaveEdit) },
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!state.entry.checked) {
            TandemSecondaryButton(
                text = stringResource(if (state.delayed) R.string.shopping_action_now else R.string.shopping_action_later),
                onClick = {
                    onAction(ShoppingAction.ToggleLater(state.entry.id))
                    onAction(ShoppingAction.CloseEdit)
                },
                leadingIcon = TandemIcons.Schedule,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TandemTextButton(
            text = stringResource(R.string.shopping_edit_delete),
            onClick = { onAction(ShoppingAction.DeleteEdited) },
            leadingIcon = TandemIcons.Delete,
            modifier = Modifier.fillMaxWidth(),
        )
        // Phase 2 (koppling lane, #12): the whole recipe goes, with every row it put on the list.
        if (recipeName != null && state.recipeRows > 0) {
            TandemTextButton(
                text = pluralStringResource(R.plurals.shopping_edit_remove_recipe, state.recipeRows, state.recipeRows, recipeName),
                onClick = { onAction(ShoppingAction.RemoveEditedRecipe) },
                leadingIcon = TandemIcons.Delete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Fas 5: the entries of the merged row the sheet was opened on, the edited one selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergedParts(state: EditEntryState, format: ShoppingFormat, onAction: (ShoppingAction) -> Unit) {
    SmallHeading(stringResource(R.string.shopping_edit_parts))
    Text(
        text = stringResource(R.string.shopping_edit_parts_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        state.parts.forEach { part ->
            val amount = format.quantity(part) ?: format.food(part)
            val source = part.recipe?.recipeName?.takeIf { it.isNotBlank() }
            FilterChip(
                selected = part.id == state.entry.id,
                onClick = { onAction(ShoppingAction.EditPart(part.id)) },
                label = { Text(if (source != null) stringResource(R.string.shopping_edit_part, amount, source) else amount) },
            )
        }
    }
}
