package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Remove
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R

/**
 * The servings picker of the recipe detail (plan 6.2: "Portionsväljare, minns valet per recept"):
 * a label, the current value ("4 portioner") and − / + buttons (48 dp targets). The value is
 * a live region, so TalkBack announces the new servings after each step.
 *
 * @param label the heading, e.g. "Servings".
 * @param valueText the formatted servings with their unit, e.g. "6 portioner" or "1 ½ loaf".
 * @param amountText just the number, shown between the buttons ("6"); hidden from TalkBack,
 *   which reads [valueText].
 * @param onReset shows "Reset to 4" while the servings differ from the recipe's own.
 * @param resetText the text of that button, e.g. "Reset to 4".
 */
@Composable
fun ServingsStepper(
    label: String,
    valueText: String,
    amountText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseEnabled: Boolean = true,
    onReset: (() -> Unit)? = null,
    resetText: String? = null,
) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    text = label,
                    style = TandemTheme.extendedTypography.overline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (onReset != null && resetText != null) {
                    TandemTextButton(text = resetText, onClick = onReset)
                }
            }
            FilledTonalIconButton(onClick = onDecrease, enabled = decreaseEnabled) {
                Icon(imageVector = TandemIcons.Remove, contentDescription = stringResource(R.string.ui_recipe_fewer_servings))
            }
            Text(
                text = amountText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 32.dp)
                    .clearAndSetSemantics { },
            )
            FilledTonalIconButton(onClick = onIncrease) {
                Icon(imageVector = TandemIcons.Add, contentDescription = stringResource(R.string.ui_recipe_more_servings))
            }
        }
    }
}
