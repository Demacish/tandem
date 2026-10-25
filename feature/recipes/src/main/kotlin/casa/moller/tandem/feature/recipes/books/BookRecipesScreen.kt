package casa.moller.tandem.feature.recipes.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.BookmarkFilled
import casa.moller.tandem.core.designsystem.icon.CollectionsBookmark
import casa.moller.tandem.core.designsystem.icon.FavoriteFilled
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Tune
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.recipe.RecipeListItem
import casa.moller.tandem.feature.recipes.R

/** Test tag of a book's recipe list. */
const val BOOK_RECIPES_TAG: String = "book_recipes"

/**
 * One book's recipes, everyone's favourites or "Vill laga" (plan 3.7, 6.2; Stitch
 * receptbok_master_detail_surfplatta's book pane). Stateless: [state] in, [onAction] out.
 *
 * - Someone else's book says why it cannot be changed (plan 3.7: only the owner may write) and has
 *   no remove buttons; a guest sees the read-only banner (#42).
 * - Rows are recipe rows (one TalkBack item each) with a 48 dp remove button where the user may
 *   take the recipe out, and "Planera" in "Vill laga" ([onPlanMeal]).
 * - A grid of rows on wide windows (two columns on a tablet in landscape).
 *
 * @param title the screen title ([BookRecipesUiState.title] for a book).
 * @param onBack null hides the back arrow (beside the books on wide windows, where the list has one).
 * @param onPlanMeal plans a recipe from "Vill laga"; null hides the button (other books, guests).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookRecipesScreen(
    state: BookRecipesUiState,
    title: String,
    onAction: (BookRecipesAction) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onPlanMeal: ((Long) -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = title,
                onNavigateBack = onBack,
                actions = { AppChromeStatusChip(Modifier.padding(end = TandemTheme.spacing.xs)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when {
            state.loading -> LoadingState(modifier = contentModifier)

            state.missing -> EmptyState(
                icon = TandemIcons.CollectionsBookmark,
                title = stringResource(R.string.books_missing_title),
                body = stringResource(R.string.books_missing_body),
                modifier = contentModifier,
            )

            else -> PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { onAction(BookRecipesAction.Refresh) },
                modifier = contentModifier,
            ) {
                RecipeGrid(state = state, onAction = onAction, onOpenRecipe = onOpenRecipe, onPlanMeal = onPlanMeal)
            }
        }
    }
}

@Composable
private fun RecipeGrid(
    state: BookRecipesUiState,
    onAction: (BookRecipesAction) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onPlanMeal: ((Long) -> Unit)?,
) {
    val spacing = TandemTheme.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(ROW_MIN_CELL),
        modifier = Modifier.fillMaxSize().testTag(BOOK_RECIPES_TAG),
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        full("demo") { AppChromeDemoBanner() }
        if (state.readOnly) full("guest") { ReadOnlyBanner(compact = true) }
        full("about") { About(state) }
        if (state.recipes.isEmpty()) {
            full("empty") { Empty(state) }
        } else {
            items(state.recipes, key = { it.id }) { recipe ->
                RecipeRow(
                    recipe = recipe,
                    kind = state.kind,
                    onOpen = { onOpenRecipe(recipe.id) },
                    onRemove = if (state.canRemove(recipe)) ({ onAction(BookRecipesAction.Remove(recipe.id)) }) else null,
                    onPlan = onPlanMeal
                        ?.takeIf { state.kind == RecipeBookKind.WANT_TO_COOK && !state.readOnly }
                        ?.let { plan -> { plan(recipe.id) } },
                )
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** What this list is, and for someone else's book why it cannot be changed (plan 3.7). */
@Composable
private fun About(state: BookRecipesUiState) {
    when {
        state.kind == RecipeBookKind.FAVORITES && state.title.isEmpty() -> InfoCard(
            title = stringResource(R.string.books_favorites_info_title),
            body = stringResource(R.string.books_favorites_info_body),
            icon = TandemIcons.FavoriteFilled,
        )

        state.kind == RecipeBookKind.WANT_TO_COOK -> InfoCard(
            title = stringResource(R.string.books_want_to_cook_info_title),
            body = stringResource(R.string.books_want_to_cook_info_body),
            icon = TandemIcons.BookmarkFilled,
        )

        state.sharedByOther -> InfoCard(
            title = state.ownerName?.let { stringResource(R.string.books_shared_by_title, it) }
                ?: stringResource(R.string.books_shared_by_someone_title),
            body = state.ownerName?.let { stringResource(R.string.books_shared_by_body, it) }
                ?: stringResource(R.string.books_shared_by_someone_body),
            icon = TandemIcons.Lock,
            tone = TandemTone.Warning,
        )

        state.kind == RecipeBookKind.SMART -> InfoCard(
            title = stringResource(R.string.books_smart_info_title),
            body = stringResource(R.string.books_smart_info_body),
            icon = TandemIcons.Tune,
        )

        state.description.isNotBlank() -> Text(
            text = state.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Empty(state: BookRecipesUiState) {
    val (title, body) = when {
        state.kind == RecipeBookKind.FAVORITES && state.title.isEmpty() ->
            R.string.books_empty_favorites_title to R.string.books_empty_favorites_body

        state.kind == RecipeBookKind.WANT_TO_COOK -> R.string.books_empty_want_to_cook_title to R.string.books_empty_want_to_cook_body

        state.kind == RecipeBookKind.SMART -> R.string.books_empty_book_title to R.string.books_empty_smart_body

        !state.isOwn || state.readOnly -> R.string.books_empty_book_title to R.string.books_empty_shared_body

        else -> R.string.books_empty_book_title to R.string.books_empty_book_body
    }
    EmptyState(
        icon = when (state.kind) {
            RecipeBookKind.FAVORITES -> TandemIcons.FavoriteFilled
            RecipeBookKind.WANT_TO_COOK -> TandemIcons.BookmarkFilled
            else -> TandemIcons.CollectionsBookmark
        },
        title = stringResource(title),
        body = stringResource(body),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A recipe row with its remove (and "Planera") buttons beside it: three TalkBack items at most. */
@Composable
private fun RecipeRow(recipe: RecipeOverview, kind: RecipeBookKind, onOpen: () -> Unit, onRemove: (() -> Unit)?, onPlan: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RecipeListItem(recipe = recipe, onClick = onOpen, modifier = Modifier.weight(1f))
        if (onPlan != null) {
            IconButton(onClick = onPlan) {
                Icon(imageVector = TandemIcons.CalendarMonth, contentDescription = stringResource(R.string.books_plan, recipe.name))
            }
        }
        if (onRemove != null) {
            val (icon, label) = when (kind) {
                RecipeBookKind.FAVORITES -> TandemIcons.FavoriteFilled to R.string.books_remove_favorite
                RecipeBookKind.WANT_TO_COOK -> TandemIcons.BookmarkFilled to R.string.books_remove_want_to_cook
                else -> TandemIcons.Close to R.string.books_remove_from_book
            }
            IconButton(onClick = onRemove) {
                Icon(imageVector = icon, contentDescription = stringResource(label, recipe.name), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val ROW_MIN_CELL = 360.dp
