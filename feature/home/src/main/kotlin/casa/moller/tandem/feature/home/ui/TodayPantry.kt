package casa.moller.tandem.feature.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.pantry.ExpiryLabel
import casa.moller.tandem.core.ui.pantry.expiryText
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.home.R
import casa.moller.tandem.feature.home.TodayExpiring
import casa.moller.tandem.feature.home.TodayPantryState
import kotlinx.datetime.LocalDate

/** Test tag of Idag's "Kan laga nu" row. */
const val TODAY_MAKE_NOW_TAG = "today:make-now"

/** Test tag of Idag's "Går ut snart" card. */
const val TODAY_EXPIRING_TAG = "today:expiring"

/**
 * Fas S (hemma-ui lane): Idag's "Kan laga nu" (plan §8.8, #23): the first recipes the household can
 * cook, those with everything at home first; "Visa alla" opens "Kan laga nu". Nothing when empty.
 */
@Composable
internal fun TodayMakeNowRow(
    pantry: TodayPantryState,
    horizontal: Dp,
    cardWidth: Dp,
    onOpenRecipe: (Long) -> Unit,
    onOpenMakeNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pantry.makeNow.isEmpty()) return
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.testTag(TODAY_MAKE_NOW_TAG), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizontal, end = horizontal - spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_make_now_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = if (pantry.hasAll > 0) {
                        pluralStringResource(R.plurals.home_make_now_subtitle, pantry.hasAll, pantry.hasAll)
                    } else {
                        stringResource(R.string.home_make_now_subtitle_almost)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TandemTextButton(
                text = stringResource(R.string.home_make_now_all),
                onClick = onOpenMakeNow,
                trailingIcon = TandemIcons.ArrowForward,
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = horizontal), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            items(pantry.makeNow, key = { it.id }) { recipe ->
                HomeRecipeCard(recipe = recipe, width = cardWidth, onClick = { onOpenRecipe(recipe.id) })
            }
        }
    }
}

/**
 * Fas S (hemma-ui lane): Idag's "Går ut snart" (plan §8.8 "Använd upp", #28): what expires within
 * three days, each with how many recipes use it ("Grädde går ut i morgon – 4 recept"); a row opens
 * "Kan laga nu" with those recipes first. Each row is one TalkBack item. Nothing when empty.
 */
@Composable
internal fun TodayExpiringCard(pantry: TodayPantryState, onOpenUseUp: () -> Unit, modifier: Modifier = Modifier) {
    val today = pantry.today ?: return
    if (pantry.expiring.isEmpty()) return
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.testTag(TODAY_EXPIRING_TAG), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = stringResource(R.string.home_expiring_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.home_expiring_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = spacing.xs)) {
                pantry.expiring.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExpiringRow(item = item, today = today, onOpen = onOpenUseUp)
                }
            }
        }
    }
}

@Composable
private fun ExpiringRow(item: TodayExpiring, today: LocalDate, onOpen: () -> Unit) {
    val spacing = TandemTheme.spacing
    val name = foodTitle(item.entry.food.name, currentLocale())
    val day = item.entry.expires
    val expiry = day?.let { expiryText(it, today) }.orEmpty()
    val recipes = if (item.recipeCount > 0) {
        pluralStringResource(R.plurals.home_expiring_recipes, item.recipeCount, item.recipeCount)
    } else {
        stringResource(R.string.home_expiring_no_recipes)
    }
    val description = listOf(name, expiry, recipes).filter { it.isNotEmpty() }.joinToString(", ")
    val openLabel = stringResource(R.string.home_expiring_open)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget + spacing.md)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = openLabel) {
                    onOpen()
                    true
                }
            }
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            if (expiry.isNotEmpty()) ExpiryLabel(text = expiry, urgency = item.urgency)
        }
        Text(text = recipes, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(TandemIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
