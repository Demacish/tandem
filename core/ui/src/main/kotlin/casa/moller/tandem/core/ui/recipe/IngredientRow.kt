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
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R

/**
 * One ingredient of the recipe detail (plan 6.2): check box, food (with note) and the scaled
 * amount. Tapping the row strikes it through (session only, "Tryck för att bocka av"); tapping
 * the amount shows it in the next unit the server converted it to, when there is one.
 *
 * Accessibility (plan 6.6): the row is one toggleable item ("checked"/"not checked" is read by
 * TalkBack), the strike-through is not the only signal (the box is checked too), and the unit
 * switch is also a custom action, so it does not need a precise tap.
 *
 * @param amountText the amount with its unit, e.g. "2 ½ dl"; null for "no amount" rows (salt).
 * @param name the food, e.g. "champinjoner"; already in plural when the amount is not 1.
 * @param note the free text after the food, e.g. "pressade".
 * @param onAmountClick switches the unit; null when the server sent no conversions.
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
) {
    val spacing = TandemTheme.spacing
    val textColor = if (struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val decoration = if (struck) TextDecoration.LineThrough else null
    val otherUnit = stringResource(R.string.ui_recipe_show_other_unit)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .toggleable(value = struck, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
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
        Column(modifier = Modifier.weight(1f).padding(vertical = spacing.sm)) {
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
            Amount(text = amountText, struck = struck, onSwitchUnit = onAmountClick, actionLabel = otherUnit)
        }
    }
}

@Composable
private fun Amount(text: String, struck: Boolean, onSwitchUnit: (() -> Unit)?, actionLabel: String) {
    val style = MaterialTheme.typography.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
        textDecoration = if (struck) TextDecoration.LineThrough else null,
        textAlign = TextAlign.End,
    )
    val color = if (struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    if (onSwitchUnit == null) {
        Text(text = text, style = style, color = color, modifier = Modifier.padding(horizontal = TandemTheme.spacing.sm))
    } else {
        // A tonal pill shows that the amount itself can be tapped (48 dp high target).
        Surface(
            onClick = onSwitchUnit,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .widthIn(min = 48.dp)
                .semantics {
                    onClick(label = actionLabel) {
                        onSwitchUnit()
                        true
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = TandemTheme.spacing.md)) {
                Text(text = text, style = style, color = color)
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
