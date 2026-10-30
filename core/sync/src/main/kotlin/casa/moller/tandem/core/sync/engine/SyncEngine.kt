package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.dao.IdRewriteDao
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.database.mapper.toEntity
import casa.moller.tandem.core.datastore.DiagnosticsStore
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.RecipeSortOrder
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.paging.PagedResult
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.book.BookSyncStep
import casa.moller.tandem.core.sync.data.DefaultServerStatusRepository
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.image.ImagePrefetcher
import casa.moller.tandem.core.sync.image.PrefetchedImages
import casa.moller.tandem.core.sync.mealplan.MealPlanWindowReader
import casa.moller.tandem.core.sync.pantry.PantrySyncStep
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toEntity
import casa.moller.tandem.core.sync.remote.toIndexEntry
import casa.moller.tandem.core.sync.remote.toModel
import casa.moller.tandem.core.sync.remote.toOverviewWrite
import casa.moller.tandem.core.sync.shopping.ShoppingSyncStep
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * What the engine is doing, for `SyncStatus`.
 *
 * @property running a sync run is in progress.
 * @property progress its current step, null when idle.
 * @property lastError why the last run failed, null after a successful one; a run that was
 *   cancelled or overtaken by an account change leaves a [SyncStop] marker.
 * @property runsStarted runs started since the process started (`SyncStatus.runsStarted`).
 */
internal data class EngineState(
    val running: Boolean = false,
    val progress: SyncProgress? = null,
    val lastError: TandemError? = null,
    val runsStarted: Long = 0,
)

/**
 * The sync of the active account (plan 3.5 "Synkstrategi", 3.6, 4.1, 5.3): Room is the single
 * source of truth, and a run brings it up to date with the server in this order:
 *
 * 1. **Server check** (periodic runs; start and resume are checked by the shell): version and
 *    capabilities, at most every 15 minutes.
 * 2. **Small tables** (keywords, foods, units, meal types, user preferences, cook statistics from
 *    the cook log): full relist, at most every hour on start/resume/refresh and every 6 hours in
 *    the periodic sync.
 * 3. **Recipe index** (`recipe/flat`, deletions) and **overviews** (`recipe/` pages of 100 with
 *    `updatedon_gte` = last sync day minus one day; all of them on the first sync): at most every
 *    hour, always on the first sync and on pull to refresh.
 * 4. **Meal plan** (-14..+42 days) and **Today's carousels** (plan 3.3 queries): every 15 minutes
 *    (2 minutes on pull to refresh), serialized with Today's own refresh ([refreshHome]).
 * 5. **Details** of pinned (older than 24 h), planned (yesterday..+7) and recently viewed recipes
 *    (missing ones), and once those the upgrade to 0.2.0 marked (step photos): at most
 *    [SyncSchedule.MAX_DETAIL_PREFETCH_PER_RUN] per run, 2 at a time with a pause, then the **images** of pinned, planned and recently viewed recipes into the disk
 *    cache (an image that failed temporarily is tried again in the next run).
 * 6. **Outbox** ([OutboxSender]).
 *
 * Only one run at a time ([run] waits for a running one). Every request passes [SyncGuard]; a run
 * stops as soon as the guard closes (unauthorized, blocked, breaker or, for background runs, an
 * import) or the active account changes; a response that arrives after the account changed is not
 * written (rows that slip through anyway are removed by `AccountMirror`). Failures of steps 2, 4
 * and 5 keep the stored data and do not fail the run; a failed index (step 3) does.
 *
 * Every "at most every ..." check treats a stored time in the future (the device clock was ahead
 * when it was written) as due, so a corrected clock never stops the sync for days.
 *
 * Phase 1b (inställningar lane): with "Download all recipes" on (Settings → Sync), step 5 also
 * fetches recipes without a stored detail, and stored details that are older than their recipe's
 * `updated_at` (fix-data, 0.2.0 review: an edit on the web reaches the offline copy), and their
 * images, up to [SyncSchedule.MAX_DOWNLOAD_ALL_PER_RUN] per run. Images already in the disk cache
 * are skipped, also in a new process. A successful run that read every overview for the first
 * time (no earlier sync date) is stored as the "full sync" timing for Settings → Diagnostics
 * (Phase 1 "Klart när": ~300 recipes under 60 s; plan §10 "index + översikter + detaljer för
 * fästa"): measured from the run's start to the end of the usual details, without the
 * download-all details and the images.
 */
@Singleton
internal class SyncEngine @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val details: DetailFetcher,
    private val images: ImagePrefetcher,
    private val prefetched: PrefetchedImages,
    private val outbox: OutboxSender,
    private val serverStatus: DefaultServerStatusRepository,
    private val scheduler: SyncScheduler,
    private val guard: SyncGuard,
    private val network: NetworkStatus,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val diagnostics: DiagnosticsStore,
    // Phase 2 (inköp-data lane): the shopping list and its small tables.
    private val shopping: ShoppingSyncStep,
    // Phase 2 (böcker lane): the role (guest mode) and the books, with the small tables.
    private val books: BookSyncStep,
    // Phase 2 (matsedel lane): the meal plan window, shared with Veckan's own refresh.
    private val mealPlanWindow: MealPlanWindowReader,
    // Fas S (hemma-data lane): the pantry, the learned aliases and staples waiting for a food.
    private val pantry: PantrySyncStep,
) {
    private val mutex = Mutex()
    private val homeMutex = Mutex()
    private val mutableState = MutableStateFlow(EngineState())
    private val firstSyncLock = Any()
    private var firstSyncAttempt: MutableStateFlow<SyncProgress>? = null

    /** The engine's state for `SyncStatus`. */
    val state: StateFlow<EngineState> = mutableState.asStateFlow()

    /**
     * The first sync after a login (plan 6.2 "Efter inloggning"): starts one run in the
     * application scope (or joins the running attempt) and streams its progress until DONE or
     * FAILED. Cancelling the collector does not cancel the run.
     */
    fun firstSync(): Flow<SyncProgress> {
        val attempt = synchronized(firstSyncLock) {
            firstSyncAttempt?.takeIf { !it.value.isFinished } ?: MutableStateFlow(SyncProgress(SyncPhase.SERVER_CHECK)).also { flow ->
                firstSyncAttempt = flow
                scope.launch { flow.value = run(SyncReason.FIRST_SYNC) { progress -> flow.value = progress } }
            }
        }
        return attempt.transformWhile { progress ->
            emit(progress)
            !progress.isFinished
        }
    }

    /**
     * Runs one sync of the active account and returns how it ended (DONE or FAILED). Without a
     * logged-in account there is nothing to do (DONE). Never throws except for cancellation.
     */
    suspend fun run(reason: SyncReason, onProgress: (SyncProgress) -> Unit = {}): SyncProgress {
        val remote = sources.current() as? ActiveSource.Remote ?: return SyncProgress(SyncPhase.DONE)
        return mutex.withLock {
            val run = SyncRun(remote, reason, onProgress)
            val startedAt = clock.elapsedMillis()
            mutableState.update { state ->
                state.copy(running = true, progress = SyncProgress(SyncPhase.SERVER_CHECK), runsStarted = state.runsStarted + 1)
            }
            var result = SyncProgress(SyncPhase.FAILED, error = SyncStop.Cancelled)
            try {
                result = try {
                    run.execute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SyncProgress(SyncPhase.FAILED, error = TandemError.Unknown(e.javaClass.name))
                }
            } finally {
                val failed = result.phase == SyncPhase.FAILED
                mutableState.update { it.copy(running = false, progress = null, lastError = if (failed) result.error else null) }
            }
            val recipes = run.fullIndexRecipes
            val fullSyncDone = run.fullSyncElapsedMillis
            if (result.phase == SyncPhase.DONE && recipes != null && fullSyncDone != null) {
                val duration = (fullSyncDone - startedAt).coerceAtLeast(0).milliseconds
                diagnostics.recordFullSync(FullSyncTiming(duration, recipes, Instant.fromEpochMilliseconds(clock.nowMillis())))
            }
            result
        }
    }

    /**
     * Today's pull to refresh and first display (`HomeRepository.refresh`): the meal plan window
     * and the carousels, at most every [SyncSchedule.HOME_MIN_INTERVAL].
     */
    suspend fun refreshHome(remote: ActiveSource.Remote): Outcome<Unit> = homeMutex.withLock {
        catchingUnexpected {
            if (!network.online.value) return@catchingUnexpected Outcome.Failure(TandemError.NetworkUnavailable)
            val run = SyncRun(remote, SyncReason.USER_REFRESH) {}
            if (!run.isDue(SyncKeys.HOME, SyncSchedule.HOME_MIN_INTERVAL, always = false)) return@catchingUnexpected Outcome.Success(Unit)
            run.syncMealPlanAndHome()
        }
    }

    /** One sync run for [remote]; holds the run's settings so the steps stay short. */
    private inner class SyncRun(
        private val remote: ActiveSource.Remote,
        private val reason: SyncReason,
        private val onProgress: (SyncProgress) -> Unit,
    ) {
        private val accountId = remote.accountId
        private val database: TandemDatabase = databases.get(remote.kind)
        private val imageResolver = ImageResolver.forBaseUrl(remote.baseUrl)

        /** Requests the user did not start may be held back by the breaker and imports. */
        private val background = reason != SyncReason.FIRST_SYNC && reason != SyncReason.USER_REFRESH

        /** Steps that ignore their "at most every ..." limit for this run. */
        private val forced = reason == SyncReason.FIRST_SYNC || reason == SyncReason.ACCOUNT_CHANGED || reason == SyncReason.USER_REFRESH

        /** Recipes in the index when this run read every overview for the first time (a "full sync"), else null. */
        var fullIndexRecipes: Int? = null
            private set

        /** [SyncClock.elapsedMillis] when the full sync's part was done (index, overviews, usual details). */
        var fullSyncElapsedMillis: Long? = null
            private set

        /** Settings → Sync → "Download all recipes", read once when the run starts. */
        private var downloadAll = false

        suspend fun execute(): SyncProgress {
            openGate()?.let { return failed(it) }
            downloadAll = settings.settings.first().downloadAllRecipes

            progress(SyncPhase.SERVER_CHECK)
            if (reason == SyncReason.PERIODIC) serverStatus.refresh(force = false)
            serverStatus.ensureCapabilities(remote)
            stopReason()?.let { return failed(it) }

            val referenceInterval = if (reason == SyncReason.PERIODIC) {
                SyncSchedule.REFERENCE_DATA_INTERVAL
            } else {
                SyncSchedule.REFERENCE_DATA_MIN_INTERVAL
            }
            val referenceForced = reason == SyncReason.FIRST_SYNC || reason == SyncReason.ACCOUNT_CHANGED
            val referenceDue = isDue(SyncKeys.REFERENCE_DATA, referenceInterval, referenceForced)
            if (referenceDue) {
                progress(SyncPhase.REFERENCE_DATA)
                syncReferenceData()
            }
            stopReason()?.let { return failed(it) }

            if (isDue(SyncKeys.RECIPE_INDEX, SyncSchedule.RECIPE_INDEX_MIN_INTERVAL, forced)) {
                progress(SyncPhase.RECIPE_INDEX)
                val index = syncIndex()
                if (index is Outcome.Failure) return failed(stopReason() ?: index.error)
            }
            // After the index, so the counts reach recipes stored by this run too. Also out of
            // turn while no complete cook log is stored (first run after the update to 0.2.0).
            if (referenceDue || !database.cookLogDao().isComplete(accountId)) syncCookStatistics()
            if (referenceDue) markDone(SyncKeys.REFERENCE_DATA)
            stopReason()?.let { return failed(it) }

            // 0.3.0 review (fix-data): the role first (guest mode #42), so the list and the outbox
            // below never act for a guest whose role was not read yet.
            books.role(remote, background, smallTables = referenceDue)
            stopReason()?.let { return failed(it) }

            // Phase 2 (inköp-data lane): the shopping list (plan 3.5: full at start, else a delta).
            shopping.run(remote, background, smallTables = referenceDue, full = reason in SHOPPING_FULL_READ)
            stopReason()?.let { return failed(it) }

            // Phase 2 (böcker lane): the books (plan 3.5 "Små tabeller").
            books.run(remote, background, smallTables = referenceDue)
            stopReason()?.let { return failed(it) }

            // Fas S (hemma-data lane): the pantry (at most every 5 minutes), the learned aliases (with the small tables).
            pantry.run(remote, background, smallTables = referenceDue, forced = forced)
            stopReason()?.let { return failed(it) }

            // Serialized with Today's refresh (refreshHome) and checked again inside the lock:
            // whichever comes second sees the fresh HOME time and skips (plan 4.1, no duplicates).
            homeMutex.withLock {
                val homeInterval = if (forced) SyncSchedule.HOME_MIN_INTERVAL else SyncSchedule.HOME_INTERVAL
                if (isDue(SyncKeys.HOME, homeInterval, always = false)) {
                    progress(SyncPhase.MEAL_PLAN)
                    syncMealPlanAndHome()
                }
            }
            stopReason()?.let { return failed(it) }

            val viewed = recentlyViewedIds()
            prefetchDetails(usualDetailIds(viewed))
            stopReason()?.let { return failed(it) }
            if (fullIndexRecipes != null) fullSyncElapsedMillis = clock.elapsedMillis()
            if (downloadAll) {
                prefetchDetails(downloadAllDetailIds())
                stopReason()?.let { return failed(it) }
            }
            prefetchImages(viewed)

            progress(SyncPhase.OUTBOX)
            if (outbox.drain(remote) == DrainResult.RETRY) scheduler.scheduleOutbox()

            // Fas S (rester lane): old temporary-id mappings nothing needs any more, once a day.
            pruneIdMappings()

            markDone(SyncKeys.LAST_SUCCESS)
            return SyncProgress(SyncPhase.DONE).also { onProgress(it) }
        }

        // ------------------------------------------------------------ gate and bookkeeping

        /** Checks whether this run may start at all; a new login lifts an unauthorized pause. */
        private suspend fun openGate(): TandemError? {
            if (!network.online.value) return TandemError.NetworkUnavailable
            if (guard.state.value.unauthorizedAccount == accountId) {
                sources.reloadToken(accountId)
                guard.liftUnauthorizedIfTokenChanged(accountId, sources.tokenFingerprint(accountId))
            }
            return guard.check(accountId, background)
        }

        /** Why the run must stop now, or null to go on. */
        fun stopReason(): TandemError? {
            if (sourceChanged()) return SOURCE_CHANGED_ERROR
            return guard.check(accountId, background)
        }

        /** True when another account (or none) became active: nothing more may be written for this one. */
        private fun sourceChanged(): Boolean = sources.source.value.identity != remote.identity

        private fun failed(error: TandemError): SyncProgress = SyncProgress(SyncPhase.FAILED, error = error).also { onProgress(it) }

        private fun progress(phase: SyncPhase, done: Int = 0, total: Int? = null) {
            val progress = SyncProgress(phase, done, total)
            mutableState.update { it.copy(progress = progress) }
            onProgress(progress)
        }

        private suspend fun syncState(key: String): SyncStateEntity? = database.syncStateDao().get(accountId, key)

        private suspend fun updateSyncState(key: String, transform: (SyncStateEntity) -> SyncStateEntity) {
            val current = syncState(key) ?: SyncStateEntity(accountId = accountId, entity = key)
            database.syncStateDao().upsert(transform(current))
        }

        private suspend fun markDone(key: String) {
            val now = clock.nowMillis()
            updateSyncState(key) { it.copy(lastFullRelistEpochMillis = now) }
        }

        /**
         * True when the step [key] last finished at least [interval] ago (or never), or [always].
         * A time more than [SyncSchedule.CLOCK_SKEW_TOLERANCE] in the future also counts as due.
         */
        suspend fun isDue(key: String, interval: Duration, always: Boolean): Boolean {
            if (always) return true
            val last = syncState(key)?.lastFullRelistEpochMillis ?: return true
            val elapsed = clock.nowMillis() - last
            return elapsed < -SyncSchedule.CLOCK_SKEW_TOLERANCE.inWholeMilliseconds || elapsed >= interval.inWholeMilliseconds
        }

        /**
         * Every page of a list (page 1 without `page`), as one guarded call. A result that arrives
         * after the active source changed is reported as a failure, so it is never written.
         */
        private suspend fun <T> fetchAll(fetch: suspend TandoorApi.(page: Int?) -> PageDto<T>): Outcome<PagedResult<T>> {
            val result = caller.call(remote, background) {
                val api = this
                Paginator.fetchAll { page -> api.fetch(page.takeIf { it > 1 }) }
            }
            return if (sourceChanged()) Outcome.Failure(SOURCE_CHANGED_ERROR) else result
        }

        // ------------------------------------------------------------ 2. small tables

        private suspend fun syncReferenceData() {
            val recipeDao = database.recipeDao()
            val reference = database.referenceDao()
            val keywords = fetchAll { page -> taxonomy.keywords(page) }
            if (keywords is Outcome.Success && keywords.value.complete) {
                val rows = keywords.value.items.mapNotNull { it.toEntity(accountId) }
                val hash = rows.sortedBy { it.id }.hashCode().toString()
                if (syncState(SyncKeys.KEYWORDS)?.hash != hash) {
                    recipeDao.replaceKeywords(accountId, rows)
                    updateSyncState(SyncKeys.KEYWORDS) { it.copy(hash = hash) }
                }
            }
            if (stopReason() != null) return
            val foods = fetchAll { page -> taxonomy.foods(page) }
            if (foods is Outcome.Success && foods.value.complete) {
                reference.replaceFoods(accountId, foods.value.items.mapNotNull { it.toEntity(accountId) })
            }
            if (stopReason() != null) return
            val units = fetchAll { page -> taxonomy.units(page) }
            if (units is Outcome.Success && units.value.complete) {
                reference.replaceUnits(accountId, units.value.items.mapNotNull { it.toEntity(accountId) })
            }
            if (stopReason() != null) return
            val mealTypes = fetchAll { page -> taxonomy.mealTypes(page) }
            if (mealTypes is Outcome.Success && mealTypes.value.complete) {
                reference.replaceMealTypes(accountId, mealTypes.value.items.mapNotNull { it.toEntity(accountId) })
            }
            if (stopReason() != null) return
            val userPreferences = caller.call(remote, background) { preferences.userPreferences() }
            if (userPreferences is Outcome.Success && !sourceChanged()) {
                val own = userPreferences.value.firstOrNull()
                if (own != null) reference.upsertUserPreference(own.toModel().toEntity(accountId, clock.nowMillis()))
            }
        }

        /**
         * Times cooked, last cooked and the user's own rating, counted from the cook log (FACTS
         * "Fas 1 c": `last_cooked` is always null, `-times_cooked` answers 500 on 2.6 and lists
         * carry a rating only when sorted by it). The complete list replaces the server's part of
         * `cook_log` and every recipe is recounted in one transaction (`CookLogDao.reconcile`):
         * entries logged here and not yet sent (or sent after the list was requested) still
         * count, once; recipes without entries go back to "never cooked, not rated". The detail's
         * rating has the same meaning (FACTS "Fas 1 d"), so a later detail fetch agrees with it.
         */
        private suspend fun syncCookStatistics() {
            val requestedAt = clock.nowMillis()
            val cookLog = fetchAll { page -> logs.cookLogs(recipe = null, page = page) }
            if (cookLog is Outcome.Success && cookLog.value.complete) {
                database.cookLogDao().reconcile(
                    accountId = accountId,
                    serverEntries = cookLog.value.items.mapNotNull { it.toEntity(accountId) },
                    listRequestedAtEpochMillis = requestedAt,
                    nowEpochMillis = clock.nowMillis(),
                    rules = remote.cookStatsRules,
                )
            }
        }

        // ------------------------------------------------------------ 3. index and overviews

        private suspend fun syncIndex(): Outcome<Unit> {
            val flat = when (val result = caller.call(remote, background) { recipes.flat() }) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            val entries = flat.mapNotNull { it.toIndexEntry(imageResolver) }
            progress(SyncPhase.RECIPE_INDEX, done = 0, total = entries.size)
            val change = database.recipeDao().applyIndex(accountId, entries)
            if (stopReason() != null) return Outcome.Success(Unit)

            // Never later than today: a date stored while the clock was ahead would skip changes.
            val since = syncState(SyncKeys.RECIPES)?.lastDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { minOf(it, clock.today()) }
                ?.minus(1, DateTimeUnit.DAY)
            val startedOn = clock.today()
            val first = fetchOverviews(since, entries.size)
            val firstIds = (first as? Outcome.Success)?.value
            val missed = since != null && firstIds != null && change.inserted.any { it !in firstIds }
            // A recipe became visible without being changed (e.g. shared): read every overview.
            val fetched = if (missed) fetchOverviews(since = null, total = entries.size) else first
            if (fetched is Outcome.Failure) return fetched
            // Only the first complete read is the "full sync" of plan §10; a re-read because a recipe
            // became visible (often together with download-all work) is a different measurement.
            if (since == null) fullIndexRecipes = entries.size
            updateSyncState(SyncKeys.RECIPES) { it.copy(lastDate = startedOn.toString()) }
            markDone(SyncKeys.RECIPE_INDEX)
            return Outcome.Success(Unit)
        }

        /**
         * Reads overview pages (100 each) and stores each page in one transaction; returns the ids
         * seen. The run's stop conditions are checked before every page is written: after a
         * logout or an account switch nothing more is stored and the paging ends.
         */
        private suspend fun fetchOverviews(since: LocalDate?, total: Int): Outcome<Set<Long>> {
            val seen = HashSet<Long>()
            var stopped: TandemError? = null
            val query = RecipeListQuery(updatedOnOrAfter = since, sortOrder = RecipeSortOrder.NAME)
            val result = caller.call(remote, background) {
                val api = this
                Paginator.fetchAll { page ->
                    val response = api.recipes.list(query, page)
                    val stop = stopReason()
                    if (stop != null) {
                        stopped = stop
                        return@fetchAll response.copy(next = null)
                    }
                    val writes = response.results.mapNotNull { it.toOverviewWrite(accountId, imageResolver) }
                    database.recipeDao().upsertOverviews(accountId, writes)
                    writes.forEach { seen += it.overview.serverId }
                    if (since == null) progress(SyncPhase.RECIPE_INDEX, done = seen.size.coerceAtMost(total), total = total)
                    response
                }
            }
            stopped?.let { return Outcome.Failure(it) }
            return when (result) {
                is Outcome.Failure -> result
                is Outcome.Success -> Outcome.Success(seen)
            }
        }

        // ------------------------------------------------------------ 4. meal plan and Today

        suspend fun syncMealPlanAndHome(): Outcome<Unit> {
            val today = clock.today()
            // Phase 2 (matsedel lane): the same read as Veckan's refresh, which also drops queued
            // changes of meals deleted elsewhere before the outbox is sent (no 404).
            val plan = mealPlanWindow.read(remote, background)
            if (stopReason() == null) syncHomeSections(today)
            if (plan is Outcome.Failure) return plan
            if (sourceChanged()) return Outcome.Failure(SOURCE_CHANGED_ERROR)
            markDone(SyncKeys.HOME)
            return Outcome.Success(Unit)
        }

        /** The carousel queries of plan 3.3, each one page; a failed query keeps its cached result. */
        private suspend fun syncHomeSections(today: LocalDate) {
            val size = SyncSchedule.HOME_SECTION_SIZE
            val queries = listOf(
                HomeSectionType.OFTEN_COOKED to RecipeListQuery(
                    timesCookedAtLeast = 1,
                    sortOrder = RecipeSortOrder.LAST_COOKED_DESC,
                    pageSize = SyncSchedule.OFTEN_COOKED_CANDIDATES,
                ),
                // `new=true` only sorts new recipes first (FACTS "Fas 1 c"); the `new` flag filters.
                HomeSectionType.NEW to RecipeListQuery(newFirst = true, pageSize = size),
                HomeSectionType.RECENTLY_VIEWED to RecipeListQuery(
                    viewedOnOrAfter = today.minus(SyncSchedule.RECENTLY_VIEWED_DAYS, DateTimeUnit.DAY),
                    sortOrder = RecipeSortOrder.LAST_VIEWED_DESC,
                    pageSize = size,
                ),
                HomeSectionType.NOT_COOKED_LATELY to RecipeListQuery(
                    cookedOnOrBefore = today.minus(SyncSchedule.NOT_COOKED_LATELY_DAYS, DateTimeUnit.DAY),
                    sortOrder = RecipeSortOrder.LAST_COOKED,
                    pageSize = size,
                ),
            )
            val recipeDao = database.recipeDao()
            for ((type, query) in queries) {
                if (stopReason() != null) return
                val page = caller.call(remote, background) { recipes.list(query) }
                if (sourceChanged()) return
                if (page !is Outcome.Success) continue
                val results = page.value.results
                recipeDao.upsertOverviews(accountId, results.mapNotNull { it.toOverviewWrite(accountId, imageResolver) })
                val inOrder = results.mapNotNull { it.id }.distinct()
                val ids = when (type) {
                    HomeSectionType.OFTEN_COOKED -> {
                        // Server order (last cooked) for ties; the cook log's count first.
                        val counts = recipeDao.getListItems(accountId, inOrder)
                            .associate { it.overview.serverId to (it.overview.timesCooked ?: 0) }
                        inOrder.sortedByDescending { counts[it] ?: 0 }
                    }

                    HomeSectionType.NEW -> results.filter { it.isNew }.mapNotNull { it.id }

                    else -> inOrder
                }
                database.homeSectionDao().replaceSection(accountId, type, ids.take(size), clock.nowMillis())
            }
        }

        // ------------------------------------------------------------ 5. details and images

        private suspend fun plannedRecipeIds(): List<Long> {
            val today = clock.today()
            val from = today.minus(SyncSchedule.PLANNED_DAYS_BEFORE, DateTimeUnit.DAY).toString()
            val to = today.plus(SyncSchedule.PLANNED_DAYS_AFTER, DateTimeUnit.DAY).toString()
            return database.mealPlanDao().getRange(accountId, from, to).mapNotNull { it.entry.recipeId }.distinct()
        }

        /** Recently viewed recipes: opened on this device, and the server's "Recently viewed" carousel. */
        private suspend fun recentlyViewedIds(): List<Long> {
            val viewedLocally = database.recipeDao().observeRecentlyViewedLocal(accountId, SyncSchedule.HOME_SECTION_SIZE).first()
                .map { it.overview.serverId }
            val viewedOnServer = database.homeSectionDao().observeSectionRows(accountId).first()
                .filter { it.sectionType == HomeSectionType.RECENTLY_VIEWED }
                .map { it.item.overview.serverId }
            return (viewedLocally + viewedOnServer).distinct()
        }

        /**
         * Pinned (older than 24 h), planned and recently viewed recipes, then details the 0.2.0
         * migration marked for a new fetch (step photos, `getIdsMarkedForRefetch`), at most
         * [SyncSchedule.MAX_DETAIL_PREFETCH_PER_RUN]; the rest of the marked ones follow in later runs.
         */
        private suspend fun usualDetailIds(recentlyViewed: List<Long>): List<Long> {
            val recipes = database.recipeDao()
            val now = clock.nowMillis()
            val staleBefore = now - SyncSchedule.PINNED_DETAIL_MAX_AGE.inWholeMilliseconds
            // A fetch time in the future (the clock was ahead) counts as stale.
            val fetchedAfter = now + SyncSchedule.CLOCK_SKEW_TOLERANCE.inWholeMilliseconds
            val pinned = recipes.getPinnedIdsToRefresh(accountId, staleBefore, fetchedAfter)
            val planned = plannedRecipeIds().filter { id ->
                val row = recipes.getDetailRow(accountId, id)
                val fetchedAt = row?.fetchedAtEpochMillis ?: 0L
                row?.rawJson == null || fetchedAt < staleBefore || fetchedAt > fetchedAfter
            }
            val stored = recipes.getStoredDetailIds(accountId).toHashSet()
            val viewed = recentlyViewed.filter { it !in stored }
            val marked = recipes.getIdsMarkedForRefetch(accountId, SyncSchedule.MAX_DETAIL_PREFETCH_PER_RUN)
            // Only recipes the index knows: an id from stale data must not become a 404 (plan 4.1).
            val known = recipes.getRecipeIds(accountId).toHashSet()
            return (pinned + planned + viewed + marked).distinct()
                .filter { it in known }
                .take(SyncSchedule.MAX_DETAIL_PREFETCH_PER_RUN)
        }

        /**
         * "Download all recipes": every recipe of the index without a stored detail, then stored
         * details older than their recipe's `updated_at` (changed on the server since), in batches
         * of [SyncSchedule.MAX_DOWNLOAD_ALL_PER_RUN] per run (the rest follow in the next run).
         */
        private suspend fun downloadAllDetailIds(): List<Long> {
            val recipes = database.recipeDao()
            val cap = SyncSchedule.MAX_DOWNLOAD_ALL_PER_RUN
            val missing = recipes.getIdsWithoutDetail(accountId, cap)
            val stale = if (missing.size < cap) {
                recipes.getIdsWithStaleDetail(accountId, SyncSchedule.CLOCK_SKEW_TOLERANCE.inWholeMilliseconds, cap - missing.size)
            } else {
                emptyList()
            }
            return (missing + stale).distinct()
        }

        private suspend fun prefetchDetails(ids: List<Long>) {
            if (ids.isEmpty()) return
            progress(SyncPhase.RECIPE_DETAILS, done = 0, total = ids.size)
            val done = AtomicInteger()
            coroutineScope {
                ids.forEach { id ->
                    launch {
                        if (stopReason() != null) return@launch
                        details.fetch(remote, id, isBackground = true)
                        progress(SyncPhase.RECIPE_DETAILS, done = done.incrementAndGet(), total = ids.size)
                    }
                }
            }
        }

        /**
         * Images of pinned, planned and recently viewed recipes (in that order, so the per-run cap
         * drops viewed ones first). Only images that are settled (loaded, or gone for good) are
         * remembered; a temporary failure is tried again in the next run.
         */
        private suspend fun prefetchImages(recentlyViewed: List<Long>) {
            val recipes = database.recipeDao()
            val pinned = recipes.observePinned(accountId).first()
            val planned = recipes.getListItems(accountId, plannedRecipeIds())
            val viewed = recipes.getListItems(accountId, recentlyViewed)
            val usualRecipes = pinned + planned + viewed
            // fix-ui (0.2.0): the step photos of the same recipes, so cook mode shows them offline.
            val usualCandidates = (usualRecipes.mapNotNull { it.overview.imageUrl } + stepImages(usualRecipes.map { it.overview.serverId }))
                .distinct()
                .filter { it !in prefetched }
            // "Download all recipes": the photo of every recipe kept offline, after the usual ones.
            val everythingCandidates = if (downloadAll) {
                (recipes.getAllOverviews(accountId).mapNotNull { it.imageUrl } + recipes.getAllStepImageUrls(accountId))
                    .filter { it !in prefetched }
            } else {
                emptyList()
            }
            val candidates = (usualCandidates + everythingCandidates).distinct()
            if (candidates.isEmpty() || (background && guard.state.value.importsRunning > 0)) return
            // The in-memory set is empty in a new process (a periodic run often is one): images that
            // are already in the disk cache are skipped, so every run reaches the ones still missing.
            val cached = images.cached(candidates)
            prefetched.addAll(cached)
            val usual = usualCandidates.filter { it !in cached }.take(SyncSchedule.MAX_IMAGE_PREFETCH_PER_RUN)
            val everything = everythingCandidates.filter { it !in cached }.take(SyncSchedule.MAX_DOWNLOAD_ALL_PER_RUN)
            val urls = (usual + everything).distinct()
            if (urls.isEmpty()) return
            progress(SyncPhase.IMAGES, done = 0, total = urls.size)
            prefetched.addAll(images.prefetch(urls))
        }

        /**
         * Fas S (rester lane, Phase 2's open issue): `id_mapping` rows older than
         * `IdRewriteDao.MAPPING_RETENTION_MILLIS` that no outbox row or local table needs any more
         * (`IdRewriteDao.pruneMappings`, one transaction), at most every [ID_MAPPING_PRUNE_INTERVAL].
         */
        private suspend fun pruneIdMappings() {
            if (!isDue(SyncKeys.ID_MAPPING_PRUNE, ID_MAPPING_PRUNE_INTERVAL, always = false)) return
            database.idRewriteDao().pruneMappings(accountId, clock.nowMillis() - IdRewriteDao.MAPPING_RETENTION_MILLIS)
            markDone(SyncKeys.ID_MAPPING_PRUNE)
        }

        private suspend fun stepImages(recipeIds: List<Long>): List<String> =
            if (recipeIds.isEmpty()) emptyList() else database.recipeDao().getStepImageUrls(accountId, recipeIds.distinct())
    }

    private companion object {
        val SOURCE_CHANGED_ERROR: TandemError = SyncStop.SourceChanged

        /** Runs that read the whole shopping list (plan 3.5 "vid start"); the others read a delta. */
        val SHOPPING_FULL_READ: Set<SyncReason> = setOf(SyncReason.FIRST_SYNC, SyncReason.APP_START, SyncReason.ACCOUNT_CHANGED)

        /** Fas S (rester lane): how often [SyncRun.pruneIdMappings] runs. */
        val ID_MAPPING_PRUNE_INTERVAL: Duration = 1.days
    }
}
