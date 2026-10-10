package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.core.model.recipe.ServingsChoice
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory [RecipeRepository] over [SampleRecipes] (or your own data). Deterministic and
 * synchronous: every change to the public state flows is visible to observers at once.
 *
 * Search mimics the offline FTS: case- and diacritics-insensitive ("artsoppa" finds "Ärtsoppa"),
 * over name, description, keyword names and food names. [searchServer] returns
 * [searchServerResult] when set, otherwise the same local filtering.
 */
class FakeRecipeRepository(
    details: List<Recipe> = SampleRecipes.details,
    overviews: List<RecipeOverview> = SampleRecipes.overviews,
    keywords: List<Keyword> = SampleRecipes.keywords,
) : RecipeRepository {
    val overviews = MutableStateFlow(overviews)
    val details = MutableStateFlow(details.associateBy { it.id })
    val keywords = MutableStateFlow(keywords)
    val servingsChoices = MutableStateFlow<Map<Long, Double>>(emptyMap())

    /** Detail states to emit instead of the derived one (loading, errors), by recipe id. */
    val detailOverrides = MutableStateFlow<Map<Long, RecipeDetailState>>(emptyMap())

    /** What [searchServer] returns; null means "filter the local overviews". */
    var searchServerResult: Outcome<List<RecipeOverview>>? = null

    /** What [refreshRecipe] returns. */
    var refreshResult: Outcome<Unit> = Outcome.Success(Unit)

    val searchedQueries = mutableListOf<RecipeQuery>()
    val refreshedIds = mutableListOf<Long>()
    val viewedIds = mutableListOf<Long>()

    override fun observeRecipes(query: RecipeQuery): Flow<List<RecipeOverview>> = combine(overviews, details) { list, byId ->
        list.matching(query, byId)
    }

    override suspend fun searchServer(query: RecipeQuery): Outcome<List<RecipeOverview>> {
        searchedQueries += query
        return searchServerResult ?: Outcome.Success(overviews.value.matching(query, details.value))
    }

    override fun observeRecipe(id: Long): Flow<RecipeDetailState> = combine(overviews, details, detailOverrides) { list, byId, overrides ->
        overrides[id] ?: RecipeDetailState(recipeId = id, overview = list.firstOrNull { it.id == id }, recipe = byId[id])
    }

    override suspend fun refreshRecipe(id: Long): Outcome<Unit> {
        refreshedIds += id
        return refreshResult
    }

    override suspend fun setAvailableOffline(id: Long, available: Boolean): Outcome<Unit> {
        overviews.value = overviews.value.map { overview ->
            if (overview.id != id) {
                overview
            } else {
                overview.copy(pinned = available, availableOffline = available || overview.availableOffline)
            }
        }
        return Outcome.Success(Unit)
    }

    override fun observeServingsChoice(id: Long): Flow<ServingsChoice?> = servingsChoices.map { choices ->
        choices[id]?.let { ServingsChoice(recipeId = id, servings = it) }
    }

    override suspend fun setServingsChoice(id: Long, servings: Double?): Outcome<Unit> {
        servingsChoices.value = if (servings == null) servingsChoices.value - id else servingsChoices.value + (id to servings)
        return Outcome.Success(Unit)
    }

    override suspend fun markViewed(id: Long) {
        viewedIds += id
    }

    override fun observeKeywords(): Flow<List<Keyword>> = keywords.map { list ->
        list.sortedWith(compareBy<Keyword, String>(swedishCollator) { it.name })
    }
}

/** In-memory [HomeRepository]: set [sections] and [upcomingMeals]; [refresh] returns [refreshResult]. */
class FakeHomeRepository(
    sections: List<HomeSection> = SampleRecipes.homeSections,
    upcomingMeals: UpcomingMeals = SampleRecipes.upcomingMeals,
) : HomeRepository {
    val sections = MutableStateFlow(sections)
    val upcomingMeals = MutableStateFlow(upcomingMeals)
    var refreshResult: Outcome<Unit> = Outcome.Success(Unit)
    var refreshCount: Int = 0
        private set

    override fun observeSections(): Flow<List<HomeSection>> = sections.map { list -> list.filter { it.recipes.isNotEmpty() } }

    override fun observeUpcomingMeals(): Flow<UpcomingMeals> = upcomingMeals

    override suspend fun refresh(): Outcome<Unit> {
        refreshCount++
        return refreshResult
    }
}

/**
 * [SyncRepository] whose state is set by the test. [firstSync] emits [firstSyncProgress]
 * (by default a short successful run: 312 recipes, then DONE).
 */
class FakeSyncRepository(initial: SyncStatus = SyncStatus.Idle) : SyncRepository {
    private val mutableStatus = MutableStateFlow(initial)
    override val status: StateFlow<SyncStatus> = mutableStatus

    val requests = mutableListOf<SyncReason>()

    var firstSyncProgress: List<SyncProgress> = listOf(
        SyncProgress(SyncPhase.SERVER_CHECK),
        SyncProgress(SyncPhase.RECIPE_INDEX, done = 0, total = 312),
        SyncProgress(SyncPhase.RECIPE_INDEX, done = 312, total = 312),
        SyncProgress(SyncPhase.DONE),
    )

    /** Sets the status that [status] emits. */
    fun setStatus(status: SyncStatus) {
        mutableStatus.value = status
    }

    override fun requestSync(reason: SyncReason) {
        requests += reason
    }

    override fun firstSync(): Flow<SyncProgress> = flowOf(*firstSyncProgress.toTypedArray())

    companion object {
        /** A first sync that fails while fetching the index. */
        fun failingFirstSync(error: TandemError = TandemError.NetworkUnavailable): List<SyncProgress> = listOf(
            SyncProgress(SyncPhase.SERVER_CHECK),
            SyncProgress(SyncPhase.FAILED, error = error),
        )
    }
}

/** [PreferencesRepository] with settable [preferences]. */
class FakePreferencesRepository(initial: UserPreferences = UserPreferences.Default) : PreferencesRepository {
    val preferences = MutableStateFlow(initial)

    override fun observeUserPreferences(): Flow<UserPreferences> = preferences
}

/**
 * [ServerStatusRepository] with settable [status]. [emitVersionChange] simulates a check that
 * found a new server version; [refresh] returns [refreshResult] or the current status.
 */
class FakeServerStatusRepository(initial: ServerStatus = ServerStatus.Unknown) : ServerStatusRepository {
    val status = MutableStateFlow(initial)
    private val versionChanges = MutableSharedFlow<ServerVersionChange>(extraBufferCapacity = 8)
    var refreshResult: Outcome<ServerStatus>? = null
    val refreshes = mutableListOf<Boolean>()

    suspend fun emitVersionChange(change: ServerVersionChange) {
        versionChanges.emit(change)
    }

    override fun observeStatus(): Flow<ServerStatus> = status

    override fun observeVersionChanges(): Flow<ServerVersionChange> = versionChanges

    override suspend fun refresh(force: Boolean): Outcome<ServerStatus> {
        refreshes += force
        return refreshResult ?: Outcome.Success(status.value)
    }
}

private val swedishCollator: Collator = Collator.getInstance(Locale.forLanguageTag("sv")).apply { strength = Collator.SECONDARY }

private val combiningMarks = Regex("\\p{Mn}+")

/** Lower case without diacritics, like FTS `unicode61 remove_diacritics 2`: "Ärtsoppa" -> "artsoppa". */
internal fun String.searchKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

private fun List<RecipeOverview>.matching(query: RecipeQuery, details: Map<Long, Recipe>): List<RecipeOverview> {
    val words = query.text.searchKey().split(' ').filter { it.isNotBlank() }
    val maxMinutes = query.maxTotalMinutes
    val minRating = query.minRating
    return filter { recipe ->
        val haystack = buildString {
            append(recipe.name).append(' ').append(recipe.description.orEmpty())
            recipe.keywords.forEach { append(' ').append(it.name) }
            details[recipe.id]?.ingredients?.forEach { ingredient -> ingredient.food?.let { append(' ').append(it.name) } }
        }.searchKey()
        words.all { it in haystack } &&
            recipe.keywords.map { it.id }.containsAll(query.keywordIds) &&
            (maxMinutes == null || recipe.totalMinutes in 1..maxMinutes) &&
            (minRating == null || (recipe.rating ?: 0.0) >= minRating) &&
            (!query.onlyOffline || recipe.availableOffline)
    }.sortedWith(query.sort.comparator())
}

private fun RecipeSort.comparator(): Comparator<RecipeOverview> {
    val byName = compareBy<RecipeOverview, String>(swedishCollator) { it.name }
    return when (this) {
        RecipeSort.NAME -> byName
        RecipeSort.RATING -> compareByDescending<RecipeOverview> { it.rating ?: -1.0 }.then(byName)
        RecipeSort.LAST_COOKED -> compareByDescending<RecipeOverview> { it.lastCooked }.then(byName)
        RecipeSort.TIMES_COOKED ->
            compareByDescending<RecipeOverview> { it.timesCooked ?: -1 }.thenByDescending { it.lastCooked }.then(byName)
        RecipeSort.NEWEST -> compareByDescending<RecipeOverview> { it.isNew }.thenByDescending { it.id }
        RecipeSort.TOTAL_TIME -> compareBy<RecipeOverview> { if (it.totalMinutes > 0) it.totalMinutes else Int.MAX_VALUE }.then(byName)
    }
}
