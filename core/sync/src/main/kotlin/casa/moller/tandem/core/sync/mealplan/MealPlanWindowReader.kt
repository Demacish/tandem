package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.domain.mealplan.MealPlanDates
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.engine.SyncKeys
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toEntity
import casa.moller.tandem.core.sync.remote.toOverviewWrite
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the meal plan window (plan 3.5: −14..+42 days around today, compared in full) for the sync
 * run (`SyncEngine`, step 4) and for [MealPlanSync] (pull to refresh, Veckan's polling):
 *
 * - `GET /api/meal-plan/?from_date&to_date` filters on the server-local date of `to_date` (FACTS
 *   "Fas 1 g"), so every stored server meal whose `to_date` lies in the window is replaced by the
 *   answer (`MealPlanDao.replaceWindow`); meals with unsent changes stay as the user left them.
 * - **Deleted elsewhere** (plan 3.5, Phase 2 "Klart när"): a meal with unsent changes whose last
 *   known server `to_date` lies in the window but that the complete answer no longer lists was
 *   deleted on another device. Its outbox rows are dropped WITHOUT a request and the meal removed,
 *   so the drain after the read never meets a 404.
 * - Only a complete answer (every page) changes anything; one that arrives after the account
 *   changed is never written.
 * - [lock] serializes reads with the meal plan outbox handlers (0.3.0 review, fix-data, like
 *   `ShoppingReader.lock`): a meal whose CREATE is answered while the window is read would
 *   otherwise be missing from the answer and removed. Lock order: `OutboxSender`'s mutex, then
 *   [lock]; a read never drains.
 * - Before a PATCH or DELETE of a meal is sent, [stillOnServerLocked] makes sure a complete window
 *   was read after the last reconnect and within [VERIFY_MAX_AGE_MILLIS] (plan 3.5 "Innan outboxen
 *   töms läses … matsedelsfönstret"), so a meal deleted elsewhere while this phone was offline is
 *   dropped without a request also when only the outbox worker runs.
 */
@Singleton
internal class MealPlanWindowReader @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val clock: SyncClock,
    private val network: NetworkStatus,
) {
    /** Held by every read and by the meal plan outbox handlers while they send. */
    val lock = Mutex()

    @Volatile
    private var seen: SeenWindow? = null

    /**
     * Reads the window, or (fix-ui) only [range]: a week Veckan shows outside the window. A range
     * read replaces the stored server meals of those days only and leaves the window's state alone.
     */
    suspend fun read(remote: ActiveSource.Remote, background: Boolean, range: ClosedRange<LocalDate>? = null): Outcome<Unit> =
        lock.withLock { readLocked(remote, background, range) }

    /**
     * Before an update or delete of meal [serverId] (last known on server day [day]) is sent; the
     * caller holds [lock]. Returns false only when a complete window read after the last reconnect,
     * at most [VERIFY_MAX_AGE_MILLIS] old (read now if needed), covers [day] and lacks the meal: it
     * was deleted elsewhere. True when it was there or the window cannot tell (outside it, incomplete).
     */
    suspend fun stillOnServerLocked(remote: ActiveSource.Remote, serverId: Long, day: LocalDate?): Outcome<Boolean> {
        val now = clock.nowMillis()
        val fresh: (SeenWindow?) -> SeenWindow? = { window ->
            window?.takeIf {
                it.accountId == remote.accountId &&
                    now - it.readAtMillis in 0 until VERIFY_MAX_AGE_MILLIS &&
                    it.reconnections == network.reconnections
            }
        }
        val window = fresh(seen) ?: when (val read = readLocked(remote, background = true)) {
            is Outcome.Failure -> return read
            is Outcome.Success -> seen?.takeIf { it.accountId == remote.accountId } ?: return Outcome.Success(true)
        }
        if (day == null || day < window.from || day > window.to) return Outcome.Success(true)
        return Outcome.Success(serverId in window.ids)
    }

    /** A meal's CREATE was answered: it exists, whatever the last window read said. */
    fun noteCreated(accountId: String, serverId: Long) {
        seen = seen?.takeIf { it.accountId == accountId }?.let { it.copy(ids = it.ids + serverId) }
    }

    /** A 404 came back for a meal the last read listed: the next update or delete reads the window again first. */
    fun invalidateSeen() {
        seen = null
    }

    /** [read] for a caller that holds [lock]. */
    suspend fun readLocked(remote: ActiveSource.Remote, background: Boolean, range: ClosedRange<LocalDate>? = null): Outcome<Unit> {
        val requestedAt = clock.nowMillis()
        val reconnections = network.reconnections
        val today = clock.today()
        val from = range?.start ?: today.minus(SyncSchedule.MEAL_PLAN_DAYS_BEFORE, DateTimeUnit.DAY)
        val to = range?.endInclusive ?: today.plus(SyncSchedule.MEAL_PLAN_DAYS_AFTER, DateTimeUnit.DAY)
        val result = caller.call(remote, background) {
            val api = this
            Paginator.fetchAll { page -> api.mealPlans.mealPlans(from, to, page.takeIf { it > 1 }) }
        }
        if (sources.source.value.identity != remote.identity) return Outcome.Failure(SyncStop.SourceChanged)
        val plan = when (result) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        if (!plan.complete) return Outcome.Success(Unit)
        val accountId = remote.accountId
        val database = databases.get(remote.kind)
        val images = ImageResolver.forBaseUrl(remote.baseUrl)
        database.recipeDao().upsertOverviews(accountId, plan.items.mapNotNull { it.recipe?.toOverviewWrite(accountId, images) })
        val rows = plan.items.mapNotNull { it.toEntity(accountId) }
        val listed = rows.mapNotNullTo(HashSet()) { it.serverId }
        val gone = goneElsewhere(remote, listed, from, to)
        val mealPlans = database.mealPlanDao()
        mealPlans.deleteGone(accountId, gone)
        mealPlans.replaceWindow(accountId, from.toString(), to.toString(), rows)
        if (range != null) return Outcome.Success(Unit)
        val syncState = database.syncStateDao()
        val state = syncState.get(accountId, SyncKeys.MEAL_PLAN) ?: SyncStateEntity(accountId = accountId, entity = SyncKeys.MEAL_PLAN)
        syncState.upsert(state.copy(windowFrom = from.toString(), windowTo = to.toString()))
        seen = SeenWindow(accountId, listed, from, to, readAtMillis = requestedAt, reconnections = reconnections)
        return Outcome.Success(Unit)
    }

    /**
     * Server ids of meals with unsent outbox rows that the complete window answer no longer lists
     * although their last known server `to_date` lies in it. A row being sent right now is left alone.
     */
    private suspend fun goneElsewhere(remote: ActiveSource.Remote, listed: Set<Long>, from: LocalDate, to: LocalDate): List<Long> {
        val database = databases.get(remote.kind)
        val rows = database.outboxDao().getAllFor(remote.accountId, OutboxEntities.MEAL_PLAN)
        val candidates = rows.filter { it.serverId != null && it.serverId !in listed && it.state != OutboxState.IN_FLIGHT }
        return candidates.groupBy { requireNotNull(it.serverId) }.mapNotNull { (serverId, queued) ->
            val stored = database.mealPlanDao().get(remote.accountId, serverId)
            val day = if (stored != null) {
                MealPlanDates.serverDay(stored.toRaw) ?: MealPlanDates.serverDay(stored.toDate)
            } else {
                // Deleted on this device too: the DELETE row remembers the meal's last server day.
                queued.firstNotNullOfOrNull { row ->
                    if (row.op != OutboxOps.DELETE) return@firstNotNullOfOrNull null
                    val day = (MealPlanPayloads.parse(row.payload)?.get(MealPlanPayloads.TO_DATE) as? JsonPrimitive)?.contentOrNull
                    MealPlanDates.serverDay(day)
                }
            }
            serverId.takeIf { day != null && day >= from && day <= to }
        }
    }

    /** The meals a complete window read listed (plus own creates since), for [stillOnServerLocked]. */
    private data class SeenWindow(
        val accountId: String,
        val ids: Set<Long>,
        val from: LocalDate,
        val to: LocalDate,
        val readAtMillis: Long,
        val reconnections: Int,
    )

    companion object {
        /** True when the days [from]..[to] lie in the window around [today] (plan 3.5: −14..+42). */
        fun inWindow(from: LocalDate, to: LocalDate, today: LocalDate): Boolean =
            from >= today.minus(SyncSchedule.MEAL_PLAN_DAYS_BEFORE, DateTimeUnit.DAY) &&
                to <= today.plus(SyncSchedule.MEAL_PLAN_DAYS_AFTER, DateTimeUnit.DAY)

        /** A window read this recent is good enough to send updates and deletes against. */
        const val VERIFY_MAX_AGE_MILLIS: Long = 60_000L
    }
}
