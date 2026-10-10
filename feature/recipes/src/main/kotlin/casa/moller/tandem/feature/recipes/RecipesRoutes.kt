package casa.moller.tandem.feature.recipes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip

/**
 * The Recipes tab's first screen: the recipe list (plan 6.2 "Receptlista", feature #3).
 *
 * Navigation contract (docs/BYGGE.md "Navigering"): the shell shows this as the list pane of a
 * list-detail scene; [onOpenRecipe] opens the recipe detail full screen on phones and in the
 * detail pane beside the list on wider windows (the shell decides).
 *
 * PLACEHOLDER (grund lane): the UI lane replaces the body with the real list, keeping this
 * signature. The ViewModel (RecipeRepository from core:data) goes behind this function.
 */
@Composable
fun RecipesRoute(onOpenRecipe: (recipeId: Long) -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.recipes_title),
        bodyTitle = stringResource(R.string.recipes_placeholder_title),
        body = stringResource(R.string.recipes_placeholder_body),
        modifier = modifier,
        showDemoBanner = true,
    )
}

/**
 * The recipe detail (plan 6.2 "Receptdetalj", feature #4) for [recipeId].
 *
 * @param onBack closes the detail (system back is handled by the shell).
 * @param onOpenRecipe opens another recipe, e.g. a step's sub-recipe (pushed on top).
 * @param onOpenOnWeb opens a URL in the browser ("Open on the web" for editing, the source URL).
 *
 * PLACEHOLDER (grund lane): the UI lane replaces the body, keeping this signature. Phase 1b adds
 * `onStartCooking`.
 */
@Composable
fun RecipeDetailRoute(
    recipeId: Long,
    onBack: () -> Unit,
    onOpenRecipe: (recipeId: Long) -> Unit,
    onOpenOnWeb: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.recipe_detail_title),
        bodyTitle = stringResource(R.string.recipe_detail_placeholder_title, recipeId),
        body = stringResource(R.string.recipe_detail_placeholder_body),
        modifier = modifier,
        onNavigateBack = onBack,
    )
}

/**
 * The empty detail pane beside the list on medium/expanded widths before a recipe is picked
 * ("Välj ett recept"). Also replaced by the UI lane.
 */
@Composable
fun RecipeDetailPanePlaceholder(modifier: Modifier = Modifier) {
    EmptyState(
        icon = TandemIcons.MenuBook,
        title = stringResource(R.string.recipe_pane_empty_title),
        body = stringResource(R.string.recipe_pane_empty_body),
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun PlaceholderScreen(
    title: String,
    bodyTitle: String,
    body: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    showDemoBanner: Boolean = false,
) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = title,
                onNavigateBack = onNavigateBack,
                actions = { AppChromeStatusChip(Modifier.padding(end = spacing.md)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showDemoBanner) {
                AppChromeDemoBanner(
                    Modifier
                        .padding(top = spacing.sm)
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth),
                )
            }
            EmptyState(
                icon = TandemIcons.MenuBook,
                title = bodyTitle,
                body = body,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
