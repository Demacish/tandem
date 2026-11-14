package casa.moller.tandem.feature.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.ui.drink.DrinkSuggestionSlot
import casa.moller.tandem.feature.cook.CookRoute
import casa.moller.tandem.feature.cook.TimersRoute
import casa.moller.tandem.feature.cook.kitchen.KitchenRoute
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.home.TodayRoute
import casa.moller.tandem.feature.mealplan.PlanMealRoute
import casa.moller.tandem.feature.mealplan.WeekRoute
import casa.moller.tandem.feature.mealplan.WeekShoppingRoute
import casa.moller.tandem.feature.mealplan.calendar.MealCalendarRoute
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import casa.moller.tandem.feature.pantry.PantryRoute
import casa.moller.tandem.feature.pantry.navigation.PantryDestination
import casa.moller.tandem.feature.pantry.navigation.PantryScanner
import casa.moller.tandem.feature.recipes.RecipeDetailPanePlaceholder
import casa.moller.tandem.feature.recipes.RecipeDetailRoute
import casa.moller.tandem.feature.recipes.RecipesRoute
import casa.moller.tandem.feature.recipes.books.BookDetailRoute
import casa.moller.tandem.feature.recipes.books.BooksRoute
import casa.moller.tandem.feature.recipes.books.FavoritesRoute
import casa.moller.tandem.feature.recipes.books.RecipeCollection
import casa.moller.tandem.feature.recipes.books.WantToCookRoute
import casa.moller.tandem.feature.recipes.drinks.DrinkRoute
import casa.moller.tandem.feature.recipes.drinks.drinkSuggestionSlot
import casa.moller.tandem.feature.recipes.editor.RecipeEditorRoute
import casa.moller.tandem.feature.recipes.navigation.DrinkDestination
import casa.moller.tandem.feature.recipes.navigation.RecipeEditorKey
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import casa.moller.tandem.feature.recipes.recipeimport.RecipeImportRoute
import casa.moller.tandem.feature.scan.ScanRoute
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.scan.navigation.FridgeScanKey
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.navigation.ScanDestination
import casa.moller.tandem.feature.shopping.ShoppingHistoryRoute
import casa.moller.tandem.feature.shopping.ShoppingListChoiceRoute
import casa.moller.tandem.feature.shopping.ShoppingRoute
import casa.moller.tandem.feature.shopping.StoreModeRoute
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import casa.moller.tandem.feature.shopping.recipe.RecipeMissingShoppingSheet
import casa.moller.tandem.feature.shopping.recipe.RecipeShoppingRoute
import casa.moller.tandem.feature.shopping.storereminder.StoreReminderRoute

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
    /**
     * The Today tab's first screen (feature:home); [onStartCooking] is tonight's "Laga" (Phase 1b).
     * Phase 2 (integration lane): [onOpenFavorites] is "Visa alla" on the favourites row, and
     * [onOpenShoppingList] is "Visa listan" after tonight's "Handla" (feature:shopping's sheet).
     * Fas S (hemma-ui lane): [onOpenMakeNow] is "Kan laga nu" (`MakeNowKey`), from "Går ut snart"
     * with the recipes that use soon-expiring foods first (true).
     * Fas 5 (kok lane, #41): [onOpenKitchen] is "Köksskärm" (`KitchenKey`).
     */
    @Composable
    fun Today(
        onOpenRecipe: (Long) -> Unit,
        onOpenRecipes: () -> Unit,
        onOpenWeek: () -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
        onOpenWantToCook: () -> Unit,
        onOpenFavorites: () -> Unit,
        onOpenShoppingList: () -> Unit,
        onOpenMakeNow: (useUpFirst: Boolean) -> Unit,
        onOpenKitchen: () -> Unit,
    )

    /**
     * The Recipes tab's first screen and list pane (feature:recipes). [selectedRecipeId] is the
     * recipe open in the detail pane beside it (highlighted), null when none. [onOpenCollection]
     * (Phase 2, books lane) opens Favoriter, Vill laga or the books from the collections row.
     */
    @Composable
    fun Recipes(
        onOpenRecipe: (Long) -> Unit,
        selectedRecipeId: Long?,
        onOpenCollection: (RecipeCollection) -> Unit,
        onCreateRecipe: () -> Unit,
        onImportRecipe: () -> Unit,
    )

    /**
     * A recipe's detail (feature:recipes): full screen on phones, detail pane on wide windows.
     * [onStartCooking] is "Börja laga" (Phase 1b). [onOpenShoppingList] (Phase 2, koppling lane) is
     * "Visa listan" after "Lägg på inköpslistan" (feature:shopping's sheet in the detail's slot).
     * [onPlanMeal] (fix-ui, plan 6.2 "Receptdetalj: … Planera") plans the recipe in Veckan.
     * Fas S (drycker lane): [onNavigate] opens the pairing picker from "Passar med" / "Passar till".
     * Fas 3 (redigerare lane): [onEdit] opens the editor; [onDeleted] leaves the detail after "Ta bort".
     */
    @Composable
    fun RecipeDetail(
        recipeId: Long,
        onBack: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onOpenOnWeb: (String) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
        onOpenShoppingList: () -> Unit,
        onPlanMeal: (Long) -> Unit,
        onNavigate: (NavKey) -> Unit,
        onEdit: (Long) -> Unit,
        onDeleted: () -> Unit,
    )

    /**
     * Fas 3 (redigerare lane): the recipe editor (feature:recipes), full screen. [onSaved] gets
     * the saved recipe's id (temporary for a new one offline) and whether it was new; [onBack]
     * closes without saving.
     */
    @Composable
    fun RecipeEditor(key: RecipeEditorKey, onSaved: (recipeId: Long, isNew: Boolean) -> Unit, onBack: () -> Unit)

    /**
     * Fas 3 (import lane): "Importera recept" (feature:recipes), full screen; [onOpenEditor] gets
     * the accepted import as the editor's saved-draft JSON (`RecipeEditorKey.importedDraft`) and
     * the photo or PDF it was read from (`RecipeEditorKey.sourcePath`);
     * Fas 4: [onScanText] opens "Fota recept" (feature:scan, read on the device).
     */
    @Composable
    fun RecipeImport(
        key: RecipeImportKey,
        onOpenEditor: (importedDraft: String, sourcePath: String?) -> Unit,
        onBack: () -> Unit,
        onScanText: () -> Unit,
    )

    /** The detail pane before a recipe is picked (wide windows only). */
    @Composable
    fun EmptyRecipePane()

    // --- Phase 1b (kokläge lane): cook mode and the timer list (feature:cook). ---

    /**
     * Cook mode (full screen on every width). [pageRequest] is a page one of its recipes should
     * turn to (a tapped timer of it), handled once with [onPageRequestHandled]. [onOpenShoppingList]
     * (Phase 2, koppling lane) is "Visa listan" after "Lägg på inköpslistan" on the last page.
     * Fas 4 (two recipes at once): [onRecipesChanged] reports the recipes open in it.
     */
    @Composable
    fun Cook(
        key: CookKey,
        onClose: () -> Unit,
        pageRequest: CookPageRequest?,
        onPageRequestHandled: () -> Unit,
        onOpenShoppingList: () -> Unit,
        onRecipesChanged: (Set<Long>) -> Unit,
    )

    /**
     * All timers; [onOpenCook] opens cook mode at a timer's recipe and step, offered only for
     * timers of [activeSource] (core:model `TimerSource`).
     */
    @Composable
    fun Timers(onBack: () -> Unit, onOpenCook: (CookTimer) -> Unit, activeSource: String?)

    /**
     * Fas 5 (kok lane, plan §8.7 #41): the kitchen display (feature:cook), full screen on every
     * width; [onStartCooking] opens cook mode on top of it, [onOpenTimer] a timer's cook mode.
     */
    @Composable
    fun Kitchen(onClose: () -> Unit, onStartCooking: (Long, Double?) -> Unit, onOpenTimer: (CookTimer) -> Unit)

    // --- Phase 2 (grund lane): Handla, Veckan and books (feature:shopping, feature:mealplan,
    // feature:recipes). The lanes fill the routes; the shell's tests show placeholders. ---

    /**
     * The shopping list: the Handla tab root (`listId`/`supermarketId` null) or a `ShoppingListKey`.
     * Fas S (hemma-ui lane): [onOpenUnpack] is "Packa upp" (`UnpackKey`) after shopping (#46).
     * [openAddRequested] opens the add panel (the shortcut "Ny vara"), then [onOpenAddHandled] runs.
     */
    @Composable
    fun Shopping(
        listId: Long?,
        supermarketId: Long?,
        onOpenStoreMode: (StoreModeKey) -> Unit,
        onOpenListChoice: () -> Unit,
        onOpenHistory: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onOpenUnpack: () -> Unit,
        openAddRequested: Boolean,
        onOpenAddHandled: () -> Unit,
    )

    /** Store mode (full screen on every width). */
    @Composable
    fun StoreMode(key: StoreModeKey, onClose: () -> Unit)

    /** The list / store chooser; Fas 5 (butik lane): [onOpenStoreReminder] is "Påminn mig nära en butik" below the stores. */
    @Composable
    fun ShoppingListChoice(onChosen: (listId: Long?, supermarketId: Long?) -> Unit, onDismiss: () -> Unit, onOpenStoreReminder: () -> Unit)

    /** The local shopping history. */
    @Composable
    fun ShoppingHistory(onBack: () -> Unit)

    /**
     * The week agenda: the Veckan tab root (`weekStart` null) or a `WeekAgendaKey`.
     * Fas 5 (kalender lane): [onOpenCalendar] is "Matsedeln i kalendern" in its menu.
     */
    @Composable
    fun Week(
        weekStart: String?,
        onOpenRecipe: (Long) -> Unit,
        onPlanMeal: (PlanMealKey) -> Unit,
        onWeekShopping: (WeekShoppingKey) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
        onOpenCalendar: () -> Unit,
    )

    /** Fas 5 (kalender lane, plan §7 #36): "Matsedeln i kalendern" (feature:mealplan). */
    @Composable
    fun MealCalendar(onBack: () -> Unit)

    /** Fas 5 (butik lane, plan §7 #34): "Påminnelse nära butiken" (feature:shopping). */
    @Composable
    fun StoreReminder(onBack: () -> Unit)

    /** The plan-meal sheet. */
    @Composable
    fun PlanMeal(key: PlanMealKey, onDone: () -> Unit)

    /** "Handla för veckan"; [onOpenShoppingList] switches to the Handla tab. */
    @Composable
    fun WeekShopping(key: WeekShoppingKey, onDone: () -> Unit, onOpenShoppingList: () -> Unit)

    /** All recipe books; [selectedBookId] is the book open beside them on wide windows (books lane). */
    @Composable
    fun Books(
        onOpenBook: (Long) -> Unit,
        onOpenFavorites: () -> Unit,
        onOpenWantToCook: () -> Unit,
        onBack: () -> Unit,
        selectedBookId: Long?,
    )

    /** One book; [onBack] null beside the books on wide windows (books lane). */
    @Composable
    fun BookDetail(bookId: Long, onOpenRecipe: (Long) -> Unit, onBack: (() -> Unit)?)

    /** Everyone's favourites. */
    @Composable
    fun Favorites(onOpenRecipe: (Long) -> Unit, onBack: (() -> Unit)?)

    /** "Vill laga"; [onPlanMeal] opens the plan-meal sheet for a recipe. */
    @Composable
    fun WantToCook(onOpenRecipe: (Long) -> Unit, onPlanMeal: (Long) -> Unit, onBack: (() -> Unit)?)

    // --- Fas S (grund lane): Hemma, scanning and drinks (feature:pantry, feature:scan, feature:recipes),
    // one function per module; [onNavigate] opens any key the shell maps (docs/BYGGE.md "Fas S – kontrakt"). ---

    /**
     * Every pantry destination ("Hemma", a location, an entry, "Packa upp", "Kan laga nu", "Basvaror").
     * hemma-ui lane: the scanners open through [onNavigate] (the shell's keys), and
     * [onOpenShoppingList] is "Visa listan" after "Lägg det som saknas på listan" (the Handla tab).
     * [onStartCooking] is "Laga" in Kan laga nu, the same entry as the recipe's and Today's "Laga"
     * (Fas 4: it joins another recipe's running timers).
     */
    @Composable
    fun Pantry(
        key: PantryDestination,
        onNavigate: (NavKey) -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onBack: (() -> Unit)?,
        onOpenShoppingList: () -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    )

    /**
     * Every scan destination (barcode, receipt, fridge photo, the review, Settings → AI; Fas 4:
     * "Fota recept", whose text [onRecipeText] gets for "Importera recept").
     */
    @Composable
    fun Scan(key: ScanDestination, onNavigate: (NavKey) -> Unit, onBack: () -> Unit, onRecipeText: (String) -> Unit)

    /**
     * Every drink destination ("Drycker", the pairing picker, "Ny dryck"; a drink itself is shown
     * with [RecipeDetail]). drycker lane: [selectedDrinkId] is the drink open beside "Drycker".
     */
    @Composable
    fun Drinks(
        key: DrinkDestination,
        onNavigate: (NavKey) -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onBack: (() -> Unit)?,
        selectedDrinkId: Long?,
    )

    /**
     * Fas S (drycker lane): the drink suggestion under a planned recipe in Idag and Veckan
     * (core:ui `LocalDrinkSuggestion`); null shows none (the shell's tests).
     */
    fun drinkSuggestion(onOpenRecipe: (Long) -> Unit): DrinkSuggestionSlot?
}

/** The real feature routes. */
internal object RouteShellFeatures : ShellFeatures {
    @Composable
    override fun Today(
        onOpenRecipe: (Long) -> Unit,
        onOpenRecipes: () -> Unit,
        onOpenWeek: () -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
        onOpenWantToCook: () -> Unit,
        onOpenFavorites: () -> Unit,
        onOpenShoppingList: () -> Unit,
        onOpenMakeNow: (useUpFirst: Boolean) -> Unit,
        onOpenKitchen: () -> Unit,
    ) {
        TodayRoute(
            onOpenRecipe = onOpenRecipe,
            onOpenRecipes = onOpenRecipes,
            onOpenWeek = onOpenWeek,
            onStartCooking = onStartCooking,
            onOpenWantToCook = onOpenWantToCook,
            onOpenFavorites = onOpenFavorites,
            // Phase 2 (integration lane, plan 6.2): "Handla" on tonight's card.
            tonightShoppingAction = { recipeId, servings, snackbar ->
                RecipeShoppingRoute(recipeId, servings, snackbar, onOpenShoppingList)
            },
            // Fas S (hemma-ui lane): "Kan laga nu" and "Går ut snart".
            onOpenMakeNow = onOpenMakeNow,
            // Fas 5 (kok lane, #41): "Köksskärm".
            onOpenKitchen = onOpenKitchen,
        )
    }

    @Composable
    override fun Recipes(
        onOpenRecipe: (Long) -> Unit,
        selectedRecipeId: Long?,
        onOpenCollection: (RecipeCollection) -> Unit,
        onCreateRecipe: () -> Unit,
        onImportRecipe: () -> Unit,
    ) {
        RecipesRoute(
            onOpenRecipe = onOpenRecipe,
            selectedRecipeId = selectedRecipeId,
            onOpenCollection = onOpenCollection,
            onCreateRecipe = onCreateRecipe,
            onImportRecipe = onImportRecipe,
        )
    }

    @Composable
    override fun RecipeDetail(
        recipeId: Long,
        onBack: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onOpenOnWeb: (String) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
        onOpenShoppingList: () -> Unit,
        onPlanMeal: (Long) -> Unit,
        onNavigate: (NavKey) -> Unit,
        onEdit: (Long) -> Unit,
        onDeleted: () -> Unit,
    ) {
        RecipeDetailRoute(
            recipeId = recipeId,
            onBack = onBack,
            onOpenRecipe = onOpenRecipe,
            onOpenOnWeb = onOpenOnWeb,
            onStartCooking = onStartCooking,
            // Phase 2 (koppling lane, #12): "Lägg på inköpslistan" under the ingredients (Fas 5: in the tin shown).
            shoppingAction = { servings, panSize, snackbar ->
                RecipeShoppingRoute(recipeId, servings, snackbar, onOpenShoppingList, panSize = panSize)
            },
            // fix-ui: "Planera" beside the book buttons (hidden for guests by feature:recipes).
            onPlanMeal = onPlanMeal,
            // Fas S (drycker lane): "Passar med" / "Passar till".
            onNavigate = onNavigate,
            // Fas 3 (redigerare lane): "Redigera" and "Ta bort".
            onEdit = onEdit,
            onDeleted = onDeleted,
        )
    }

    @Composable
    override fun EmptyRecipePane() {
        RecipeDetailPanePlaceholder()
    }

    @Composable
    override fun RecipeEditor(key: RecipeEditorKey, onSaved: (recipeId: Long, isNew: Boolean) -> Unit, onBack: () -> Unit) {
        RecipeEditorRoute(key = key, onSaved = onSaved, onBack = onBack)
    }

    @Composable
    override fun RecipeImport(
        key: RecipeImportKey,
        onOpenEditor: (importedDraft: String, sourcePath: String?) -> Unit,
        onBack: () -> Unit,
        onScanText: () -> Unit,
    ) {
        RecipeImportRoute(key = key, onOpenEditor = onOpenEditor, onBack = onBack, onScanText = onScanText)
    }

    @Composable
    override fun Cook(
        key: CookKey,
        onClose: () -> Unit,
        pageRequest: CookPageRequest?,
        onPageRequestHandled: () -> Unit,
        onOpenShoppingList: () -> Unit,
        onRecipesChanged: (Set<Long>) -> Unit,
    ) {
        CookRoute(
            key = key,
            onClose = onClose,
            pageRequest = pageRequest,
            onPageRequestHandled = onPageRequestHandled,
            onRecipesChanged = onRecipesChanged,
            // Phase 2 (koppling lane, #12): "Lägg på inköpslistan" on the last page (Fas 4: of the shown
            // recipe; Fas 5: in its tin).
            shoppingAction = { recipeId, servings, panSize, snackbar ->
                RecipeShoppingRoute(recipeId, servings, snackbar, onOpenShoppingList, panSize = panSize)
            },
        )
    }

    @Composable
    override fun Timers(onBack: () -> Unit, onOpenCook: (CookTimer) -> Unit, activeSource: String?) {
        TimersRoute(onBack = onBack, onOpenCook = onOpenCook, activeSource = activeSource)
    }

    // --- Fas 5 (kok lane) ---
    @Composable
    override fun Kitchen(onClose: () -> Unit, onStartCooking: (Long, Double?) -> Unit, onOpenTimer: (CookTimer) -> Unit) {
        KitchenRoute(onClose = onClose, onStartCooking = onStartCooking, onOpenTimer = onOpenTimer)
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
        onOpenUnpack: () -> Unit,
        openAddRequested: Boolean,
        onOpenAddHandled: () -> Unit,
    ) {
        ShoppingRoute(
            onOpenStoreMode = onOpenStoreMode,
            onOpenListChoice = onOpenListChoice,
            onOpenHistory = onOpenHistory,
            onOpenRecipe = onOpenRecipe,
            listId = listId,
            supermarketId = supermarketId,
            // Fas S (hemma-ui lane, #46).
            onOpenUnpack = onOpenUnpack,
            openAddRequested = openAddRequested,
            onOpenAddHandled = onOpenAddHandled,
        )
    }

    @Composable
    override fun StoreMode(key: StoreModeKey, onClose: () -> Unit) {
        StoreModeRoute(key = key, onClose = onClose)
    }

    @Composable
    override fun ShoppingListChoice(
        onChosen: (listId: Long?, supermarketId: Long?) -> Unit,
        onDismiss: () -> Unit,
        onOpenStoreReminder: () -> Unit,
    ) {
        ShoppingListChoiceRoute(onChosen = onChosen, onDismiss = onDismiss, onOpenStoreReminder = onOpenStoreReminder)
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
        onOpenCalendar: () -> Unit,
    ) {
        WeekRoute(
            onOpenRecipe = onOpenRecipe,
            onPlanMeal = onPlanMeal,
            onWeekShopping = onWeekShopping,
            onStartCooking = onStartCooking,
            weekStart = weekStart,
            onOpenCalendar = onOpenCalendar,
        )
    }

    @Composable
    override fun MealCalendar(onBack: () -> Unit) {
        MealCalendarRoute(onBack = onBack)
    }

    @Composable
    override fun StoreReminder(onBack: () -> Unit) {
        StoreReminderRoute(onBack = onBack)
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
    override fun Books(
        onOpenBook: (Long) -> Unit,
        onOpenFavorites: () -> Unit,
        onOpenWantToCook: () -> Unit,
        onBack: () -> Unit,
        selectedBookId: Long?,
    ) {
        BooksRoute(
            onOpenBook = onOpenBook,
            onOpenFavorites = onOpenFavorites,
            onOpenWantToCook = onOpenWantToCook,
            onBack = onBack,
            selectedBookId = selectedBookId,
        )
    }

    @Composable
    override fun BookDetail(bookId: Long, onOpenRecipe: (Long) -> Unit, onBack: (() -> Unit)?) {
        BookDetailRoute(bookId = bookId, onOpenRecipe = onOpenRecipe, onBack = onBack)
    }

    @Composable
    override fun Favorites(onOpenRecipe: (Long) -> Unit, onBack: (() -> Unit)?) {
        FavoritesRoute(onOpenRecipe = onOpenRecipe, onBack = onBack)
    }

    @Composable
    override fun WantToCook(onOpenRecipe: (Long) -> Unit, onPlanMeal: (Long) -> Unit, onBack: (() -> Unit)?) {
        WantToCookRoute(onOpenRecipe = onOpenRecipe, onPlanMeal = onPlanMeal, onBack = onBack)
    }

    // --- Fas S (grund lane) ---

    @Composable
    override fun Pantry(
        key: PantryDestination,
        onNavigate: (NavKey) -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onBack: (() -> Unit)?,
        onOpenShoppingList: () -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    ) {
        PantryRoute(
            key = key,
            onNavigate = onNavigate,
            onOpenRecipe = onOpenRecipe,
            onBack = onBack,
            // hemma-ui lane: "Laga" in Kan laga nu (Fas 4: the shell's own entry, so it joins another
            // recipe's running timers), the scanners of feature:scan and the shopping sheet.
            onStartCooking = { recipeId -> onStartCooking(recipeId, null) },
            onOpenScanner = { scanner, locationId ->
                onNavigate(
                    when (scanner) {
                        PantryScanner.BARCODE -> BarcodeScanKey(locationId)
                        PantryScanner.RECEIPT -> ReceiptScanKey(locationId)
                        PantryScanner.FRIDGE -> FridgeScanKey(locationId)
                    },
                )
            },
            missingToList = { recipeId, missingFoodIds, request, snackbar ->
                RecipeMissingShoppingSheet(recipeId, missingFoodIds, request, snackbar, onOpenShoppingList)
            },
        )
    }

    @Composable
    override fun Scan(key: ScanDestination, onNavigate: (NavKey) -> Unit, onBack: () -> Unit, onRecipeText: (String) -> Unit) {
        ScanRoute(key = key, onNavigate = onNavigate, onBack = onBack, onRecipeText = onRecipeText)
    }

    @Composable
    override fun Drinks(
        key: DrinkDestination,
        onNavigate: (NavKey) -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onBack: (() -> Unit)?,
        selectedDrinkId: Long?,
    ) {
        DrinkRoute(key = key, onNavigate = onNavigate, onOpenRecipe = onOpenRecipe, onBack = onBack, selectedDrinkId = selectedDrinkId)
    }

    override fun drinkSuggestion(onOpenRecipe: (Long) -> Unit): DrinkSuggestionSlot = drinkSuggestionSlot(onOpenRecipe)
}
