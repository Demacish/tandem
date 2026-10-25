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
 */
@Singleton
internal class MealPlanWindowReader @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val clock: SyncClock,
) {
    /** One read at a time (the sync run and Veckan's polling may meet). */
    private val lock = Mutex()

    suspend fun read(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> = lock.withLock {
        val today = clock.today()
        val from = today.minus(SyncSchedule.MEAL_PLAN_DAYS_BEFORE, DateTimeUnit.DAY)
        val to = today.plus(SyncSchedule.MEAL_PLAN_DAYS_AFTER, DateTimeUnit.DAY)
        val result = caller.call(remote, background) {
            val api = this
            Paginator.fetchAll { page -> api.mealPlans.mealPlans(from, to, page.takeIf { it > 1 }) }
        }
        if (sources.source.value.identity != remote.identity) return@withLock Outcome.Failure(SyncStop.SourceChanged)
        val plan = when (result) {
            is Outcome.Failure -> return@withLock result
            is Outcome.Success -> result.value
        }
        if (!plan.complete) return@withLock Outcome.Success(Unit)
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
        val syncState = database.syncStateDao()
        val state = syncState.get(accountId, SyncKeys.MEAL_PLAN) ?: SyncStateEntity(accountId = accountId, entity = SyncKeys.MEAL_PLAN)
        syncState.upsert(state.copy(windowFrom = from.toString(), windowTo = to.toString()))
        Outcome.Success(Unit)
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
}
