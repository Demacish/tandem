package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.shopping.AddItemState
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingAction
import casa.moller.tandem.feature.shopping.ShoppingFormat

/** Test tag of the add field. */
const val SHOPPING_ADD_FIELD_TAG = "shopping:add:field"

/** Test tag of the add button. */
const val SHOPPING_ADD_BUTTON_TAG = "shopping:add:button"

/**
 * "Lägg till" (#11, plan 6.2 "Lägg till manuellt", Stitch ink_pslista_handla_surfplatta's "Lägg
 * till vara"): free text ("2 dl grädde", several by line or comma), the interpretation of every
 * item before it is added, suggestions for the item being typed and the foods bought recently.
 * Done on the keyboard adds and keeps the field open for the next item; so does the button, which
 * stands right below the field so that the keyboard never covers it. In a bottom sheet on phones,
 * in the side pane on tablets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddItemPanel(
    state: AddItemState,
    format: ShoppingFormat,
    onAction: (ShoppingAction) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val spacing = TandemTheme.spacing
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(focus) { focus.requestFocus() }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = stringResource(R.string.shopping_add_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        TandemTextField(
            value = state.text,
            onValueChange = { onAction(ShoppingAction.AddTextChanged(it)) },
            label = stringResource(R.string.shopping_add_label),
            placeholder = stringResource(R.string.shopping_add_placeholder),
            supportingText = stringResource(R.string.shopping_add_help),
            leadingIcon = null,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { onAction(ShoppingAction.SubmitAdd) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag(SHOPPING_ADD_FIELD_TAG),
        )
        // Right below the field, not below the preview and the suggestions: on a phone the keyboard
        // covers the lower half of the sheet, and with a few items and suggestions "Lägg till n
        // varor" ended up under it (0.3.0 integration, seen in the release smoke test).
        val count = state.preview.size
        TandemPrimaryButton(
            text = if (count > 0) {
                pluralStringResource(R.plurals.shopping_add_button_count, count, count)
            } else {
                stringResource(R.string.shopping_add_button)
            },
            onClick = { onAction(ShoppingAction.SubmitAdd) },
            enabled = count > 0 && !state.adding,
            leadingIcon = TandemIcons.Add,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SHOPPING_ADD_BUTTON_TAG),
        )
        if (state.preview.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SmallHeading(stringResource(R.string.shopping_add_preview_title))
                state.preview.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Icon(
                            imageVector = TandemIcons.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                        )
                        Text(text = format.parsed(item), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        val chips = if (state.text.isBlank()) state.recent else state.suggestions
        if (chips.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                val heading = if (state.text.isBlank()) R.string.shopping_add_recent_title else R.string.shopping_add_suggestions_title
                SmallHeading(stringResource(heading))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    chips.forEach { name ->
                        val description = stringResource(R.string.shopping_add_food_description, name)
                        AssistChip(
                            onClick = { onAction(ShoppingAction.AddFood(name)) },
                            label = { Text(name) },
                            leadingIcon = { Icon(TandemIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            enabled = !state.adding,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SmallHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}
