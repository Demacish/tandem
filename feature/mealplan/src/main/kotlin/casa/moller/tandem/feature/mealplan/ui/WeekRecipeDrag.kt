package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.WeekAction
import casa.moller.tandem.feature.mealplan.WeekUiState

// #21 (plan 8.3 "Vill laga-kö + dra och släpp till veckan", 6.5 "receptsökning som stödpanel"):
// where a recipe is dragged from. Phones and narrow tablets have "Vill laga" as a strip above the
// days; a wide tablet has a panel beside the grid with the queue and a search. A tap opens the
// recipe and a long press lifts it. TalkBack plans the same recipes through "+" on a day (the
// plan-meal page offers "Vill laga" and the search), so nothing here is drag-only.

/** "Vill laga" as a strip. */
const val WEEK_WANT_STRIP_TAG = "week:want-strip"

/** The tablet's recipe panel. */
const val WEEK_RECIPE_PANEL_TAG = "week:recipe-panel"

/** A recipe to drag: `week:drag-recipe:<id>`. */
fun dragRecipeTag(id: Long): String = "week:drag-recipe:$id"

/** From this width the tablet's grid has the recipe panel beside it. */
internal val RecipePanelMinWidth = 960.dp
internal val RecipePanelWidth = 280.dp

private val StripItemMaxWidth = 200.dp
private val RecipeThumbnail = 40.dp

/** "Vill laga" above the days, while the queue has something and the user may plan. */
@Composable
internal fun WantToCookStrip(state: WeekUiState, onAction: (WeekAction) -> Unit, modifier: Modifier = Modifier) {
    if (state.readOnly || state.wantToCook.isEmpty()) return
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth().testTag(WEEK_WANT_STRIP_TAG), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
        Text(
            text = stringResource(R.string.mealplan_want_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = spacing.lg).semantics { heading() },
        )
        Text(
            text = stringResource(R.string.mealplan_want_drag_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.lg),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(state.wantToCook, key = { it.id }) { recipe ->
                DragRecipeRow(recipe = recipe, onAction = onAction, modifier = Modifier.widthIn(max = StripItemMaxWidth))
            }
        }
    }
}

/** Beside the grid on a wide tablet: the search, and "Vill laga" while nothing is searched. */
@Composable
internal fun RecipeDragPanel(state: WeekUiState, onAction: (WeekAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val searching = state.recipeQuery.isNotBlank()
    val recipes = if (searching) state.recipeResults else state.wantToCook
    Column(
        modifier = modifier.fillMaxHeight().padding(end = spacing.lg, top = spacing.sm).testTag(WEEK_RECIPE_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        TandemTextField(
            value = state.recipeQuery,
            onValueChange = { onAction(WeekAction.SearchRecipes(it)) },
            label = stringResource(R.string.mealplan_recipe_search),
            leadingIcon = TandemIcons.Search,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(if (searching) R.string.mealplan_recipe_results else R.string.mealplan_want_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(
                when {
                    recipes.isNotEmpty() -> R.string.mealplan_want_drag_hint
                    searching -> R.string.mealplan_recipe_no_results
                    else -> R.string.mealplan_want_empty
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // A node that stays while the results change, so TalkBack says when nothing matched.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(recipes, key = { it.id }) { recipe ->
                DragRecipeRow(recipe = recipe, onAction = onAction, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One recipe to drag: a tap opens it, a long press lifts it. */
@Composable
private fun DragRecipeRow(recipe: RecipeOverview, onAction: (WeekAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .testTag(dragRecipeTag(recipe.id))
                .clip(MaterialTheme.shapes.large)
                .recipeDragSource(recipe, enabled = true)
                .clickable(role = Role.Button) { onAction(WeekAction.OpenRecipe(recipe.id)) }
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RecipeImage(
                imageUrl = recipe.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(RecipeThumbnail).clip(MaterialTheme.shapes.medium),
            )
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
