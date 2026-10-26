package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.SyncEntities
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.domain.shopping.ShoppingWindowRule
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.dto.ShoppingListEntryDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.paging.PagedResult
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the shopping list from Tandoor into Room (plan 3.5 "Synkstrategi", 5.7; FACTS "Fas 2 b"):
 *
 * - **Delta:** `updated_after` = the server's last `timestamp` (minus [OVERLAP_MILLIS], so a change
 *   committed while the last answer was built is never missed), `page_size` 200. Every
 *   [FULL_EVERY]th read, the first one and on request: the **full** list.
 * - **Window rule** (full reads only, `ShoppingWindowRule`): a stored server entry missing from a
 *   complete answer below 1000 entries was deleted elsewhere (removed locally, and its queued
 *   changes are dropped without being sent: plan 3.5 "Outbox-rader vars entitet inte längre finns
 *   på servern tas bort utan att skickas"), unless it was checked before the window: then it
 *   aged out and moves to `shopping_history`. Entries only on this device are never touched.
 * - **Local changes win until sent:** an entry (or list recipe) with rows in the outbox keeps its
 *   local state; the server's copy is taken once the change is sent.
 * - **Others' changes** (new entries of other members, checks, deletions) go to [ShoppingChangeBus],
 *   never on the first read of an account.
 *
 * [lock] serializes reads with the shopping outbox handlers, so a read never compares the list with
 * a change that is being sent at the same time (a CREATE answered while the list was read would
 * otherwise look deleted). Lock order: `OutboxSender`'s mutex, then [lock]; a read never drains.
 */
@Singleton
internal class ShoppingReader @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
    private val clock: SyncClock,
    private val changes: ShoppingChangeBus,
    private val dropped: DroppedChanges,
    private val network: NetworkStatus,
) {
    /** Held by every read and by the shopping outbox handlers while they send. */
    val lock = Mutex()

    @Volatile
    private var seen: SeenOnServer? = null

    /** Reads the list (delta, or full when due or [forceFull]); takes [lock]. */
    suspend fun sync(remote: ActiveSource.Remote, background: Boolean, forceFull: Boolean = false): Outcome<Unit> =
        lock.withLock { syncLocked(remote, background, forceFull) }

    /** [sync] for a caller that holds [lock]. */
    suspend fun syncLocked(remote: ActiveSource.Remote, background: Boolean, forceFull: Boolean): Outcome<Unit> {
        val state = database(remote).syncStateDao().get(remote.accountId, SyncEntities.SHOPPING_ENTRY)
        val since = state?.lastServerTimestamp
        if (forceFull || state == null || since == null) return readFull(remote, background)
        return if (state.deltaCount >= FULL_EVERY - 1) readFull(remote, background) else readDelta(remote, background, since, state)
    }

    /**
     * Before an update or delete of an entry is sent (caller holds [lock]): the full list, unless one
     * was read within [VERIFY_MAX_AGE_MILLIS] AND the device has not been offline since (Phase 2
     * integration lane: changes made offline are always checked against a list read after the
     * network came back, so rows another phone deleted meanwhile drain without a 404 even when the
     * device was offline for less than a minute). Returns whether [serverId] is still on the server:
     * false only when a complete answer lacked it (it is gone; the change must be dropped), true when
     * it was there or the answer cannot tell (1000 entries, incomplete).
     */
    suspend fun stillOnServerLocked(remote: ActiveSource.Remote, serverId: Long): Outcome<Boolean> {
        val now = clock.nowMillis()
        val known = seen?.takeIf {
            it.accountId == remote.accountId && now - it.readAtMillis in 0 until VERIFY_MAX_AGE_MILLIS && it.reconnections == network.reconnections
        }
        val snapshot = known ?: when (val read = readFull(remote, background = true)) {
            is Outcome.Failure -> return read
            is Outcome.Success -> seen ?: return Outcome.Success(true)
        }
        return Outcome.Success(!snapshot.complete || serverId in snapshot.ids)
    }

    /** A CREATE was answered: the new entry exists, whatever the last full read said. */
    fun noteCreated(accountId: String, serverId: Long) {
        seen = seen?.takeIf { it.accountId == accountId }?.let { it.copy(ids = it.ids + serverId) }
    }

    // ------------------------------------------------------------------------------------ reads

    private suspend fun readFull(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> {
        val requestedAt = clock.nowMillis()
        val reconnections = network.reconnections
        val page = when (val result = fetchAll(remote, background) { page -> shopping.entries(updatedAfter = null, page = page) }) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val database = database(remote)
        val accountId = remote.accountId
        val firstRead = database.syncStateDao().get(accountId, SyncEntities.SHOPPING_ENTRY)?.lastServerTimestamp == null
        val canDetect = ShoppingWindowRule.canDetectDeletions(page.items.size, page.complete)
        var events = emptyList<ShoppingChange>()
        database.shoppingDao().inTransaction {
            events = store(remote, page.items, full = canDetect, now = clock.nowMillis())
            val previous = database.syncStateDao().get(accountId, SyncEntities.SHOPPING_ENTRY)
            database.syncStateDao().upsert(
                (previous ?: SyncStateEntity(accountId = accountId, entity = SyncEntities.SHOPPING_ENTRY)).copy(
                    lastServerTimestamp = page.timestamp ?: previous?.lastServerTimestamp ?: TandoorDates.format(
                        java.time.Instant.ofEpochMilli(requestedAt),
                    ),
                    lastFullRelistEpochMillis = requestedAt,
                    deltaCount = 0,
                ),
            )
        }
        val ids = page.items.mapNotNull { it.id }.toSet()
        seen = SeenOnServer(accountId, ids, complete = canDetect, readAtMillis = requestedAt, reconnections = reconnections)
        if (!firstRead) events.forEach { changes.emit(accountId, it) }
        return Outcome.Success(Unit)
    }

    private suspend fun readDelta(remote: ActiveSource.Remote, background: Boolean, since: String, state: SyncStateEntity): Outcome<Unit> {
        val after = overlapped(since)
        val page = when (val result = fetchAll(remote, background) { page -> shopping.entries(updatedAfter = after, page = page) }) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val database = database(remote)
        var events = emptyList<ShoppingChange>()
        database.shoppingDao().inTransaction {
            events = store(remote, page.items, full = false, now = clock.nowMillis())
            database.syncStateDao().upsert(
                state.copy(lastServerTimestamp = page.timestamp ?: since, deltaCount = state.deltaCount + 1),
            )
        }
        seen = seen?.takeIf { it.accountId == remote.accountId }?.let { snapshot ->
            snapshot.copy(ids = snapshot.ids + page.items.mapNotNull { it.id })
        }
        events.forEach { changes.emit(remote.accountId, it) }
        return Outcome.Success(Unit)
    }

    /**
     * Writes [items] (inside the caller's transaction) and, when [full] (a complete full answer),
     * applies the window rule to the stored server entries it lacks. Returns others' changes.
     */
    private suspend fun store(
        remote: ActiveSource.Remote,
        items: List<ShoppingListEntryDto>,
        full: Boolean,
        now: Long,
    ): List<ShoppingChange> {
        val database = database(remote)
        val accountId = remote.accountId
        val dao = database.shoppingDao()
        val outbox = database.outboxDao()
        val pendingEntries = outbox.getAllFor(accountId, OutboxEntities.SHOPPING_ENTRY).pendingServerIds()
        val pendingListRecipes = outbox.getAllFor(accountId, OutboxEntities.SHOPPING_LIST_RECIPE).pendingServerIds()
        val stored = dao.getEntries(accountId)
        val storedByServerId = stored.filter { it.serverId != null }.associateBy { it.serverId }
        val incoming = items.mapNotNull { it.toEntity(accountId, now) }
        val events = mutableListOf<ShoppingChange>()
        for (entry in incoming) {
            if (entry.serverId in pendingEntries) continue
            val before = storedByServerId[entry.serverId]
            when {
                before == null && entry.createdBy != remote.account.userId ->
                    events += ShoppingChange.Added(entry.foodName, entry.createdByName)

                before != null && !before.checked && entry.checked -> events += ShoppingChange.Checked(entry.foodName)
            }
        }
        dao.upsertEntries(incoming.filter { it.serverId !in pendingEntries })
        val listRecipes = items.mapNotNull { it.listRecipeData?.toEntity(accountId) }.distinctBy { it.localId }
        dao.upsertListRecipes(listRecipes.filter { it.serverId !in pendingListRecipes })
        if (full) events += applyWindowRule(remote, database, stored, incoming.mapNotNullTo(HashSet()) { it.serverId }, now)
        return events
    }

    /** The window rule for every stored server entry the complete answer lacks. */
    private suspend fun applyWindowRule(
        remote: ActiveSource.Remote,
        database: TandemDatabase,
        stored: List<ShoppingEntryEntity>,
        onServer: Set<Long>,
        now: Long,
    ): List<ShoppingChange> {
        val accountId = remote.accountId
        val dao = database.shoppingDao()
        val outbox = database.outboxDao()
        val rows = outbox.getAllFor(accountId, OutboxEntities.SHOPPING_ENTRY)
        val recentDays = database.referenceDao().getUserPreference(accountId)?.shoppingRecentDays ?: DEFAULT_RECENT_DAYS
        val nowInstant = Instant.fromEpochMilliseconds(now)
        // A queued change of an entry that is gone is not a reason to keep it (plan 3.5): it is
        // judged like any other, and its changes are dropped unsent when it was deleted.
        val verdicts = stored.filter { entry -> entry.serverId?.let { it !in onServer } == true }
            .associateWith { verdictOf(it, nowInstant, recentDays) }
        val queuedIds = rows.mapNotNullTo(HashSet()) { it.serverId }
        val deleted = verdicts.filterValues { it == ShoppingWindowRule.Verdict.DELETED }.keys.toList()
        // Aged out with a change still to send: kept, the change goes out (the entry exists).
        val agedOut = verdicts.filter { (entry, verdict) -> verdict == ShoppingWindowRule.Verdict.AGED_OUT && entry.serverId !in queuedIds }
            .keys
            .toList()
        val unsent = rows.filter { row -> row.state != OutboxState.IN_FLIGHT && deleted.any { it.serverId == row.serverId } }
        if (unsent.isNotEmpty()) {
            outbox.deleteWithDependents(unsent.map { it.id })
            unsent.forEach { dropped.record(DroppedChange(it.entity, it.op, it.serverId, DroppedChanges.DELETED_ELSEWHERE, now)) }
        }
        if (deleted.isNotEmpty()) dao.deleteEntries(accountId, deleted.map { it.localId })
        if (agedOut.isNotEmpty()) dao.archive(accountId, agedOut.map { it.toHistory(now) }, agedOut.map { it.localId })
        removeOrphanListRecipes(remote, database)
        return deleted.map { ShoppingChange.Removed(it.foodName) }
    }

    private fun verdictOf(entry: ShoppingEntryEntity, now: Instant, recentDays: Int): ShoppingWindowRule.Verdict =
        ShoppingWindowRule.missingEntry(
            onServer = true,
            hasUnsentChanges = false,
            checked = entry.checked,
            completedAt = entry.completedAt?.let { Instant.fromEpochMilliseconds(it) },
            now = now,
            recentDays = recentDays,
        )

    /** Server list recipes no entry points to any more (deleted with their entries) and nothing queued for. */
    private suspend fun removeOrphanListRecipes(remote: ActiveSource.Remote, database: TandemDatabase) {
        val accountId = remote.accountId
        val dao = database.shoppingDao()
        val used = dao.getEntries(accountId).mapNotNullTo(HashSet()) { it.listRecipeId }
        val queued = database.outboxDao().getAllFor(accountId, OutboxEntities.SHOPPING_LIST_RECIPE)
            .flatMap { listOfNotNull(it.serverId, it.localId) }
            .toSet()
        val orphans = dao.getListRecipes(accountId).filter { it.serverId != null && it.localId !in used && it.localId !in queued }
        if (orphans.isNotEmpty()) dao.deleteListRecipes(accountId, orphans.map { it.localId })
    }

    private suspend fun <T> fetchAll(
        remote: ActiveSource.Remote,
        background: Boolean,
        fetch: suspend TandoorApi.(page: Int?) -> PageDto<T>,
    ): Outcome<PagedResult<T>> {
        val result = caller.call(remote, background) {
            val api = this
            Paginator.fetchAll(maxPages = MAX_PAGES) { page -> api.fetch(page.takeIf { it > 1 }) }
        }
        // A response that arrives after the account changed is never written.
        return if (sources.source.value.identity != remote.identity) Outcome.Failure(SyncStop.SourceChanged) else result
    }

    private fun database(remote: ActiveSource.Remote): TandemDatabase = databases.get(remote.kind)

    private fun List<OutboxEntity>.pendingServerIds(): Set<Long> = mapNotNullTo(HashSet()) { it.serverId }

    /** [since] moved back by [OVERLAP_MILLIS] (in `Z` form, which Tandoor reads, FACTS "Fas 2 b"); as it is when unreadable. */
    private fun overlapped(since: String): String {
        val instant = TandoorDates.parseInstantOrNull(since) ?: return since
        return TandoorDates.format(instant.minusMillis(OVERLAP_MILLIS))
    }

    /** The ids a full read returned (plus later deltas and own creates), for [stillOnServerLocked]. */
    private data class SeenOnServer(
        val accountId: String,
        val ids: Set<Long>,
        val complete: Boolean,
        val readAtMillis: Long,
        val reconnections: Int,
    )

    companion object {
        /** Every 10th read of the list is a full one (plan 3.5). */
        const val FULL_EVERY: Int = 10

        /** Tandoor's default `shopping_recent_days` when the preferences are not stored yet. */
        const val DEFAULT_RECENT_DAYS: Int = 7

        /** A full read this recent is good enough to send updates and deletes against. */
        const val VERIFY_MAX_AGE_MILLIS: Long = 60_000L

        /** Deltas start this much before the last answer's `timestamp`. */
        const val OVERLAP_MILLIS: Long = 2_000L

        /** 1000 entries at most (the server's cap) at 200 per page, with room to spare. */
        const val MAX_PAGES: Int = 10
    }
}
