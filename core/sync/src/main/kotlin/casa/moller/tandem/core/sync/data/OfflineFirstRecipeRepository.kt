package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.dao.RecipeDao
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.database.mapper.toRecipe
import casa.moller.tandem.core.database.relation.RecipeDetailWithSteps
import casa.moller.tandem.core.database.relation.RecipeListItem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.core.model.recipe.ServingsChoice
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.image.ImagePrefetcher
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toListQuery
import casa.moller.tandem.core.sync.remote.toOverviewWrite
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [RecipeRepository] on Room (the single source of truth) for the active account (plan 3.5,
 * 5.3): lists, search and details are read from the database of the [ActiveSource] (the real one
 * or the demo one), the network only refreshes them through [DetailFetcher] and [RemoteCaller].
 * See the interface for the contract; the notes here are about how it is met.
 *
 * - [observeRecipe] is stale-while-revalidate: the stored copy is emitted first, then (online,
 *   real account, stored copy older than [SyncSchedule.DETAIL_FRESH_FOR] or missing) the state
 *   with `refreshing = true`, then the fresh copy from the database, or the old one with the error.
 * - [markViewed] stores the view locally and queues `POST /api/view-log/` (coalesced per recipe
 *   in the outbox, sent by `SyncOutboxWorker`).
 * - Demo mode never touches the network: search runs locally and refresh succeeds at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstRecipeRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val details: DetailFetcher,
    private val images: ImagePrefetcher,
    private val scheduler: SyncScheduler,
    private val network: NetworkStatus,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
) : RecipeRepository {

    override fun observeRecipes(query: RecipeQuery): Flow<List<RecipeOverview>> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> recipeDao(source)
                    .observeRecipeList(source.accountId, query, Locale.getDefault())
                    .map { items -> items.map { item -> item.toModel() } }

                ActiveSource.Loading -> emptyFlow<List<RecipeOverview>>()

                ActiveSource.None -> flowOf(emptyList<RecipeOverview>())
            }
        }
        .flowOn(Dispatchers.Default)

    override suspend fun searchServer(query: RecipeQuery): Outcome<List<RecipeOverview>> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Remote -> searchRemote(source, query)

            // Demo mode has no server: the same query runs on the demo database.
            ActiveSource.Demo -> Outcome.Success(
                recipeDao(ActiveSource.Demo).getListItemsFor(ActiveSource.Demo.accountId, query),
            )

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    private suspend fun RecipeDao.getListItemsFor(accountId: String, query: RecipeQuery): List<RecipeOverview> =
        observeRecipeList(accountId, query, Locale.getDefault()).first().map { it.toModel() }

    private suspend fun searchRemote(remote: ActiveSource.Remote, query: RecipeQuery): Outcome<List<RecipeOverview>> {
        val page = when (val response = caller.call(remote, background = false) { recipes.list(query.toListQuery()) }) {
            is Outcome.Failure -> return response
            is Outcome.Success -> response.value
        }
        val resolver = ImageResolver.forBaseUrl(remote.baseUrl)
        val writes = page.results.mapNotNull { it.toOverviewWrite(remote.accountId, resolver) }
        val dao = recipeDao(remote)
        dao.upsertOverviews(remote.accountId, writes)
        val ids = writes.map { it.overview.serverId }.distinct()
        val stored = ids.chunked(MAX_SQL_ARGUMENTS).flatMap { dao.getListItems(remote.accountId, it) }.associateBy { it.overview.serverId }
        // Filters without a server parameter are applied here (plan 3.3).
        val found = ids.mapNotNull { stored[it]?.toModel() }
            .filter { recipe -> query.maxTotalMinutes?.let { recipe.totalMinutes <= it } ?: true }
            .filter { recipe -> !query.onlyOffline || recipe.availableOffline }
        val sorted = if (query.sort == RecipeSort.TOTAL_TIME) {
            found.sortedBy { if (it.totalMinutes > 0) it.totalMinutes else Int.MAX_VALUE }
        } else {
            found
        }
        return Outcome.Success(sorted)
    }

    override fun observeRecipe(id: Long): Flow<RecipeDetailState> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> detailStates(source, id)
                ActiveSource.Loading -> emptyFlow<RecipeDetailState>()
                ActiveSource.None -> flowOf(RecipeDetailState(recipeId = id, error = DataProblem.NO_ACCOUNT.toError()))
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private fun detailStates(source: ActiveSource.Stored, id: Long): Flow<RecipeDetailState> = channelFlow {
        val dao = recipeDao(source)
        val refresh = MutableStateFlow(RefreshState())
        var revalidating = false
        combine(dao.observeListItem(source.accountId, id), dao.observeDetail(source.accountId, id), refresh) { item, detail, state ->
            detailState(id, item, detail, state)
        }.collect { state ->
            send(state)
            // Stale-while-revalidate: once per collection, after the stored copy was emitted.
            if (!revalidating && source is ActiveSource.Remote) {
                revalidating = true
                launch { revalidate(source, id, state, refresh) }
            }
        }
    }

    private suspend fun revalidate(
        remote: ActiveSource.Remote,
        id: Long,
        stored: RecipeDetailState,
        refresh: MutableStateFlow<RefreshState>,
    ) {
        val fetchedAt = stored.fetchedAt?.toEpochMilliseconds()
        val fresh = fetchedAt != null && clock.nowMillis() - fetchedAt < SyncSchedule.DETAIL_FRESH_FOR.inWholeMilliseconds
        if (fresh) return
        if (!network.online.value) {
            refresh.value = RefreshState(error = TandemError.NetworkUnavailable)
            return
        }
        refresh.value = RefreshState(refreshing = true)
        val result = details.fetch(remote, id, isBackground = false)
        refresh.value = RefreshState(error = (result as? Outcome.Failure)?.error)
    }

    private fun detailState(id: Long, item: RecipeListItem?, detail: RecipeDetailWithSteps?, refresh: RefreshState): RecipeDetailState {
        // A 404 removed the recipe locally (DetailFetcher). Room's flows report the removal a
        // moment later; until then the stale rows must not be shown together with "deleted".
        if (refresh.error == TandemError.NotFound) return RecipeDetailState(recipeId = id, error = TandemError.NotFound)
        val overview = item?.toModel()
        val recipe = detail?.toRecipe(item?.overview, overview?.keywords.orEmpty())
        return RecipeDetailState(
            recipeId = id,
            overview = overview,
            recipe = recipe,
            fetchedAt = detail?.detail?.fetchedAtEpochMillis?.takeIf { recipe != null }?.let { Instant.fromEpochMilliseconds(it) },
            refreshing = refresh.refreshing,
            error = refresh.error,
        )
    }

    override suspend fun refreshRecipe(id: Long): Outcome<Unit> = when (val source = sources.current()) {
        is ActiveSource.Remote -> details.fetch(source, id, isBackground = false)
        ActiveSource.Demo -> Outcome.Success(Unit)
        ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
    }

    override suspend fun setAvailableOffline(id: Long, available: Boolean): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                recipeDao(source).setPinned(source.accountId, id, available)
                // Download now if possible; otherwise the next sync fetches it (pinned without detail).
                if (available && source is ActiveSource.Remote && network.online.value) {
                    scope.launch { downloadForOffline(source, id) }
                }
                Outcome.Success(Unit)
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    private suspend fun downloadForOffline(remote: ActiveSource.Remote, id: Long) {
        if (details.fetch(remote, id, isBackground = false) !is Outcome.Success) return
        val image = recipeDao(remote).getListItems(remote.accountId, listOf(id)).firstOrNull()?.overview?.imageUrl ?: return
        images.prefetch(listOf(image))
    }

    override fun observeServingsChoice(id: Long): Flow<ServingsChoice?> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> recipeDao(source).observePortionsChosen(source.accountId, id)
                    .map { servings -> servings?.takeIf { it > 0 }?.let { ServingsChoice(id, it) } }

                ActiveSource.Loading -> emptyFlow<ServingsChoice?>()

                ActiveSource.None -> flowOf<ServingsChoice?>(null)
            }
        }
        .distinctUntilChanged()

    override suspend fun setServingsChoice(id: Long, servings: Double?): Outcome<Unit> {
        if (servings != null && !(servings.isFinite() && servings > 0)) {
            return Outcome.Failure(TandemError.BadRequest(mapOf(FIELD_SERVINGS to emptyList())))
        }
        return catchingUnexpected {
            when (val source = sources.current()) {
                is ActiveSource.Stored -> {
                    recipeDao(source).setPortionsChosen(source.accountId, id, servings)
                    Outcome.Success(Unit)
                }

                ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
            }
        }
    }

    override suspend fun markViewed(id: Long) {
        try {
            val source = sources.current() as? ActiveSource.Stored ?: return
            val now = clock.nowMillis()
            val database = databases.get(source.kind)
            database.recipeDao().markViewed(source.accountId, id, now)
            if (source is ActiveSource.Remote) {
                val payload = buildJsonObject { put(FIELD_RECIPE, id) }.toString()
                database.outboxDao().enqueueViewLog(source.accountId, id, payload, now)
                scheduler.scheduleOutbox()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never fails (contract): a view that could not be stored is simply not recorded.
        }
    }

    override fun observeKeywords(): Flow<List<Keyword>> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> recipeDao(source).observeKeywords(source.accountId).map { rows ->
                    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
                    rows.map { it.toModel() }.sortedWith(compareBy<Keyword, String>(collator) { it.name })
                }

                ActiveSource.Loading -> emptyFlow<List<Keyword>>()

                ActiveSource.None -> flowOf(emptyList<Keyword>())
            }
        }
        .flowOn(Dispatchers.Default)

    private fun recipeDao(source: ActiveSource.Stored): RecipeDao = databases.get(source.kind).recipeDao()

    /** The detail's refresh, combined with the stored rows. */
    private data class RefreshState(val refreshing: Boolean = false, val error: TandemError? = null)

    private companion object {
        const val FIELD_SERVINGS = "servings"
        const val FIELD_RECIPE = "recipe"

        /** SQLite's limit for `IN (...)` arguments, with room to spare. */
        const val MAX_SQL_ARGUMENTS = 500
    }
}
