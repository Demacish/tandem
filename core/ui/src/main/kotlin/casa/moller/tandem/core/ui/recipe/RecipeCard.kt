package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.icon.DownloadForOfflineFilled
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.R

/**
 * A recipe in the grid of the recipe list (design recept_alla_recept): photo with the first
 * keyword, name, total time and rating.
 *
 * @param dimmed the recipe cannot be opened right now (offline and no stored detail): faded
 *   photo with a cloud badge "Not downloaded" (plan 6.2 "Offline"). Still clickable; the detail
 *   explains why it cannot load. The texts keep their full contrast (WCAG AA).
 * @param selected the recipe is open in the detail pane beside the list (wide windows).
 *
 * TalkBack (plan 6.6) reads one item: name, total time, rating ([recipeCardDescription]), then
 * the state ("Not downloaded, needs a connection" or "Downloaded, opens offline") once. The
 * photo with its keyword label and badges, and the time and rating labels, are hidden; the name
 * stays the card's text, so tests and UI Automator find the card by it.
 */
@Composable
fun RecipeCard(
    recipe: RecipeOverview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    selected: Boolean = false,
) {
    val spacing = TandemTheme.spacing
    val colors = cardTextColors(selected)
    Card(
        onClick = onClick,
        modifier = modifier.recipeCardSemantics(recipeCardDescription(recipe), cardStateDescription(recipe, dimmed), selected),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = colors.content,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            modifier = Modifier
                .padding(spacing.sm)
                .clip(MaterialTheme.shapes.medium)
                .clearAndSetSemantics { },
        ) {
            RecipeImage(
                imageUrl = recipe.imageUrl,
                dimmed = dimmed,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(IMAGE_ASPECT_RATIO),
            )
            recipe.keywords.firstOrNull()?.let { keyword ->
                ImageLabel(text = keyword.name, modifier = Modifier.align(Alignment.BottomStart).padding(spacing.sm))
            }
            if (recipe.availableOffline && !dimmed) {
                OfflineIcon(Modifier.align(Alignment.TopEnd).padding(spacing.sm))
            }
            if (dimmed) {
                NotDownloadedBadge(Modifier.align(Alignment.Center))
            }
        }
        Column(
            modifier = Modifier.padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Part of the card's description; not read a second time.
            FlowRow(
                modifier = Modifier.clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                RecipeTimeLabel(recipe.totalMinutes, color = colors.secondary)
                RecipeRatingLabel(recipe.rating, color = colors.content)
            }
        }
    }
}

/**
 * A recipe as a row of the recipe list in list mode: thumbnail, name, keywords, time and rating.
 * Same [dimmed] and [selected] rules as [RecipeCard].
 */
@Composable
fun RecipeListItem(
    recipe: RecipeOverview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    selected: Boolean = false,
) {
    val spacing = TandemTheme.spacing
    val colors = cardTextColors(selected)
    Card(
        onClick = onClick,
        modifier = modifier.recipeCardSemantics(recipeCardDescription(recipe), cardStateDescription(recipe, dimmed), selected),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = colors.content,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp)
                .padding(spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecipeImage(
                imageUrl = recipe.imageUrl,
                dimmed = dimmed,
                placeholderIconSize = TandemTheme.sizes.icon,
                modifier = Modifier
                    .size(THUMBNAIL_SIZE)
                    .clip(MaterialTheme.shapes.medium),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (recipe.keywords.isNotEmpty()) {
                    Text(
                        text = recipe.keywords.take(2).joinToString(" · ") { it.name },
                        style = TandemTheme.extendedTypography.overline,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    modifier = Modifier.clearAndSetSemantics { },
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    RecipeTimeLabel(recipe.totalMinutes, color = colors.secondary)
                    RecipeRatingLabel(recipe.rating, color = colors.content)
                }
            }
            // The card's state description says the same for TalkBack.
            Box(modifier = Modifier.clearAndSetSemantics { }) {
                when {
                    dimmed -> NotDownloadedIcon()
                    recipe.availableOffline -> OfflineIcon()
                }
            }
        }
    }
}

/**
 * What TalkBack reads for a recipe card or row, in reading order: the name, then the total time
 * and the rating when known ("Krämig svamppasta, Total time 30 min, Rated 4,5 of 5").
 */
@Composable
fun recipeCardDescription(recipe: RecipeOverview): String =
    listOfNotNull(recipe.name, recipeTimeDescription(recipe.totalMinutes), recipeRatingDescription(recipe.rating)).joinToString(", ")

/** "Not downloaded, needs a connection" (dimmed) or "Downloaded, opens offline"; null otherwise. */
@Composable
private fun cardStateDescription(recipe: RecipeOverview, dimmed: Boolean): String? = when {
    dimmed -> stringResource(R.string.ui_recipe_not_downloaded_description)
    recipe.availableOffline -> stringResource(R.string.ui_recipe_offline_badge_description)
    else -> null
}

/**
 * The card is one TalkBack item: its own [description] (its children are hidden), whether it is
 * open beside the list, and its offline [state], said once.
 */
private fun Modifier.recipeCardSemantics(description: String, state: String?, selected: Boolean): Modifier = semantics {
    contentDescription = description
    this.selected = selected
    if (state != null) stateDescription = state
}

/** Text colours of a card: on surfaceContainerLow, or on primaryContainer while [selected] (WCAG AA). */
private data class CardTextColors(val content: Color, val secondary: Color, val accent: Color)

@Composable
private fun cardTextColors(selected: Boolean): CardTextColors {
    val scheme = MaterialTheme.colorScheme
    return if (selected) {
        CardTextColors(content = scheme.onPrimaryContainer, secondary = scheme.onPrimaryContainer, accent = scheme.onPrimaryContainer)
    } else {
        CardTextColors(content = scheme.onSurface, secondary = scheme.onSurfaceVariant, accent = scheme.primary)
    }
}

/** Dark translucent label on a photo (first keyword), readable on any image. */
@Composable
private fun ImageLabel(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = TandemTheme.spacing.sm, vertical = TandemTheme.spacing.xxs),
        )
    }
}

/** Small round "downloaded" marker; TalkBack reads "Downloaded, opens offline". */
@Composable
private fun OfflineIcon(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = CircleShape, color = TandemTheme.extendedColors.successContainer) {
        Icon(
            imageVector = TandemIcons.DownloadForOfflineFilled,
            contentDescription = stringResource(R.string.ui_recipe_offline_badge_description),
            tint = TandemTheme.extendedColors.success,
            modifier = Modifier
                .padding(TandemTheme.spacing.xs)
                .size(TandemTheme.sizes.iconSmall),
        )
    }
}

@Composable
private fun NotDownloadedIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = TandemIcons.CloudOff,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = modifier.size(TandemTheme.sizes.icon),
    )
}

private const val IMAGE_ASPECT_RATIO = 4f / 3f
private val THUMBNAIL_SIZE = 72.dp
