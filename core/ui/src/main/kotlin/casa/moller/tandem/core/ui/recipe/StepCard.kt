package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R

/**
 * One step of a recipe (design "Gör så här"): the step number in a tomato circle, the optional
 * step name as a heading (TalkBack navigates by headings, plan 6.6), the step's time, its
 * ingredients and the instruction.
 *
 * @param number the 1-based step number; null for Tandoor steps shown as a section heading.
 * @param title the step's name, if it has one.
 * @param minutes the step's own time (Tandoor `time`), 0 for none.
 * @param ingredients the step's ingredient rows, if they are shown with the step.
 * @param footer e.g. a button that opens a sub-recipe.
 * @param instruction the step text.
 */
@Composable
fun StepCard(
    number: Int?,
    title: String?,
    modifier: Modifier = Modifier,
    minutes: Int = 0,
    ingredients: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    instruction: @Composable () -> Unit,
) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(spacing.lg), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            if (number != null) {
                StepNumber(number)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                val stepLabel = number?.let { stringResource(R.string.ui_recipe_step_number, it) }
                val headingText = if (!title.isNullOrBlank()) title else stepLabel
                if (headingText != null) {
                    // TalkBack hears the number too: "Step 2: Pastan".
                    val spoken = if (!title.isNullOrBlank() && number != null) {
                        stringResource(R.string.ui_recipe_step_number_title, number, title)
                    } else {
                        headingText
                    }
                    Text(
                        text = headingText,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            heading()
                            contentDescription = spoken
                        },
                    )
                }
                if (minutes > 0) {
                    IconLabel(
                        icon = TandemIcons.Schedule,
                        text = formatMinutes(minutes),
                        contentDescription = stringResource(R.string.ui_recipe_step_time_description, formatMinutes(minutes)),
                    )
                }
                if (ingredients != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(modifier = Modifier.padding(vertical = spacing.xs), content = ingredients)
                    }
                }
                instruction()
                footer?.invoke(this)
            }
        }
    }
}

@Composable
private fun StepNumber(number: Int) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 32.dp, minHeight = 32.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Box(modifier = Modifier.sizeIn(minWidth = 32.dp, minHeight = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = TandemTheme.spacing.sm),
                )
            }
        }
    }
}
