package casa.moller.tandem.core.sync.storereminder

import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.datastore.StoreReminderStore
import casa.moller.tandem.core.datastore.StoreVisitRecord
import casa.moller.tandem.core.domain.storereminder.EnterDecision
import casa.moller.tandem.core.domain.storereminder.StoreReminderCount
import casa.moller.tandem.core.domain.storereminder.StoreTransition
import casa.moller.tandem.core.domain.storereminder.StoreVisit
import casa.moller.tandem.core.domain.storereminder.StoreVisits
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.StoreReminderNotifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What one store's event led to (tests and the smoke hook read it). */
internal enum class StoreReminderOutcome {
    /** "Du är nära … – n saker på listan" was posted. */
    POSTED,

    /** A new visit, but nothing on the list: nothing posted (the next visit may remind). */
    EMPTY_LIST,

    /** A new visit, but notifications are off for the app or the channel. */
    NOT_ALLOWED,

    SAME_VISIT,
    TOO_SOON,
    OUT_OF_ORDER,

    /** EXIT: the visit ended, the store is re-armed. */
    LEFT,

    /** The store has no place or is turned off (a geofence that outlived its setting). */
    NOT_WATCHED,

    /** The reminder is off for the account. */
    OFF,

    /** Demo mode or nobody logged in: never. */
    NO_ACCOUNT,
}

/**
 * Fas 5 (butik lane, plan §7 #34, §10 Fas 5): one geofence event for the active account.
 *
 * ENTER follows the once-per-visit rule (core:domain [StoreVisits]); when it says "remind", the
 * list is brought up to date if the phone is online (one read through `RemoteCaller`, at most
 * [REFRESH_TIMEOUT_MILLIS]; offline the stored list counts) and the reminder is posted only when
 * something is still to buy. Only a posted reminder counts towards the gap, so an empty list never
 * silences the next visit. EXIT re-arms the store. Each store's visit is stored at once.
 *
 * Never in demo mode or without an account; guests get it (it only reads).
 */
@Singleton
internal class StoreReminderCheck @Inject constructor(
    private val sources: ActiveSources,
    private val store: StoreReminderStore,
    private val shopping: ShoppingRepository,
    private val supermarkets: SupermarketRepository,
    private val notifier: StoreReminderNotifier,
    private val network: NetworkStatus,
) {
    private val mutex = Mutex()

    /** Handles [transition] of [storeIds] that came at [atMillis]; one outcome per store. */
    suspend fun onTransition(storeIds: List<Long>, transition: StoreTransition, atMillis: Long): Map<Long, StoreReminderOutcome> =
        mutex.withLock {
            val source = sources.current() as? ActiveSource.Remote
                ?: return@withLock storeIds.associateWith { StoreReminderOutcome.NO_ACCOUNT }
            val accountId = source.accountId
            val record = store.observe(accountId).first()
            if (!record.enabled) return@withLock storeIds.associateWith { StoreReminderOutcome.OFF }
            val at = Instant.fromEpochMilliseconds(atMillis)
            val visits = record.visits.associateBy { it.storeId }.toMutableMap()
            val outcomes = LinkedHashMap<Long, StoreReminderOutcome>()
            for (storeId in storeIds.distinct()) {
                if (record.spots.none { it.storeId == storeId && it.enabled }) {
                    outcomes[storeId] = StoreReminderOutcome.NOT_WATCHED
                    continue
                }
                val visit = visits[storeId]?.toVisit() ?: StoreVisit()
                val (next, outcome) = when (transition) {
                    StoreTransition.EXIT -> StoreVisits.onExit(visit, at) to StoreReminderOutcome.LEFT
                    StoreTransition.ENTER -> enter(storeId, visit, at)
                }
                visits[storeId] = next.toRecord(storeId)
                outcomes[storeId] = outcome
            }
            store.update(accountId) { current ->
                // Only stores that still have a place keep a visit.
                val kept = current.spots.mapTo(HashSet()) { it.storeId }
                current.copy(visits = visits.values.filter { it.storeId in kept }.sortedBy { it.storeId })
            }
            outcomes
        }

    private suspend fun enter(storeId: Long, visit: StoreVisit, at: Instant): Pair<StoreVisit, StoreReminderOutcome> {
        val (entered, decision) = StoreVisits.onEnter(visit, at)
        return when (decision) {
            EnterDecision.SAME_VISIT -> entered to StoreReminderOutcome.SAME_VISIT
            EnterDecision.TOO_SOON -> entered to StoreReminderOutcome.TOO_SOON
            EnterDecision.OUT_OF_ORDER -> entered to StoreReminderOutcome.OUT_OF_ORDER
            EnterDecision.REMIND -> remind(storeId, entered, at)
        }
    }

    private suspend fun remind(storeId: Long, entered: StoreVisit, at: Instant): Pair<StoreVisit, StoreReminderOutcome> {
        if (!notifier.canPost()) return entered to StoreReminderOutcome.NOT_ALLOWED
        // Offline, failed or slow: the stored list counts (refresh never throws for server or network trouble).
        if (network.online.value) withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { shopping.refresh() }
        val count = StoreReminderCount.open(shopping.observeEntries().first(), at)
        if (count == 0) return entered to StoreReminderOutcome.EMPTY_LIST
        val name = supermarkets.observeSupermarkets().first().firstOrNull { it.id == storeId }?.name
        if (!notifier.post(storeId, name, count)) return entered to StoreReminderOutcome.NOT_ALLOWED
        return StoreVisits.reminded(entered, at) to StoreReminderOutcome.POSTED
    }

    private fun StoreVisitRecord.toVisit(): StoreVisit = StoreVisit(
        inside = inside,
        lastTransitionAt = lastTransitionMillis?.let(Instant::fromEpochMilliseconds),
        lastRemindedAt = lastRemindedMillis?.let(Instant::fromEpochMilliseconds),
    )

    private fun StoreVisit.toRecord(storeId: Long): StoreVisitRecord = StoreVisitRecord(
        storeId = storeId,
        inside = inside,
        lastTransitionMillis = lastTransitionAt?.toEpochMilliseconds(),
        lastRemindedMillis = lastRemindedAt?.toEpochMilliseconds(),
    )

    private companion object {
        /** One quick read of the list before the reminder; offline or slow, the stored list counts. */
        const val REFRESH_TIMEOUT_MILLIS = 8_000L
    }
}
