package casa.moller.tandem.feature.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.feature.cook.CookRoute
import casa.moller.tandem.feature.cook.TimersRoute
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.home.TodayRoute
import casa.moller.tandem.feature.mealplan.PlanMealRoute
import casa.moller.tandem.feature.mealplan.WeekRoute
import casa.moller.tandem.feature.mealplan.WeekShoppingRoute
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import casa.moller.tandem.feature.recipes.RecipeDetailPanePlaceholder
import casa.moller.tandem.feature.recipes.RecipeDetailRoute
import casa.moller.tandem.feature.recipes.RecipesRoute
import casa.moller.tandem.feature.recipes.books.BookDetailRoute
import casa.moller.tandem.feature.recipes.books.BooksRoute
import casa.moller.tandem.feature.recipes.books.FavoritesRoute
import casa.moller.tandem.feature.recipes.books.WantToCookRoute
import casa.moller.tandem.feature.shopping.ShoppingHistoryRoute
import casa.moller.tandem.feature.shopping.ShoppingListChoiceRoute
import casa.moller.tandem.feature.shopping.ShoppingRoute
import casa.moller.tandem.feature.shopping.StoreModeRoute
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey

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

    /**
     * Cook mode (full screen on every width). [pageRequest] is a step the open cook mode should
     * turn to (a tapped timer of it), handled once with [onPageRequestHandled].
     */
    @Composable
    fun Cook(key: CookKey, onClose: () -> Unit, pageRequest: Int?, onPageRequestHandled: () -> Unit)

    /**
     * All timers; [onOpenCook] opens cook mode at a timer's recipe and step, offered only for
     * timers of [activeSource] (core:model `TimerSource`).
     */
    @Composable
    fun Timers(onBack: () -> Unit, onOpenCook: (CookTimer) -> Unit, activeSource: String?)

    // --- Phase 2 (grund lane): Handla, Veckan and books (feature:shopping, feature:mealplan,
    // feature:recipes). The lanes fill the routes; the shell's tests show placeholders. ---

    /** The shopping list: the Handla tab root (`listId`/`supermarketId` null) or a `ShoppingListKey`. */
    @Composable
    fun Shopping(
        listId: Long?,
        supermarketId: Long?,
        onOpenStoreMode: (StoreModeKey) -> Unit,
        onOpenListChoice: () -> Unit,
        onOpenHistory: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
    )

    /** Store mode (full screen on every width). */
    @Composable
    fun StoreMode(key: StoreModeKey, onClose: () -> Unit)

    /** The list / store chooser. */
    @Composable
    fun ShoppingListChoice(onChosen: (listId: Long?, supermarketId: Long?) -> Unit, onDismiss: () -> Unit)

    /** The local shopping history. */
    @Composable
    fun ShoppingHistory(onBack: () -> Unit)

    /** The week agenda: the Veckan tab root (`weekStart` null) or a `WeekAgendaKey`. */
    @Composable
    fun Week(
        weekStart: String?,
        onOpenRecipe: (Long) -> Unit,
        onPlanMeal: (PlanMealKey) -> Unit,
        onWeekShopping: (WeekShoppingKey) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    )

    /** The plan-meal sheet. */
    @Composable
    fun PlanMeal(key: PlanMealKey, onDone: () -> Unit)

    /** "Handla för veckan"; [onOpenShoppingList] switches to the Handla tab. */
    @Composable
    fun WeekShopping(key: WeekShoppingKey, onDone: () -> Unit, onOpenShoppingList: () -> Unit)

    /** All recipe books. */
    @Composable
    fun Books(onOpenBook: (Long) -> Unit, onOpenFavorites: () -> Unit, onOpenWantToCook: () -> Unit, onBack: () -> Unit)

    /** One book. */
    @Composable
    fun BookDetail(bookId: Long, onOpenRecipe: (Long) -> Unit, onBack: () -> Unit)

    /** Everyone's favourites. */
    @Composable
    fun Favorites(onOpenRecipe: (Long) -> Unit, onBack: () -> Unit)

    /** "Vill laga"; [onPlanMeal] opens the plan-meal sheet for a recipe. */
    @Composable
    fun WantToCook(onOpenRecipe: (Long) -> Unit, onPlanMeal: (Long) -> Unit, onBack: () -> Unit)
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
    override fun Cook(key: CookKey, onClose: () -> Unit, pageRequest: Int?, onPageRequestHandled: () -> Unit) {
        CookRoute(key = key, onClose = onClose, pageRequest = pageRequest, onPageRequestHandled = onPageRequestHandled)
    }

    @Composable
    override fun Timers(onBack: () -> Unit, onOpenCook: (CookTimer) -> Unit, activeSource: String?) {
        TimersRoute(onBack = onBack, onOpenCook = onOpenCook, activeSource = activeSource)
    }

    // --- Phase 2 (grund lane) ---

    @Composable
    override fun Shopping(
        listId: Long?,
        supermarketId: Long?,
        onOpenStoreMode: (StoreModeKey) -> Unit,
        onOpenListChoice: () -> Unit,
        onOpenHistory: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
    ) {
        ShoppingRoute(
            onOpenStoreMode = onOpenStoreMode,
            onOpenListChoice = onOpenListChoice,
            onOpenHistory = onOpenHistory,
            onOpenRecipe = onOpenRecipe,
            listId = listId,
            supermarketId = supermarketId,
        )
    }

    @Composable
    override fun StoreMode(key: StoreModeKey, onClose: () -> Unit) {
        StoreModeRoute(key = key, onClose = onClose)
    }

    @Composable
    override fun ShoppingListChoice(onChosen: (listId: Long?, supermarketId: Long?) -> Unit, onDismiss: () -> Unit) {
        ShoppingListChoiceRoute(onChosen = onChosen, onDismiss = onDismiss)
    }

    @Composable
    override fun ShoppingHistory(onBack: () -> Unit) {
        ShoppingHistoryRoute(onBack = onBack)
    }

    @Composable
    override fun Week(
        weekStart: String?,
        onOpenRecipe: (Long) -> Unit,
        onPlanMeal: (PlanMealKey) -> Unit,
        onWeekShopping: (WeekShoppingKey) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    ) {
        WeekRoute(
            onOpenRecipe = onOpenRecipe,
            onPlanMeal = onPlanMeal,
            onWeekShopping = onWeekShopping,
            onStartCooking = onStartCooking,
            weekStart = weekStart,
        )
    }

    @Composable
    override fun PlanMeal(key: PlanMealKey, onDone: () -> Unit) {
        PlanMealRoute(key = key, onDone = onDone)
    }

    @Composable
    override fun WeekShopping(key: WeekShoppingKey, onDone: () -> Unit, onOpenShoppingList: () -> Unit) {
        WeekShoppingRoute(key = key, onDone = onDone, onOpenShoppingList = onOpenShoppingList)
    }

    @Composable
    override fun Books(onOpenBook: (Long) -> Unit, onOpenFavorites: () -> Unit, onOpenWantToCook: () -> Unit, onBack: () -> Unit) {
        BooksRoute(onOpenBook = onOpenBook, onOpenFavorites = onOpenFavorites, onOpenWantToCook = onOpenWantToCook, onBack = onBack)
    }

    @Composable
    override fun BookDetail(bookId: Long, onOpenRecipe: (Long) -> Unit, onBack: () -> Unit) {
        BookDetailRoute(bookId = bookId, onOpenRecipe = onOpenRecipe, onBack = onBack)
    }

    @Composable
    override fun Favorites(onOpenRecipe: (Long) -> Unit, onBack: () -> Unit) {
        FavoritesRoute(onOpenRecipe = onOpenRecipe, onBack = onBack)
    }

    @Composable
    override fun WantToCook(onOpenRecipe: (Long) -> Unit, onPlanMeal: (Long) -> Unit, onBack: () -> Unit) {
        WantToCookRoute(onOpenRecipe = onOpenRecipe, onPlanMeal = onPlanMeal, onBack = onBack)
    }
}
