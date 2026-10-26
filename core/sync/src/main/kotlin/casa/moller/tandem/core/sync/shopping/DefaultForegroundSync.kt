package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.sync.ForegroundSync
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.model.sync.ForegroundPolling
import casa.moller.tandem.core.model.sync.ForegroundSyncState
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.mealplan.MealPlanWindowReader
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * [ForegroundSync] (plan 3.5, 8.6; Phase 2 "Klart när": two phones see each other's checks within
 * 30 s while the list is open). One poller per target, shared by every collector and stopped when
 * the last one leaves (the screen left STARTED: no polling in the background):
 *
 * - **Shopping list:** [ShoppingSync.refresh] (outbox, then a delta; every 10th a full read with the
 *   window rule) every `ForegroundPolling.intervalFor(shopping_min_autosync_interval,
 *   shopping_auto_sync)` (20 s with Tandoor's defaults "5" and 5), starting at once; not at all
 *   while the user's `shopping_auto_sync` is 0 (the web's "off").
 * - **Meal plan:** `MealPlanRepository.refreshWeek(week)` every [MEAL_PLAN_INTERVAL]: the window
 *   read, or the shown week on its own when it lies outside the window (fix-ui). The 60 s count
 *   from the last read of the same window or week, not from subscribing (fix-ui: paging through
 *   weeks starts a poller per week, which must not read and drain at once each time), and a read
 *   that started finishes even if Veckan moves on to another week (it may be draining the outbox).
 * - **Paused** (nothing is requested, [ForegroundSyncState.paused] says why) while offline, after a
 *   rejected token, while blocked or throttled (403 without Tandoor's JSON, 429), while the circuit
 *   breaker is open and while an import runs. **Safe mode** (an untested server version) still
 *   reads; the outbox holds the changes (`OutboxSender`). Never in demo mode or without an account.
 */
@Singleton
internal class DefaultForegroundSync(
    private val sources: ActiveSources,
    private val sync: ShoppingSync,
    private val mealPlans: Provider<MealPlanRepository>,
    private val databases: TandemDatabaseFactory,
    private val guard: SyncGuard,
    private val network: NetworkStatus,
    private val clock: SyncClock,
    private val scope: CoroutineScope,
    private val wait: suspend (Duration) -> Unit,
) : ForegroundSync {
    @Inject
    constructor(
        sources: ActiveSources,
        sync: ShoppingSync,
        mealPlans: Provider<MealPlanRepository>,
        databases: TandemDatabaseFactory,
        guard: SyncGuard,
        network: NetworkStatus,
        clock: SyncClock,
        @ApplicationScope scope: CoroutineScope,
    ) : this(sources, sync, mealPlans, databases, guard, network, clock, scope, { delay(it) })

    private val pollers = ConcurrentHashMap<ForegroundTarget, Flow<ForegroundSyncState>>()

    /** When the meal plan window ([WINDOW]) or a week outside it was last read (epoch millis, fix-ui). */
    private val mealPlanReadAt = ConcurrentHashMap<LocalDate, Long>()

    override fun keepFresh(target: ForegroundTarget): Flow<ForegroundSyncState> = pollers.getOrPut(target) {
        poll(target).shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)
    }

    private fun poll(target: ForegroundTarget): Flow<ForegroundSyncState> = flow {
        var lastPollAt: Instant? = null
        if (target is ForegroundTarget.MealPlan) {
            // fix-ui: not again within 60 s of the last read of the same window or week.
            val since = mealPlanReadAt[mealPlanKey(target)]?.let { clock.nowMillis() - it }
            if (since != null && since in 0 until MEAL_PLAN_INTERVAL.inWholeMilliseconds) {
                emit(ForegroundSyncState(interval = MEAL_PLAN_INTERVAL))
                wait((MEAL_PLAN_INTERVAL.inWholeMilliseconds - since).milliseconds)
            }
        }
        while (true) {
            val remote = sources.current() as? ActiveSource.Remote
            val interval = remote?.let { intervalFor(target, it) } ?: ForegroundPolling.DEFAULT_INTERVAL
            val pause = remote?.let { pauseFor(it) }
            val polls = remote != null && (pause == null || pause == SyncPause.SAFE_MODE) && enabled(target, remote)
            if (polls && remote != null && pollOnce(target, remote) is Outcome.Success) {
                lastPollAt = Instant.fromEpochMilliseconds(clock.nowMillis())
            }
            emit(ForegroundSyncState(active = polls, interval = interval, lastPollAt = lastPollAt, paused = pause))
            wait(interval)
        }
    }

    private suspend fun pollOnce(target: ForegroundTarget, remote: ActiveSource.Remote): Outcome<Unit> = catchingUnexpected {
        when (target) {
            ForegroundTarget.ShoppingList -> sync.refresh(remote, background = true)

            is ForegroundTarget.MealPlan -> withContext(NonCancellable) {
                mealPlanReadAt[mealPlanKey(target)] = clock.nowMillis()
                mealPlans.get().refreshWeek(target.weekStart)
            }
        }
    }

    /** The window's key, or the week's own when it lies outside the window (read on its own). */
    private fun mealPlanKey(target: ForegroundTarget.MealPlan): LocalDate {
        val end = target.weekStart.plus(MealPlanWeek.DAYS - 1, DateTimeUnit.DAY)
        return if (MealPlanWindowReader.inWindow(target.weekStart, end, clock.today())) WINDOW else target.weekStart
    }

    /** Why [remote] is not polled now; [SyncPause.SAFE_MODE] still reads. */
    internal fun pauseFor(remote: ActiveSource.Remote): SyncPause? = when {
        !network.online.value -> SyncPause.OFFLINE

        else -> guard.pauseAt(guard.state.value, remote.accountId, clock.nowMillis())
            ?: SyncPause.SAFE_MODE.takeIf { remote.writePolicy != WritePolicy.SEND }
    }

    /**
     * The time between polls of [target]: for the list the server's floor (stored by [ShoppingTables])
     * and the user's own `shopping_auto_sync` (Phase 2 integration lane), whichever is longer.
     */
    internal suspend fun intervalFor(target: ForegroundTarget, remote: ActiveSource.Remote): Duration = when (target) {
        ForegroundTarget.ShoppingList -> ForegroundPolling.intervalFor(
            databases.get(remote.kind).shoppingDao().getMinAutosyncSeconds(remote.accountId),
            userAutoSyncSeconds(remote),
        )

        is ForegroundTarget.MealPlan -> MEAL_PLAN_INTERVAL
    }

    /**
     * Phase 2 (integration lane): `shopping_auto_sync` 0 (the web's automatic sync off) also stops
     * the app's polling of the list; pull to refresh and the background sync still read it. Checked
     * before every poll, so turning it on again on the web takes effect at the next sync.
     */
    private suspend fun enabled(target: ForegroundTarget, remote: ActiveSource.Remote): Boolean =
        target !is ForegroundTarget.ShoppingList || ForegroundPolling.pollsShoppingList(userAutoSyncSeconds(remote))

    private suspend fun userAutoSyncSeconds(remote: ActiveSource.Remote): Int? =
        databases.get(remote.kind).referenceDao().getUserPreference(remote.accountId)?.shoppingAutoSyncSeconds

    companion object {
        /** The meal plan while Veckan is visible: at most every 60 s (plan 3.5). */
        val MEAL_PLAN_INTERVAL: Duration = 60.seconds

        /** [mealPlanReadAt]'s key of the window (no real week starts then). */
        private val WINDOW = LocalDate(1970, 1, 1)
    }
}
