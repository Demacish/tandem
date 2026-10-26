package casa.moller.tandem.feature.home.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.Cooking
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.recipe.RecipeImageTags
import casa.moller.tandem.core.ui.recipe.cappedAspectRatio
import casa.moller.tandem.feature.home.R
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.text.NumberFormat

/**
 * A recipe photo, or a quiet placeholder while it loads or when the recipe has none. Decorative:
 * the recipe's name is always next to it. Loaded with Coil's singleton ImageLoader, which the app
 * configures with Tandem's authenticated OkHttp client.
 */
@Composable
internal fun HomeRecipeImage(imageUrl: String?, modifier: Modifier = Modifier, placeholderSize: Dp = 32.dp) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .testTag(RecipeImageTags.of(loaded))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = TandemIcons.Restaurant,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(placeholderSize),
        )
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

/** Tallest the "Tonight" photo gets; on wide columns it is cropped instead of growing. */
private val TonightImageMaxHeight = 300.dp

/**
 * "Tonight" (design idag_*: "Ikväll"): photo, meal type, name, description, time and servings.
 * The whole card opens the recipe; an entry with only a title is shown but not clickable.
 * "Laga" (Phase 1b) opens cook mode at the planned servings.
 */
@Composable
internal fun TonightCard(
    entry: MealPlanEntry,
    onOpenRecipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onStartCooking: ((recipeId: Long, servings: Double) -> Unit)? = null,
) {
    val spacing = TandemTheme.spacing
    val recipe = entry.recipe
    val openLabel = stringResource(R.string.home_open_recipe)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .then(
                if (recipe != null) {
                    Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { onOpenRecipe(recipe.id) }
                } else {
                    Modifier
                },
            ),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (recipe != null) {
            HomeRecipeImage(
                imageUrl = recipe.imageUrl,
                placeholderSize = 56.dp,
                modifier = Modifier.cappedAspectRatio(ratio = 16f / 9f, maxHeight = TonightImageMaxHeight),
            )
        }
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            MealLabel(label = stringResource(R.string.home_tonight), mealType = entry.mealType.name)
            Text(
                text = entry.displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            recipe?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MealMeta(entry = entry)
            if (recipe != null && onStartCooking != null) {
                val cookDescription = stringResource(R.string.home_start_cooking_description, entry.displayTitle)
                TandemPrimaryButton(
                    text = stringResource(R.string.home_start_cooking),
                    onClick = { onStartCooking(recipe.id, entry.servings) },
                    leadingIcon = TandemIcons.Cooking,
                    modifier = Modifier
                        .padding(top = spacing.xs)
                        .semantics { contentDescription = cookDescription },
                )
            }
        }
    }
}

/**
 * A planned meal as a row ("Tomorrow · Dinner", name, time): thumbnail, texts and a chevron when
 * it opens a recipe.
 */
@Composable
internal fun MealRow(entry: MealPlanEntry, label: String, onOpenRecipe: (Long) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val recipe = entry.recipe
    val openLabel = stringResource(R.string.home_open_recipe)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(
                if (recipe != null) {
                    Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { onOpenRecipe(recipe.id) }
                } else {
                    Modifier
                },
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            HomeRecipeImage(
                imageUrl = recipe?.imageUrl,
                placeholderSize = 24.dp,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                MealLabel(label = label, mealType = entry.mealType.name)
                Text(text = entry.displayTitle, style = MaterialTheme.typography.titleMedium)
                MealMeta(entry = entry)
            }
            if (recipe != null) {
                Icon(
                    imageVector = TandemIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "TONIGHT · Dinner" above a planned meal. */
@Composable
private fun MealLabel(label: String, mealType: String) {
    Text(
        text = stringResource(R.string.home_meal_label, label, mealType),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Time and servings of a planned meal, or "No recipe linked" for a title-only entry. */
@Composable
private fun MealMeta(entry: MealPlanEntry) {
    val recipe = entry.recipe
    val parts = buildList {
        if (recipe == null) {
            add(stringResource(R.string.home_meal_without_recipe))
        } else {
            durationText(recipe.totalMinutes)?.let(::add)
            add(servingsText(entry.servings))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md)) {
        parts.forEach { part ->
            Text(text = part, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "35 min", "1 h 5 min"; null for an unknown time (0). */
@Composable
internal fun durationText(minutes: Int): String? {
    if (minutes <= 0) return null
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        hours == 0 -> stringResource(R.string.home_minutes, rest)
        rest == 0 -> stringResource(R.string.home_hours, hours)
        else -> stringResource(R.string.home_hours_minutes, hours, rest)
    }
}

/** "4 servings", "1.5 servings" (decimals in the user's locale). */
@Composable
private fun servingsText(servings: Double): String {
    val whole = servings % 1.0 == 0.0
    val number = if (whole) {
        servings.toLong().toString()
    } else {
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(servings)
    }
    val quantity = if (whole) servings.toInt() else Int.MAX_VALUE
    return pluralStringResource(R.plurals.home_servings, quantity, number)
}

private const val MINUTES_PER_HOUR = 60

/**
 * A recipe in a carousel: photo, name and total time. One button for TalkBack ("Open recipe").
 *
 * @param width fixed card width (the carousel scrolls sideways); the height follows the text, so
 *   large font sizes never cut the name.
 */
@Composable
internal fun HomeRecipeCard(recipe: RecipeOverview, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val openLabel = stringResource(R.string.home_open_recipe)
    Card(
        modifier = modifier
            .width(width)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        HomeRecipeImage(
            imageUrl = recipe.imageUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
        )
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            recipe.keywords.firstOrNull()?.let { keyword ->
                Text(
                    text = keyword.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = TandemTheme.extendedColors.success,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            durationText(recipe.totalMinutes)?.let { duration ->
                Text(text = duration, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * "Nothing planned today" (plan 6.2) with a way to the recipes. [onPlanWeek] adds "Plan the
 * week →" as the main action (the Week tab plans since Phase 2); without it (a guest, #42) the
 * body says the dinner shows up once someone plans and "Browse recipes" is the main action.
 */
@Composable
internal fun NothingPlannedCard(onBrowseRecipes: () -> Unit, modifier: Modifier = Modifier, onPlanWeek: (() -> Unit)? = null) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IconBadge(icon = TandemIcons.CalendarMonth)
            Text(
                text = stringResource(R.string.home_nothing_planned_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(
                    if (onPlanWeek != null) R.string.home_nothing_planned_body else R.string.home_nothing_planned_body_read_only,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (onPlanWeek != null) {
                    TandemPrimaryButton(
                        text = stringResource(R.string.home_plan_week),
                        onClick = onPlanWeek,
                        trailingIcon = TandemIcons.ArrowForward,
                    )
                    TandemTextButton(text = stringResource(R.string.home_browse_recipes), onClick = onBrowseRecipes)
                } else {
                    TandemPrimaryButton(
                        text = stringResource(R.string.home_browse_recipes),
                        onClick = onBrowseRecipes,
                        trailingIcon = TandemIcons.ArrowForward,
                    )
                }
            }
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null)
        }
    }
}

/**
 * "Last synced with Tandoor at 18:14 · Sync now" (design idag_surfplatta_*).
 *
 * @param lastSynced the whole line, e.g. "Last synced with Tandoor at 18:14" or "… on Oct 5,
 *   2026, 18:14" (home_sync_last_today / home_sync_last_date); null when never synced.
 */
@Composable
internal fun SyncStatusCard(lastSynced: String?, running: Boolean, onSyncNow: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.sm, top = spacing.xs, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(imageVector = TandemIcons.Sync, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.iconSmall))
            Text(
                text = when {
                    running -> stringResource(R.string.home_sync_running)
                    lastSynced != null -> lastSynced
                    else -> stringResource(R.string.home_sync_never)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            )
            TandemTextButton(text = stringResource(R.string.home_sync_now), onClick = onSyncNow, enabled = !running)
        }
    }
}
