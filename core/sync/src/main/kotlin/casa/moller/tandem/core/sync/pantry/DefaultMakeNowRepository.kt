package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.MakeNowEntity
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.domain.pantry.FoodAvailability
import casa.moller.tandem.core.domain.pantry.MakeNowGroups
import casa.moller.tandem.core.domain.pantry.MissingIngredients
import casa.moller.tandem.core.domain.pantry.UseUpRanking
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.makenow.MissingFood
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.network.dto.RecipeOverviewDto
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.substituteIdsOf
import casa.moller.tandem.core.sync.remote.toOverviewWrite
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * [MakeNowRepository]: "Kan laga nu" (#23) and "Använd upp" (#28), plan §8.8.
 *
 * - **Groups from the server:** three requests `recipe?makenow=0`, `=1` and `=2` (an INTEGER, FACTS
 *   "Fas S a"); a recipe belongs to the smallest N that lists it (core:domain [MakeNowGroups]). The
 *   answer is stored per account in `make_now` and shown offline (`stale` after a failed refresh).
 *   The filters (keywords, time) apply on the device to the stored groups, so changing a filter
 *   costs no request; Tandoor's recipe search has no meal type filter, so [MakeNowFilter.mealTypeId]
 *   is not applied (the UI may offer meal types through keywords).
 * - **When:** on open (collecting [observe]), after a change of the pantry or "har hemma" (a local
 *   write, a sent outbox row, a read that brought someone else's change), at most once a minute
 *   unless forced (plan 4.1; three requests of 100 per page, through the RateLimiter like every call).
 * - **Not shown:** drinks (a recipe with the keyword `Dryck`; a recipe without ingredients is always
 *   in `makenow=0`) and recipes whose downloaded detail has no food at all.
 * - **Missing foods** are computed on the device with core:domain [MissingIngredients] from the
 *   stored detail against "har hemma", the household's stock and substitutes; a detail that is not
 *   stored is fetched only when [missingFoods] asks for it (a row is shown), at most
 *   [MAX_PENDING_DETAILS] at a time, through `DetailFetcher`'s background queue.
 * - **Order:** "Använd upp" first (core:domain [UseUpRanking]: a food expiring within 3 days), then
 *   by name like the server's list.
 * - **Demo mode:** the groups are computed from the demo recipes and the demo pantry; no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultMakeNowRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val details: DetailFetcher,
    private val network: NetworkStatus,
    private val clock: SyncClock,
    private val pantry: OfflineFirstPantryRepository,
    private val changes: PantryChanges,
) : MakeNowRepository {
    private data class Status(val refreshing: Boolean = false, val stale: Boolean = false, val lastFetchMillis: Long? = null)

    private val status = MutableStateFlow<Map<String, Status>>(emptyMap())
    private val refreshLock = Mutex()
    private val pendingDetails = AtomicInteger()

    /** "Överraska mig"; tests make it predictable. */
    internal var random: Random = Random.Default

    private val collator: Collator = Collator.getInstance(Locale.getDefault())

    override fun observe(filter: MakeNowFilter, today: LocalDate): Flow<MakeNowState> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(MakeNowState())
            channelFlow {
                // On open, and after every change of the pantry (at most once a minute, the newest change wins).
                launch { refreshSource(source, force = false, background = true) }
                launch {
                    changes.changes.filter { it == source.accountId }.collectLatest {
                        val last = status.value[source.accountId]?.lastFetchMillis
                        val wait = if (last == null) 0 else last + MIN_INTERVAL_MILLIS - clock.nowMillis()
                        if (wait > 0) delay(wait.coerceAtMost(MIN_INTERVAL_MILLIS))
                        refreshSource(source, force = false, background = true)
                    }
                }
                state(source, filter, today).collect { send(it) }
            }
        }
        .catch { emit(MakeNowState(stale = true)) }
        .distinctUntilChanged()

    override suspend fun refresh(force: Boolean): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> refreshSource(source, force, background = !force)
            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    override suspend fun missingFoods(recipeId: Long): Outcome<List<MissingFood>> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val database = databases.get(source.kind)
        val dao = database.makeNowDao()
        if (!dao.hasDetail(source.accountId, recipeId)) {
            val remote = source as? ActiveSource.Remote ?: return@catchingUnexpected Outcome.Failure(TandemError.NotFound)
            if (!network.online.value) return@catchingUnexpected Outcome.Failure(TandemError.NetworkUnavailable)
            if (pendingDetails.incrementAndGet() > MAX_PENDING_DETAILS) {
                pendingDetails.decrementAndGet()
                return@catchingUnexpected Outcome.Failure(TandemError.Throttled(null))
            }
            val fetched = try {
                details.fetch(remote, recipeId, isBackground = true)
            } finally {
                pendingDetails.decrementAndGet()
            }
            if (fetched is Outcome.Failure) return@catchingUnexpected fetched
        }
        val ingredients = dao.getIngredients(source.accountId, recipeId).map { it.toModel() }
        val missing = MissingIngredients.missing(ingredients, availability(source, database).first())
        dao.setMissingFoods(source.accountId, recipeId, MissingFoodsJson.encode(missing))
        Outcome.Success(missing)
    }

    override suspend fun surprise(group: MakeNowGroup, filter: MakeNowFilter): Outcome<MakeNowRecipe?> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        // Random within the stored group with the same filters as the screen (works offline and in demo mode).
        val recipes = state(source, filter, clock.today()).first().recipes(group)
        Outcome.Success(if (recipes.isEmpty()) null else recipes[random.nextInt(recipes.size)])
    }

    // ------------------------------------------------------------------------------------ refresh

    private suspend fun refreshSource(source: ActiveSource.Stored, force: Boolean, background: Boolean): Outcome<Unit> =
        refreshLock.withLock { refreshLocked(source, force, background) }

    private suspend fun refreshLocked(source: ActiveSource.Stored, force: Boolean, background: Boolean): Outcome<Unit> {
        val accountId = source.accountId
        val last = status.value[accountId]?.lastFetchMillis
        val now = clock.nowMillis()
        if (!force && last != null && now - last in 0 until MIN_INTERVAL_MILLIS) return Outcome.Success(Unit)
        setStatus(accountId) { it.copy(refreshing = true) }
        val result = when (source) {
            is ActiveSource.Remote -> refreshRemote(source, background)
            ActiveSource.Demo -> refreshLocally(source)
        }
        setStatus(accountId) {
            val fetched = if (result is Outcome.Success) now else it.lastFetchMillis
            Status(refreshing = false, stale = result is Outcome.Failure, lastFetchMillis = fetched)
        }
        return result
    }

    private fun setStatus(accountId: String, change: (Status) -> Status) {
        status.update { all -> all + (accountId to change(all[accountId] ?: Status())) }
    }

    private suspend fun refreshRemote(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> {
        if (!network.online.value) return Outcome.Failure(TandemError.NetworkUnavailable)
        val answers = mutableMapOf<Int, List<RecipeOverviewDto>>()
        for (group in MakeNowGroup.entries) {
            val listed = caller.call(remote, background) {
                val api: TandoorApi = this
                Paginator.fetchAll(maxPages = MAX_PAGES) { page ->
                    api.recipes.list(RecipeListQuery(makeNow = group.missing, pageSize = RecipeListQuery.MAX_PAGE_SIZE), page)
                }
            }
            when (listed) {
                is Outcome.Failure -> return listed
                is Outcome.Success -> answers[group.missing] = listed.value.items
            }
        }
        if (sources.source.value.identity != remote.identity) return Outcome.Success(Unit)
        val groups = MakeNowGroups.fromServer(answers.mapValues { (_, items) -> items.mapNotNull { it.id } })
        val database = databases.get(remote.kind)
        val stored = database.recipeDao().getRecipeIds(remote.accountId).toHashSet()
        val missingOverviews = answers.values.flatten().filter { it.id != null && it.id !in stored }.distinctBy { it.id }
        if (missingOverviews.isNotEmpty()) {
            val images = ImageResolver.forBaseUrl(remote.baseUrl)
            val writes = missingOverviews.mapNotNull { it.toOverviewWrite(remote.accountId, images) }
            database.recipeDao().upsertOverviews(remote.accountId, writes)
        }
        store(database, remote.accountId, groups)
        return Outcome.Success(Unit)
    }

    /** Demo mode: the groups computed on the device from every stored detail (the demo recipes). */
    private suspend fun refreshLocally(source: ActiveSource.Stored): Outcome<Unit> {
        val database = databases.get(source.kind)
        val dao = database.makeNowDao()
        val foods = availability(source, database).first()
        val groups = LinkedHashMap<Long, Int>()
        for (recipeId in dao.getDetailIds(source.accountId)) {
            val ingredients = dao.getIngredients(source.accountId, recipeId).map { it.toModel() }
            if (ingredients.none { !it.isHeader && it.food != null }) continue
            val missing = MissingIngredients.missing(ingredients, foods).size
            if (MakeNowGroup.of(missing) != null) groups[recipeId] = missing
        }
        store(database, source.accountId, groups)
        return Outcome.Success(Unit)
    }

    /** The new groups replace the stored ones; missing foods already named stay while the count is the same. */
    private suspend fun store(database: TandemDatabase, accountId: String, groups: Map<Long, Int>) {
        val dao = database.makeNowDao()
        val now = clock.nowMillis()
        val old = dao.getAll(accountId).associateBy { it.recipeId }
        dao.replaceAll(
            accountId,
            groups.map { (recipeId, missing) ->
                val kept = old[recipeId]?.takeIf { it.missingCount == missing }?.missingFoods
                MakeNowEntity(accountId, recipeId, missingCount = missing, missingFoods = kept, fetchedAtEpochMillis = now)
            },
        )
    }

    // ------------------------------------------------------------------------------------ the state

    private fun state(source: ActiveSource.Stored, filter: MakeNowFilter, today: LocalDate): Flow<MakeNowState> {
        val database = databases.get(source.kind)
        val dao = database.makeNowDao()
        val accountId = source.accountId
        val inputs = combine(
            dao.observeAll(accountId),
            dao.observeIngredients(accountId),
            dao.observeDetailIds(accountId),
        ) { rows, ingredients, detailIds ->
            Triple(rows, ingredients.groupBy { it.recipeId }, detailIds.toHashSet())
        }
        // One subscription for both, so the missing foods and "Använd upp" always see the same pantry.
        val pantryNow = combine(database.supermarketDao().observeFoods(accountId), pantry.observePantry()) { foods, groups ->
            val entries = groups.flatMap { it.entries }
            availabilityOf(foods, entries.stocked()) to entries
        }
        val own = status.map { it[accountId] ?: Status() }.distinctUntilChanged()
        return combine(inputs, pantryNow, own) { (rows, ingredients, detailIds), (foods, entries), state ->
            val items = database.recipeDao().getListItems(accountId, rows.map { it.recipeId }).map { it.toModel() }.associateBy { it.id }
            MakeNowState(
                groups = groupsOf(rows, items, ingredients, detailIds, foods, entries, filter, today),
                refreshing = state.refreshing,
                stale = state.stale,
            )
        }
    }

    @Suppress("LongParameterList") // The state's inputs, combined above.
    private fun groupsOf(
        rows: List<MakeNowEntity>,
        overviews: Map<Long, RecipeOverview>,
        ingredients: Map<Long, List<IngredientEntity>>,
        detailIds: Set<Long>,
        foods: Map<Long, FoodAvailability>,
        entries: List<InventoryEntry>,
        filter: MakeNowFilter,
        today: LocalDate,
    ): Map<MakeNowGroup, List<MakeNowRecipe>> {
        val recipeFoods = ingredients.mapValues { (_, list) -> list.mapNotNullTo(HashSet()) { it.foodId } }
        val byGroup = rows.mapNotNull { row ->
            val group = MakeNowGroup.of(row.missingCount) ?: return@mapNotNull null
            val recipe = overviews[row.recipeId] ?: return@mapNotNull null
            if (isDrink(recipe) || !matches(recipe, filter)) return@mapNotNull null
            val known = row.recipeId in detailIds
            val recipeIngredients = ingredients[row.recipeId].orEmpty()
            // A downloaded recipe without any food (like a drink without ingredients) is always "Har allt": not cooking.
            if (known && recipeIngredients.none { !it.isHeader && it.foodId != null }) return@mapNotNull null
            val missing = if (known) {
                MissingIngredients.missing(recipeIngredients.map { it.toModel() }, foods)
            } else {
                row.missingFoods?.let(MissingFoodsJson::decode)
            }
            MakeNowRecipe(recipe = recipe, group = group, missingFoods = missing)
        }.groupBy { it.group }
        return MakeNowGroup.entries.associateWith { group ->
            val sorted = byGroup[group].orEmpty().sortedWith(compareBy(collator) { it.recipe.name })
            UseUpRanking.rank(sorted, recipeFoods, entries, today)
        }
    }

    /** What the household has, per food: "har hemma", stock (amount > 0 in its locations), substitutes, `ignore_shopping`. */
    private fun availability(source: ActiveSource.Stored, database: TandemDatabase): Flow<Map<Long, FoodAvailability>> =
        combine(database.supermarketDao().observeFoods(source.accountId), pantry.observePantry()) { foods, groups ->
            availabilityOf(foods, groups.flatMap { it.entries }.stocked())
        }

    /** The food ids with stock (amount > 0). */
    private fun List<InventoryEntry>.stocked(): Set<Long> = filter { !it.isEmpty }.mapNotNullTo(HashSet()) { it.food.id }

    private fun isDrink(recipe: RecipeOverview): Boolean = recipe.keywords.any { it.name.equals(DrinkKeywords.DRINK, ignoreCase = true) }

    private fun matches(recipe: RecipeOverview, filter: MakeNowFilter): Boolean {
        val keywords = filter.keywordIds
        val tagged = recipe.keywords.any { keyword -> keyword.id in keywords || keyword.parentId?.let { it in keywords } == true }
        if (keywords.isNotEmpty() && !tagged) return false
        val max = filter.maxMinutes
        return max == null || recipe.totalMinutes == 0 || recipe.totalMinutes <= max
    }

    companion object {
        /** The groups are read again at most once a minute unless forced. */
        const val MIN_INTERVAL_MILLIS: Long = 60_000

        /** At most this many recipe details fetched for missing foods at a time (plan §8.8: about 20). */
        const val MAX_PENDING_DETAILS: Int = 20

        /** 100 recipes per page: at most 500 per group. */
        const val MAX_PAGES: Int = 5

        /** [FoodAvailability] per food from the stored foods and the stocked food ids. */
        fun availabilityOf(foods: List<FoodEntity>, stocked: Set<Long>): Map<Long, FoodAvailability> {
            val map = HashMap<Long, FoodAvailability>()
            for (food in foods) {
                map[food.id] = FoodAvailability(
                    foodId = food.id,
                    onHand = food.onHand,
                    inStock = food.id in stocked,
                    substituteOnHand = food.substituteOnHand,
                    substituteIds = substituteIdsOf(food.substituteIds),
                    ignoreShopping = food.ignoreShopping,
                    isRecipe = food.recipeId != null,
                )
            }
            for (id in stocked) map.getOrPut(id) { FoodAvailability(foodId = id, inStock = true) }
            return map
        }
    }
}

/** `make_now.missing_foods`: `[{"id":1,"name":"grädde","plural_name":null}]`. */
internal object MissingFoodsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(foods: List<MissingFood>): String = buildJsonArray {
        for (food in foods) {
            add(
                buildJsonObject {
                    put("id", food.id)
                    put("name", food.name)
                    put("plural_name", food.pluralName)
                },
            )
        }
    }.toString()

    fun decode(text: String): List<MissingFood>? {
        val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val plural = obj["plural_name"]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull }
            MissingFood(id = id, name = name, pluralName = plural)
        }
    }
}
