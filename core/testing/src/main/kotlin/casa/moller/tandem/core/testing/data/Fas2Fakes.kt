package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.account.AccountDirectory
import casa.moller.tandem.core.data.account.AccountRemoval
import casa.moller.tandem.core.data.book.RecipeBookRepository
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.shopping.IngredientParser
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.data.sync.ForegroundSync
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.book.RecipeBookStatus
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.MealTypeDraft
import casa.moller.tandem.core.model.mealplan.WeekShoppingMeal
import casa.moller.tandem.core.model.mealplan.WeekShoppingPreview
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.shopping.Aisle
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import casa.moller.tandem.core.model.shopping.ShoppingList
import casa.moller.tandem.core.model.shopping.ShoppingRecipeLabel
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.model.sync.ForegroundSyncState
import casa.moller.tandem.core.model.sync.ForegroundTarget
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

// Phase 2 (grund lane): fakes of the "handla & planera" contracts in core:data. In memory,
// deterministic and synchronous: every change is visible to observers at once. New objects get
// negative ids (-1, -2, ...) like offline creates; `readOnly = true` makes every write fail with
// DataProblem.READ_ONLY (#42); `failure` makes the next writes fail with that error.

/** Fails with [DataProblem.READ_ONLY] or [failure] before [block] runs. */
private inline fun <T> write(readOnly: Boolean, failure: TandemError?, block: () -> T): Outcome<T> = when {
    readOnly -> Outcome.Failure(DataProblem.READ_ONLY.toError())
    failure != null -> Outcome.Failure(failure)
    else -> Outcome.Success(block())
}

/** In-memory [ShoppingRepository] over [SampleShopping]. */
class FakeShoppingRepository(
    entries: List<ShoppingEntry> = SampleShopping.entries,
    lists: List<ShoppingList> = SampleShopping.lists,
    var readOnly: Boolean = false,
) : ShoppingRepository {
    val entries = MutableStateFlow(entries)
    val lists = MutableStateFlow(lists)
    val pendingCount = MutableStateFlow(0)
    val history = MutableStateFlow<List<ShoppingHistoryItem>>(emptyList())
    val changes = MutableSharedFlow<ShoppingChange>(extraBufferCapacity = 16)

    /** When set, every write fails with it. */
    var failure: TandemError? = null

    /** Recipes put on the list with [addRecipe], in order. */
    val recipeRequests = mutableListOf<RecipeShoppingRequest>()

    /** How often [refresh] was called, and with `full`. */
    val refreshes = mutableListOf<Boolean>()
    private var nextId = -1L

    override fun observeEntries(filter: ShoppingFilter): Flow<List<ShoppingEntry>> =
        entries.map { list -> list.filter { filter.listId == null || filter.listId in it.listIds } }

    override fun observeLists(): Flow<List<ShoppingList>> = lists

    override fun observePendingCount(): Flow<Int> = pendingCount

    override fun observeChanges(): Flow<ShoppingChange> = changes

    override suspend fun add(draft: ShoppingEntryDraft): Outcome<Long> = write(readOnly, failure) {
        val id = nextId--
        val unit = draft.unitName?.let { ShoppingUnit(draft.unitId, it) }
        val food = ShoppingFood(draft.foodId, draft.foodName)
        entries.update { it + ShoppingEntry(id, food, unit, draft.amount, listIds = draft.listIds, pending = true) }
        pendingCount.update { it + 1 }
        id
    }

    override suspend fun addAll(drafts: List<ShoppingEntryDraft>): Outcome<List<Long>> = write(readOnly, failure) {
        drafts.map { draft -> (add(draft) as Outcome.Success).value }
    }

    override suspend fun addRecipe(request: RecipeShoppingRequest): Outcome<Unit> = write(readOnly, failure) {
        recipeRequests += request
    }

    override suspend fun setChecked(ids: Collection<Long>, checked: Boolean): Outcome<Unit> = change(ids) { it.copy(checked = checked) }

    override suspend fun setDelayed(ids: Collection<Long>, until: Instant?): Outcome<Unit> = change(ids) { it.copy(delayUntil = until) }

    override suspend fun update(id: Long, amount: Double?, unitName: String?, foodName: String?): Outcome<Unit> = change(listOf(id)) {
        it.copy(
            amount = amount ?: it.amount,
            unit = unitName?.let { name -> ShoppingUnit(null, name) } ?: it.unit,
            food = foodName?.let { name -> it.food.copy(id = null, name = name) } ?: it.food,
        )
    }

    override suspend fun setLists(ids: Collection<Long>, listIds: Set<Long>): Outcome<Unit> = change(ids) { it.copy(listIds = listIds) }

    override suspend fun remove(ids: Collection<Long>): Outcome<Unit> = write(readOnly, failure) {
        entries.update { list -> list.filterNot { it.id in ids } }
    }

    override suspend fun removeListRecipe(listRecipeId: Long): Outcome<Unit> = write(readOnly, failure) {
        entries.update { list -> list.filterNot { it.recipe?.listRecipeId == listRecipeId } }
    }

    override suspend fun clearChecked(): Outcome<Unit> = write(readOnly, failure) {
        entries.update { list -> list.filterNot { it.checked } }
    }

    override suspend fun refresh(full: Boolean): Outcome<Unit> {
        refreshes += full
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
    }

    override suspend fun shareText(filter: ShoppingFilter): Outcome<String> = Outcome.Success(
        entries.value.filter { !it.checked && (filter.listId == null || filter.listId in it.listIds) }.joinToString("\n") { entry ->
            listOfNotNull(entry.amount.takeIf { it > 0 }?.let { formatAmount(it) }, entry.unit?.name, entry.food.name).joinToString(" ")
        },
    )

    override fun observeSuggestions(query: String, limit: Int): Flow<List<String>> = entries.map { list ->
        (list.map { it.food.name } + SampleShopping.foodNames).distinct()
            .filter { it.startsWith(query, ignoreCase = true) }
            .take(limit)
    }

    override fun observeHistory(): Flow<List<ShoppingHistoryItem>> = history

    private fun change(ids: Collection<Long>, transform: (ShoppingEntry) -> ShoppingEntry): Outcome<Unit> = write(readOnly, failure) {
        entries.update { list -> list.map { if (it.id in ids) transform(it).copy(pending = true) else it } }
        pendingCount.update { it + ids.size }
    }

    private fun formatAmount(amount: Double): String = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
}

/** In-memory [SupermarketRepository] over [SampleShopping]. */
class FakeSupermarketRepository(
    supermarkets: List<Supermarket> = SampleShopping.supermarkets,
    categories: List<SupermarketCategory> = SampleShopping.categories,
    var readOnly: Boolean = false,
) : SupermarketRepository {
    val supermarkets = MutableStateFlow(supermarkets)
    val categories = MutableStateFlow(categories)
    val selected = MutableStateFlow<Long?>(null)

    /** Food id -> category id set with [setFoodCategory]; food id -> on hand set with [setFoodOnHand]. */
    val foodCategories = mutableMapOf<Long, Long?>()
    val foodsOnHand = mutableMapOf<Long, Boolean>()
    var failure: TandemError? = null

    override fun observeSupermarkets(): Flow<List<Supermarket>> = supermarkets

    override fun observeCategories(): Flow<List<SupermarketCategory>> = categories

    override fun observeSelectedSupermarket(): Flow<Long?> = selected

    override suspend fun selectSupermarket(id: Long?): Outcome<Unit> {
        selected.value = id
        return Outcome.Success(Unit)
    }

    override suspend fun setFoodCategory(foodId: Long, categoryId: Long?): Outcome<Unit> = write(readOnly, failure) {
        foodCategories[foodId] = categoryId
    }

    override suspend fun setFoodOnHand(foodId: Long, onHand: Boolean): Outcome<Unit> = write(readOnly, failure) {
        foodsOnHand[foodId] = onHand
    }

    override suspend fun reorderAisles(supermarketId: Long, relationIdsInOrder: List<Long>): Outcome<Unit> = write(readOnly, failure) {
        supermarkets.update { list ->
            list.map { store ->
                if (store.id != supermarketId) {
                    store
                } else {
                    val byId = store.aisles.associateBy { it.relationId }
                    store.copy(aisles = relationIdsInOrder.mapIndexedNotNull { index, id -> byId[id]?.copy(order = index) })
                }
            }
        }
    }
}

/**
 * [IngredientParser] with a tiny local parser (a number with `,` or `.`, a known unit, the rest;
 * text after a comma that is not a decimal comma is the note). [serverResults] answers [parse] for known lines instead.
 */
class FakeIngredientParser(private val units: Set<String> = SampleShopping.unitNames) : IngredientParser {
    val serverResults = mutableMapOf<String, ParsedIngredient>()
    val parsed = mutableListOf<List<String>>()

    override suspend fun parse(lines: List<String>): Outcome<List<ParsedIngredient>> {
        parsed += lines
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return Outcome.Failure(TandemError.BadRequest())
        return Outcome.Success(clean.map { serverResults[it] ?: requireNotNull(parseLocally(it)) })
    }

    override fun parseLocally(line: String): ParsedIngredient? {
        val text = line.trim()
        if (text.isEmpty()) return null
        // A comma starts the note unless it is a decimal comma ("1,5 kg").
        val (main, note) = text.split(NOTE_COMMA, limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
        val words = main.split(' ').filter { it.isNotEmpty() }
        val amount = words.firstOrNull()?.replace(',', '.')?.toDoubleOrNull()
        val rest = if (amount != null) words.drop(1) else words
        val unit = rest.firstOrNull()?.takeIf { it.lowercase() in units && rest.size > 1 }
        val food = (if (unit != null) rest.drop(1) else rest).joinToString(" ")
        return ParsedIngredient(text, amount ?: 0.0, unit, food.ifEmpty { main }, note)
    }

    private companion object {
        val NOTE_COMMA = Regex(",(?!\\d)")
    }
}

/** In-memory [MealPlanRepository]. Meals are stored by id; a week shows the meals that touch its days. */
class FakeMealPlanRepository(
    meals: List<MealPlanEntry> = emptyList(),
    mealTypes: List<MealType> = SampleShopping.mealTypes,
    var readOnly: Boolean = false,
) : MealPlanRepository {
    val meals = MutableStateFlow(meals)
    val mealTypes = MutableStateFlow(mealTypes)
    val conflicts = MutableStateFlow<List<MealPlanConflict>>(emptyList())
    var failure: TandemError? = null
    val drafts = mutableListOf<MealPlanDraft>()
    val weekSelections = mutableListOf<WeekShoppingSelection>()
    val resolved = mutableListOf<Pair<MealPlanConflict, Boolean>>()
    private var nextId = -1L

    override fun observeWeek(start: LocalDate): Flow<MealPlanWeek> = meals.map { list ->
        val days = (0 until MealPlanWeek.DAYS).map { offset ->
            val day = start.plus(offset, DateTimeUnit.DAY)
            MealPlanDay(day, list.filter { it.isOn(day) }.sortedBy { it.mealType.order })
        }
        MealPlanWeek(start, days)
    }

    override fun observeMeal(id: Long): Flow<MealPlanEntry?> = meals.map { list -> list.firstOrNull { it.id == id } }

    override fun observeMealTypes(): Flow<List<MealType>> = mealTypes.map { list -> list.sortedBy { it.order } }

    override suspend fun create(draft: MealPlanDraft): Outcome<Long> = write(readOnly, failure) {
        drafts += draft
        val id = nextId--
        val type = mealTypes.value.firstOrNull { it.id == draft.mealTypeId } ?: MealType(draft.mealTypeId, "")
        val entry = MealPlanEntry(
            id = id,
            title = draft.title,
            recipe = draft.recipeId?.let { RecipeOverview(it, "") },
            servings = draft.servings,
            note = draft.note,
            fromDate = draft.date,
            toDate = draft.toDate ?: draft.date,
            mealType = type,
            onShoppingList = draft.addToShopping,
            pending = true,
        )
        meals.update { it + entry }
        id
    }

    override suspend fun update(id: Long, update: MealPlanUpdate): Outcome<Unit> = write(readOnly, failure) {
        meals.update { list ->
            list.map { meal ->
                if (meal.id != id) return@map meal
                meal.copy(
                    fromDate = update.fromDate ?: meal.fromDate,
                    toDate = update.toDate ?: meal.toDate,
                    mealType = update.mealTypeId?.let { typeId -> mealTypes.value.firstOrNull { it.id == typeId } } ?: meal.mealType,
                    title = update.title ?: meal.title,
                    servings = update.servings ?: meal.servings,
                    note = update.note ?: meal.note,
                    pending = true,
                )
            }
        }
    }

    override suspend fun delete(id: Long): Outcome<Unit> = write(readOnly, failure) {
        meals.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun createMealType(draft: MealTypeDraft): Outcome<Long> = write(readOnly, failure) {
        val id = nextId--
        mealTypes.update { it + MealType(id, draft.name, it.size, draft.time, draft.color) }
        id
    }

    override fun observeConflicts(): Flow<List<MealPlanConflict>> = conflicts

    override suspend fun resolveConflict(conflict: MealPlanConflict, keepMine: Boolean): Outcome<Unit> {
        resolved += conflict to keepMine
        conflicts.update { it - conflict }
        return Outcome.Success(Unit)
    }

    override suspend fun refresh(): Outcome<Unit> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)

    override suspend fun weekShoppingPreview(start: LocalDate): Outcome<WeekShoppingPreview> {
        val end = start.plus(MealPlanWeek.DAYS - 1, DateTimeUnit.DAY)
        val inWeek = meals.value.filter { it.recipe != null && it.fromDate <= end && it.toDate >= start }
        return Outcome.Success(
            WeekShoppingPreview(inWeek.map { WeekShoppingMeal(it, alreadyOnList = it.onShoppingList, selected = !it.onShoppingList) }),
        )
    }

    override suspend fun addWeekToShopping(selection: WeekShoppingSelection): Outcome<Unit> = write(readOnly, failure) {
        weekSelections += selection
        meals.update { list -> list.map { if (it.id in selection.ingredientIdsByMeal) it.copy(onShoppingList = true) else it } }
    }
}

/**
 * In-memory [RecipeBookRepository]. [recipes] are the recipes the books can point at; the active
 * user is [userId]. Favourites are the [RecipeBookKind.FAVORITES] books' entries; "Vill laga" is
 * [wantToCook].
 */
class FakeRecipeBookRepository(
    books: List<RecipeBook> = emptyList(),
    private val recipes: List<RecipeOverview> = SampleRecipes.overviews,
    val userId: Long = 1,
    var readOnly: Boolean = false,
) : RecipeBookRepository {
    val books = MutableStateFlow(books)

    /** Book id -> recipe ids, in order. */
    val entries = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())
    val wantToCook = MutableStateFlow<Set<Long>>(emptySet())
    var failure: TandemError? = null
    private var nextId = -1L

    private fun recipesOf(ids: Collection<Long>): List<RecipeOverview> =
        ids.distinct().mapNotNull { id -> recipes.firstOrNull { it.id == id } }

    override fun observeBooks(): Flow<List<RecipeBook>> = combine(books, entries) { list, byBook ->
        list.map { it.copy(recipeCount = byBook[it.id].orEmpty().size) }
    }

    override fun observeBook(id: Long): Flow<RecipeBook?> = observeBooks().map { list -> list.firstOrNull { it.id == id } }

    override fun observeRecipes(bookId: Long): Flow<List<RecipeOverview>> = entries.map { recipesOf(it[bookId].orEmpty()) }

    override fun observeFavorites(): Flow<List<RecipeOverview>> = combine(books, entries) { list, byBook ->
        recipesOf(list.filter { it.kind == RecipeBookKind.FAVORITES }.flatMap { byBook[it.id].orEmpty() })
    }

    override fun observeFavoriteIds(): Flow<Set<Long>> = combine(books, entries) { list, byBook ->
        list.filter { it.kind == RecipeBookKind.FAVORITES && it.isOwn }.flatMap { byBook[it.id].orEmpty() }.toSet()
    }

    override fun observeWantToCook(): Flow<List<RecipeOverview>> = wantToCook.map { recipesOf(it) }

    override fun observeStatus(recipeId: Long): Flow<RecipeBookStatus> = combine(books, entries, wantToCook) { list, byBook, want ->
        val containing = list.filter { recipeId in byBook[it.id].orEmpty() }
        val favorites = containing.filter { it.kind == RecipeBookKind.FAVORITES }
        RecipeBookStatus(
            recipeId = recipeId,
            isFavorite = favorites.any { it.isOwn },
            favoriteOf = favorites.filterNot { it.isOwn }.mapNotNull { it.ownerName },
            wantToCook = recipeId in want,
            bookIds = containing.map { it.id }.toSet(),
        )
    }

    override suspend fun createBook(name: String, description: String): Outcome<Long> = write(readOnly, failure) {
        val id = nextId--
        books.update { it + RecipeBook(id, name, description, ownerId = userId, isOwn = true) }
        id
    }

    override suspend fun addToBook(bookId: Long, recipeId: Long): Outcome<Unit> = editBook(bookId) { it + recipeId }

    override suspend fun removeFromBook(bookId: Long, recipeId: Long): Outcome<Unit> = editBook(bookId) { it - recipeId }

    override suspend fun setFavorite(recipeId: Long, favorite: Boolean): Outcome<Unit> {
        if (readOnly) return Outcome.Failure(DataProblem.READ_ONLY.toError())
        val own = books.value.firstOrNull { it.kind == RecipeBookKind.FAVORITES && it.isOwn }
            ?: RecipeBook(nextId--, "Favoriter", kind = RecipeBookKind.FAVORITES, ownerId = userId, isOwn = true).also { book ->
                books.update { it + book }
            }
        return editBook(own.id) { if (favorite) it + recipeId else it - recipeId }
    }

    override suspend fun setWantToCook(recipeId: Long, wantToCook: Boolean): Outcome<Unit> = write(readOnly, failure) {
        this.wantToCook.update { if (wantToCook) it + recipeId else it - recipeId }
    }

    override suspend fun refresh(): Outcome<Unit> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)

    private fun editBook(bookId: Long, change: (List<Long>) -> List<Long>): Outcome<Unit> {
        val book = books.value.firstOrNull { it.id == bookId }
        if (book != null && !book.canEdit && !readOnly) return Outcome.Failure(DataProblem.NOT_OWNER.toError())
        return write(readOnly, failure) {
            entries.update { map -> map + (bookId to change(map[bookId].orEmpty()).distinct()) }
        }
    }
}

/** [AccountDirectory] over a fixed list of accounts; [switchTo] marks another one active. */
class FakeAccountDirectory(accounts: List<AccountSummary> = emptyList(), household: Household? = null) : AccountDirectory {
    val accounts = MutableStateFlow(accounts)
    val household = MutableStateFlow(household)
    val switches = mutableListOf<String>()

    override fun observeAccounts(): Flow<List<AccountSummary>> = accounts

    override suspend fun switchTo(accountId: String): Outcome<Unit> {
        failure?.let { return Outcome.Failure(it) }
        if (accounts.value.none { it.account.id == accountId }) return Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        switches += accountId
        accounts.update { list -> list.map { it.copy(isActive = it.account.id == accountId) } }
        return Outcome.Success(Unit)
    }

    override fun observeHousehold(): Flow<Household?> = household

    override suspend fun refreshHousehold(): Outcome<Household?> = Outcome.Success(household.value)

    // --- Phase 2 (konto lane): removal with the unsent-changes rule, and the export. ---

    /** Every `remove` call as (account id, revoke, discard), in order. */
    val removals = mutableListOf<Triple<String, Boolean, Boolean>>()

    /** Every account whose changes were exported, in order. */
    val exports = mutableListOf<String>()

    /** What a successful removal says about the token. */
    var revocation: TokenRevocation = TokenRevocation.REVOKED

    /** Set to make `remove` and `switchTo` fail. */
    var failure: TandemError? = null

    override suspend fun remove(accountId: String, revokeToken: Boolean, discardPendingChanges: Boolean): Outcome<AccountRemoval> {
        failure?.let { return Outcome.Failure(it) }
        val account = accounts.value.firstOrNull { it.account.id == accountId }
            ?: return Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        removals += Triple(accountId, revokeToken, discardPendingChanges)
        if (!discardPendingChanges && account.pendingChanges > 0) {
            return Outcome.Success(AccountRemoval.PendingChanges(account.pendingChanges))
        }
        val remaining = accounts.value.filterNot { it.account.id == accountId }
        val active = remaining.firstOrNull { it.isActive } ?: remaining.firstOrNull()?.takeIf { account.isActive }
        accounts.value = remaining.map { it.copy(isActive = it.account.id == active?.account?.id) }
        return Outcome.Success(AccountRemoval.Removed(revocation, active?.account))
    }

    override suspend fun exportPendingChanges(accountId: String): Outcome<String> {
        exports += accountId
        val count = accounts.value.firstOrNull { it.account.id == accountId }?.pendingChanges ?: 0
        return Outcome.Success("{\"format\": \"tandem-pending-changes\", \"count\": $count}")
    }
}

/** [SpacePermissionsRepository] with settable permissions (default: a normal member). */
class FakeSpacePermissionsRepository(initial: SpacePermissions = SpacePermissions(SpaceRole.USER)) : SpacePermissionsRepository {
    val state = MutableStateFlow(initial)

    override val permissions: Flow<SpacePermissions> = state

    /** Turns the active user into a guest (read-only) or back. */
    fun setGuest(guest: Boolean) {
        state.value = state.value.copy(role = if (guest) SpaceRole.GUEST else SpaceRole.USER)
    }
}

/** [ForegroundSync] that records which targets are kept fresh, and for how long (collectors). */
class FakeForegroundSync(state: ForegroundSyncState = ForegroundSyncState(active = true)) : ForegroundSync {
    val state = MutableStateFlow(state)

    /** Every target collection started, in order. */
    val started = mutableListOf<ForegroundTarget>()

    override fun keepFresh(target: ForegroundTarget): Flow<ForegroundSyncState> {
        started += target
        return state
    }
}

/** Deterministic Phase 2 sample data: a few entries in Swedish, a store with three aisles. */
object SampleShopping {
    val categories = listOf(
        SupermarketCategory(1, "Grönsaker"),
        SupermarketCategory(2, "Mejeri"),
        SupermarketCategory(3, "Övrigt"),
    )

    val supermarkets = listOf(
        Supermarket(
            id = 1,
            name = "Testbutiken",
            aisles = categories.mapIndexed { index, category -> Aisle(10L + index, category, index) },
        ),
    )

    val lists = listOf(ShoppingList(1, "Veckohandling", color = "#2E7D32"))

    val household = Household(1, "Hemma")

    val mealTypes = listOf(MealType(1, "Middag", 0), MealType(2, "Lunch", 1))

    val unitNames = setOf("dl", "l", "g", "kg", "msk", "tsk", "st", "paket")

    val foodNames = listOf("mjölk", "grädde", "potatis", "smör", "ägg", "tandkräm", "kaffe")

    private val gratin = ShoppingRecipeLabel(listRecipeId = 3, recipeId = 14, recipeName = "Potatisgratäng", servings = 4.0)

    val entries = listOf(
        ShoppingEntry(1, ShoppingFood(19, "mjölk", categoryId = 2), ShoppingUnit(4, "l"), 2.0),
        ShoppingEntry(2, ShoppingFood(39, "grädde", categoryId = 2), ShoppingUnit(5, "dl"), 3.0, recipe = gratin, ingredientId = 74),
        ShoppingEntry(3, ShoppingFood(4, "potatis", "potatisar", categoryId = 1), ShoppingUnit(2, "kg"), 1.0, recipe = gratin),
        ShoppingEntry(4, ShoppingFood(42, "tandkräm"), amount = 1.0, addedBy = "Erik", addedById = 3),
        ShoppingEntry(5, ShoppingFood(18, "ägg"), ShoppingUnit(6, "st"), 6.0, checked = true, listIds = setOf(1)),
    )

    /** An instant to test "köp senare" against (2000-01-15 18:00 UTC, the sample evening). */
    val now: Instant = Instant.parse("2000-01-15T18:00:00Z")
}

/** A [MealPlanEntry] for tests (dinner by default). */
fun sampleMeal(
    id: Long,
    date: LocalDate,
    recipe: RecipeOverview? = null,
    title: String = "",
    type: MealType = SampleShopping.mealTypes.first(),
): MealPlanEntry = MealPlanEntry(id = id, title = title, recipe = recipe, fromDate = date, mealType = type)
