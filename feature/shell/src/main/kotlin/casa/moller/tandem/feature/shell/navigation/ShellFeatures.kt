package casa.moller.tandem.feature.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import casa.moller.tandem.feature.cook.CookRoute
import casa.moller.tandem.feature.cook.TimersRoute
import casa.moller.tandem.feature.cook.navigation.CookKey
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
    /** The Today tab's first screen (feature:home); [onStartCooking] is tonight's "Laga" (Phase 1b). */
    @Composable
    fun Today(onOpenRecipe: (Long) -> Unit, onOpenRecipes: () -> Unit, onOpenWeek: () -> Unit, onStartCooking: (Long, Double?) -> Unit)

    /**
     * The Recipes tab's first screen and list pane (feature:recipes). [selectedRecipeId] is the
     * recipe open in the detail pane beside it (highlighted), null when none.
     */
    @Composable
    fun Recipes(onOpenRecipe: (Long) -> Unit, selectedRecipeId: Long?)

    /**
     * A recipe's detail (feature:recipes): full screen on phones, detail pane on wide windows.
     * [onStartCooking] is "Börja laga" (Phase 1b).
     */
    @Composable
    fun RecipeDetail(
        recipeId: Long,
        onBack: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onOpenOnWeb: (String) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    )

    /** The detail pane before a recipe is picked (wide windows only). */
    @Composable
    fun EmptyRecipePane()

    // --- Phase 1b (kokläge lane): cook mode and the timer list (feature:cook). ---

    /** Cook mode (full screen on every width). */
    @Composable
    fun Cook(key: CookKey, onClose: () -> Unit)

    /** All timers; [onOpenCook] opens cook mode at a timer's recipe and step. */
    @Composable
    fun Timers(onBack: () -> Unit, onOpenCook: (recipeId: Long, step: Int) -> Unit)
}

/** The real feature routes. */
internal object RouteShellFeatures : ShellFeatures {
    @Composable
    override fun Today(
        onOpenRecipe: (Long) -> Unit,
        onOpenRecipes: () -> Unit,
        onOpenWeek: () -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    ) {
        TodayRoute(
            onOpenRecipe = onOpenRecipe,
            onOpenRecipes = onOpenRecipes,
            onOpenWeek = onOpenWeek,
            onStartCooking = onStartCooking,
        )
    }

    @Composable
    override fun Recipes(onOpenRecipe: (Long) -> Unit, selectedRecipeId: Long?) {
        RecipesRoute(onOpenRecipe = onOpenRecipe, selectedRecipeId = selectedRecipeId)
    }

    @Composable
    override fun RecipeDetail(
        recipeId: Long,
        onBack: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onOpenOnWeb: (String) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    ) {
        RecipeDetailRoute(
            recipeId = recipeId,
            onBack = onBack,
            onOpenRecipe = onOpenRecipe,
            onOpenOnWeb = onOpenOnWeb,
            onStartCooking = onStartCooking,
        )
    }

    @Composable
    override fun EmptyRecipePane() {
        RecipeDetailPanePlaceholder()
    }

    @Composable
    override fun Cook(key: CookKey, onClose: () -> Unit) {
        CookRoute(key = key, onClose = onClose)
    }

    @Composable
    override fun Timers(onBack: () -> Unit, onOpenCook: (recipeId: Long, step: Int) -> Unit) {
        TimersRoute(onBack = onBack, onOpenCook = onOpenCook)
    }
}
