package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R

/**
 * One ingredient of the recipe detail (plan 6.2): check box, food (with note) and the scaled
 * amount. Tapping the row strikes it through (session only, "Tryck för att bocka av"); tapping
 * the amount shows it in the next unit the server converted it to, when there is one.
 *
 * Accessibility (plan 6.6 "mergeDescendants på rader"): the row is one TalkBack item that says
 * the whole ingredient ("125 g champinjoner, skivade", then "checkbox, not checked"); the
 * strike-through is not the only signal (the box is checked too). The tappable amount pill is
 * not a second TalkBack stop: the unit switch is the row's custom action "Show in another unit",
 * so it does not need a precise tap either.
 *
 * @param amountText the amount with its unit, e.g. "2 ½ dl"; null for "no amount" rows (salt).
 * @param name the food, e.g. "champinjoner"; already in plural when the amount is not 1.
 * @param note the free text after the food, e.g. "pressade".
 * @param onAmountClick switches the unit; null when the server sent no conversions.
 * @param amountDescription [amountText] as TalkBack should say it, e.g. "3/16 tsk" for the
 *   typographic "³⁄₁₆ tsk" (core:domain `spokenNumbers`); defaults to [amountText].
 * @param amountMinHeight the tappable amount pill's minimum height; unspecified means 48 dp
 *   (`minTouchTarget`). Cook mode passes 64 dp (`kitchenTouchTarget`, plan 6.6), so a tap just
 *   above or below the pill switches the unit instead of ticking the row off.
 */
@Composable
fun IngredientRow(
    amountText: String?,
    name: String,
    struck: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    onAmountClick: (() -> Unit)? = null,
    amountDescription: String? = amountText,
    amountMinHeight: Dp = Dp.Unspecified,
) {
    val spacing = TandemTheme.spacing
    val textColor = if (struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val decoration = if (struck) TextDecoration.LineThrough else null
    val otherUnit = stringResource(R.string.ui_recipe_show_other_unit)
    val description = ingredientDescription(amountDescription, name, note)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .toggleable(value = struck, role = Role.Checkbox, onValueChange = { onToggle() })
            // The row's own label replaces its children's texts (see the children's
            // clearAndSetSemantics), so TalkBack says the amount, food and note in one stop.
            .semantics {
                contentDescription = description
                if (onAmountClick != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(otherUnit) {
                            onAmountClick()
                            true
                        },
                    )
                }
            }
            .padding(end = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Checkbox(checked = struck, onCheckedChange = null, modifier = Modifier.padding(spacing.md))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = spacing.sm)
                .clearAndSetSemantics { },
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, color = textColor, textDecoration = decoration)
            if (!note.isNullOrBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = decoration,
                )
            }
        }
        if (amountText != null) {
            Amount(
                text = amountText,
                struck = struck,
                onSwitchUnit = onAmountClick,
                minHeight = if (amountMinHeight.isSpecified) amountMinHeight else TandemTheme.sizes.minTouchTarget,
            )
        }
    }
}

/** "125 g champinjoner, skivade": what TalkBack says for one ingredient row. */
internal fun ingredientDescription(amount: String?, name: String, note: String?): String = buildString {
    if (!amount.isNullOrBlank()) append(amount).append(' ')
    append(name)
    if (!note.isNullOrBlank()) append(", ").append(note)
}

/**
 * The amount at the end of the row. Hidden from TalkBack (the row says it, see [IngredientRow]).
 * With [onSwitchUnit] it is a tonal pill (at least [minHeight] high) that switches the unit on a tap.
 *
 * Colours (WCAG AA, TandemColorContrastTest "usage pairs"): plain amounts are primary on the
 * ingredient card (surfaceContainerLow / surfaceContainer); the pill is onPrimaryContainer on
 * primaryContainer, and onSurfaceVariant on surfaceContainerHighest once the row is ticked off.
 */
@Composable
private fun Amount(text: String, struck: Boolean, onSwitchUnit: (() -> Unit)?, minHeight: Dp) {
    val style = MaterialTheme.typography.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
        textDecoration = if (struck) TextDecoration.LineThrough else null,
        textAlign = TextAlign.End,
    )
    val colors = MaterialTheme.colorScheme
    if (onSwitchUnit == null) {
        Text(
            text = text,
            style = style,
            color = if (struck) colors.onSurfaceVariant else colors.primary,
            modifier = Modifier
                .padding(horizontal = TandemTheme.spacing.sm)
                .clearAndSetSemantics { },
        )
    } else {
        Surface(
            onClick = onSwitchUnit,
            shape = MaterialTheme.shapes.small,
            color = if (struck) colors.surfaceContainerHighest else colors.primaryContainer,
            contentColor = if (struck) colors.onSurfaceVariant else colors.onPrimaryContainer,
            // Still tappable, but not a TalkBack stop of its own: the row's custom action
            // "Show in another unit" switches the unit (plan 6.6).
            modifier = Modifier
                .clearAndSetSemantics { }
                .heightIn(min = minHeight)
                .widthIn(min = 48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = TandemTheme.spacing.md)) {
                Text(text = text, style = style)
            }
        }
    }
}

/**
 * A section heading inside an ingredient list (Tandoor's `is_header` rows, e.g. "Deg",
 * "Fyllning"). Marked as a heading for TalkBack navigation.
 */
@Composable
fun IngredientHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = TandemTheme.spacing.md, bottom = TandemTheme.spacing.xs, start = TandemTheme.spacing.md)
            .semantics { heading() },
    )
}
