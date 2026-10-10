package casa.moller.tandem.feature.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import casa.moller.tandem.feature.home.TodayRoute
import casa.moller.tandem.feature.recipes.RecipeDetailPanePlaceholder
import casa.moller.tandem.feature.recipes.RecipeDetailRoute
import casa.moller.tandem.feature.recipes.RecipesRoute

/**
 * The feature screens the shell hosts (navigation contract, docs/BYGGE.md "Navigering"). Each
 * function shows one feature route; the shell maps the callbacks to Navigation 3 keys, so the
 * features never know about each other or about the shell.
 *
 * Production uses [RouteShellFeatures]. Shell tests pass placeholders, so the shell's own tests
 * never need Hilt even when the routes create ViewModels with `hiltViewModel()`.
 */
@Stable
internal interface ShellFeatures {
    /** The Today tab's first screen (feature:home). */
    @Composable
    fun Today(onOpenRecipe: (Long) -> Unit, onOpenRecipes: () -> Unit, onOpenWeek: () -> Unit)

    /** The Recipes tab's first screen and list pane (feature:recipes). */
    @Composable
    fun Recipes(onOpenRecipe: (Long) -> Unit)

    /** A recipe's detail (feature:recipes): full screen on phones, detail pane on wide windows. */
    @Composable
    fun RecipeDetail(recipeId: Long, onBack: () -> Unit, onOpenRecipe: (Long) -> Unit, onOpenOnWeb: (String) -> Unit)

    /** The detail pane before a recipe is picked (wide windows only). */
    @Composable
    fun EmptyRecipePane()
}

/** The real feature routes. */
internal object RouteShellFeatures : ShellFeatures {
    @Composable
    override fun Today(onOpenRecipe: (Long) -> Unit, onOpenRecipes: () -> Unit, onOpenWeek: () -> Unit) {
        TodayRoute(onOpenRecipe = onOpenRecipe, onOpenRecipes = onOpenRecipes, onOpenWeek = onOpenWeek)
    }

    @Composable
    override fun Recipes(onOpenRecipe: (Long) -> Unit) {
        RecipesRoute(onOpenRecipe = onOpenRecipe)
    }

    @Composable
    override fun RecipeDetail(recipeId: Long, onBack: () -> Unit, onOpenRecipe: (Long) -> Unit, onOpenOnWeb: (String) -> Unit) {
        RecipeDetailRoute(recipeId = recipeId, onBack = onBack, onOpenRecipe = onOpenRecipe, onOpenOnWeb = onOpenOnWeb)
    }

    @Composable
    override fun EmptyRecipePane() {
        RecipeDetailPanePlaceholder()
    }
}
