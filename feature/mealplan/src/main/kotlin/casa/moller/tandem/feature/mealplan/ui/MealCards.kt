package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.icon.Cooking
import casa.moller.tandem.core.designsystem.icon.MoreVert
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.WeekAction

/** Test tag of a meal card or tile: `meal:<id>`. */
fun mealTag(id: Long): String = "meal:$id"

/** What can be done with a meal, in menu order. */
internal enum class MealMenuItem { OPEN, COOK, EDIT, MOVE, DELETE }

/**
 * The actions a meal offers: a recipe opens; "Laga" only on its day (plan 6.2: today's meal); a
 * guest only opens and cooks (#42).
 */
internal fun mealMenu(meal: MealPlanEntry, isToday: Boolean, readOnly: Boolean): List<MealMenuItem> = buildList {
    if (meal.recipe != null) add(MealMenuItem.OPEN)
    if (meal.recipe != null && isToday) add(MealMenuItem.COOK)
    if (!readOnly) addAll(listOf(MealMenuItem.EDIT, MealMenuItem.MOVE, MealMenuItem.DELETE))
}

internal fun MealMenuItem.action(meal: MealPlanEntry): WeekAction = when (this) {
    MealMenuItem.OPEN -> WeekAction.OpenRecipe(requireNotNull(meal.recipe).id)
    MealMenuItem.COOK -> WeekAction.Cook(meal)
    MealMenuItem.EDIT -> WeekAction.EditMeal(meal)
    MealMenuItem.MOVE -> WeekAction.StartMove(meal)
    MealMenuItem.DELETE -> WeekAction.Delete(meal)
}

@Composable
internal fun MealMenuItem.label(): String = stringResource(
    when (this) {
        MealMenuItem.OPEN -> R.string.mealplan_open_recipe
        MealMenuItem.COOK -> R.string.mealplan_cook
        MealMenuItem.EDIT -> R.string.mealplan_edit
        MealMenuItem.MOVE -> R.string.mealplan_move
        MealMenuItem.DELETE -> R.string.mealplan_delete
    },
)

/** The meal's name, or "Måltid" for a meal without a recipe or title (should not happen in Tandoor). */
@Composable
internal fun MealPlanEntry.name(): String = displayTitle.ifBlank { stringResource(R.string.mealplan_untitled) }

/**
 * One planned meal in the agenda (Stitch veckan_matsedel): photo, meal type, name, servings and
 * time, the note, "väntar på att synkas" when queued, and a menu. Tapping opens the recipe (or
 * the plan-meal page for a meal without one). TalkBack reads the card as one item and offers the
 * menu's actions as custom actions; today's recipe has a "Laga" button (plan 6.2).
 */
@Composable
internal fun MealCard(
    meal: MealPlanEntry,
    isToday: Boolean,
    readOnly: Boolean,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val menu = mealMenu(meal, isToday, readOnly)
    val primary = primaryAction(meal, readOnly)
    val customActions = menu.filter { it != MealMenuItem.OPEN }.map { item ->
        CustomAccessibilityAction(item.label()) {
            onAction(item.action(meal))
            true
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isToday) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isToday) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .testTag(mealTag(meal.id))
                    .clip(MaterialTheme.shapes.large)
                    .then(
                        if (primary != null) {
                            Modifier.clickable(onClickLabel = primary.second, role = Role.Button) { onAction(primary.first) }
                        } else {
                            Modifier
                        },
                    )
                    .semantics(mergeDescendants = true) { this.customActions = customActions }
                    .heightIn(min = TandemTheme.sizes.minTouchTarget)
                    .padding(start = spacing.md, top = spacing.md, bottom = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                MealThumbnail(meal = meal, modifier = Modifier.size(72.dp))
                MealTexts(meal = meal, format = format, modifier = Modifier.weight(1f))
                if (menu.isNotEmpty()) MealMenuButton(meal = meal, menu = menu, onAction = onAction)
            }
            if (isToday && MealMenuItem.COOK in menu) {
                val cookDescription = stringResource(R.string.mealplan_cook_description, meal.name())
                TandemPrimaryButton(
                    text = stringResource(R.string.mealplan_cook),
                    onClick = { onAction(WeekAction.Cook(meal)) },
                    leadingIcon = TandemIcons.Cooking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.md, end = spacing.md, bottom = spacing.md)
                        .semantics { this.contentDescription = cookDescription },
                )
            }
        }
    }
}

/**
 * A meal in the tablet's week grid (Stitch veckan_matsedel_surfplatta): photo, name, servings.
 * Tapping opens the menu (open, cook, change, move, remove).
 */
@Composable
internal fun MealTile(
    meal: MealPlanEntry,
    isToday: Boolean,
    readOnly: Boolean,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val menu = mealMenu(meal, isToday, readOnly)
    var open by rememberSaveable(meal.id) { mutableStateOf(false) }
    val resources = LocalResources.current
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(mealTag(meal.id))
                .clip(MaterialTheme.shapes.medium)
                .then(
                    if (menu.isNotEmpty()) {
                        val label = stringResource(R.string.mealplan_show_actions)
                        Modifier.clickable(onClickLabel = label, role = Role.Button) { open = true }
                    } else {
                        Modifier
                    },
                )
                .semantics(mergeDescendants = true) {},
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (meal.recipe != null) {
                    RecipeImage(
                        imageUrl = meal.recipe?.imageUrl,
                        placeholderIconSize = 24.dp,
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = spacing.sm).padding(bottom = spacing.sm, top = spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = meal.name(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (meal.recipe == null) {
                            stringResource(R.string.mealplan_without_recipe)
                        } else {
                            format.servings(meal.servings, resources)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (meal.pending) PendingLabel()
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { item ->
                DropdownMenuItem(text = { Text(item.label()) }, onClick = {
                    open = false
                    onAction(item.action(meal))
                })
            }
        }
    }
}

/** The card's tap: open the recipe, or (not for guests) change a meal without one. */
@Composable
private fun primaryAction(meal: MealPlanEntry, readOnly: Boolean): Pair<WeekAction, String>? {
    val recipe = meal.recipe
    return when {
        recipe != null -> WeekAction.OpenRecipe(recipe.id) to stringResource(R.string.mealplan_open_recipe)
        !readOnly -> WeekAction.EditMeal(meal) to stringResource(R.string.mealplan_edit)
        else -> null
    }
}

@Composable
private fun MealThumbnail(meal: MealPlanEntry, modifier: Modifier = Modifier) {
    val recipe = meal.recipe
    Box(
        modifier = modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (recipe != null) {
            RecipeImage(imageUrl = recipe.imageUrl, placeholderIconSize = 24.dp, modifier = Modifier.matchParentSize())
        } else {
            Icon(TandemIcons.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MealTexts(meal: MealPlanEntry, format: MealPlanFormat, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val spacing = TandemTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
        if (meal.mealType.name.isNotBlank()) {
            Text(
                text = meal.mealType.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(text = meal.name(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val recipe = meal.recipe
        val meta = buildList {
            if (recipe == null) {
                add(stringResource(R.string.mealplan_without_recipe))
            } else {
                durationText(recipe.totalMinutes)?.let(::add)
                add(format.servings(meal.servings, resources))
            }
            if (meal.onShoppingList) add(stringResource(R.string.mealplan_on_shopping_list))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            meta.forEach { part ->
                Text(text = part, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (meal.note.isNotBlank()) {
            Text(
                text = meal.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meal.pending) PendingLabel()
    }
}

@Composable
private fun PendingLabel() {
    Text(
        text = stringResource(R.string.mealplan_pending),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MealMenuButton(meal: MealPlanEntry, menu: List<MealMenuItem>, onAction: (WeekAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(TandemIcons.MoreVert, contentDescription = stringResource(R.string.mealplan_more_for, meal.name()))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { item ->
                DropdownMenuItem(text = { Text(item.label()) }, onClick = {
                    open = false
                    onAction(item.action(meal))
                })
            }
        }
    }
}

/** "35 min", "1 tim 5 min"; null for an unknown time (0). */
@Composable
internal fun durationText(minutes: Int): String? {
    if (minutes <= 0) return null
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return if (hours == 0) {
        stringResource(R.string.mealplan_minutes, rest)
    } else {
        stringResource(R.string.mealplan_hours_minutes, hours, rest)
    }
}

private const val MINUTES_PER_HOUR = 60
