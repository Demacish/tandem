package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.InventoryLocationEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.InventoryEntryDto
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fas S (hemma-data lane): "the pantry or 'har hemma' changed" for an account (a local write, an
 * answered outbox row, a read that brought someone else's change). "Kan laga nu" refreshes on it
 * (at most once a minute, `DefaultMakeNowRepository`).
 */
@Singleton
internal class PantryChanges @Inject constructor() {
    private val mutable = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)

    /** Account ids, one per change. */
    val changes: SharedFlow<String> = mutable.asSharedFlow()

    fun notify(accountId: String) {
        mutable.tryEmit(accountId)
    }

    private companion object {
        const val BUFFER = 16
    }
}

/**
 * Reads the pantry (plan 3.5 "Små tabeller", §8.8): every location and every entry with amount > 0
 * (without `empty`, so "Slut" entries leave the device), in full; a list that was not read
 * completely replaces nothing. The server lists the WHOLE space's locations (FACTS "Fas S b"); all
 * are stored and the repository shows the account's household only. Guests get 403 on these lists,
 * so nothing is read for a guest.
 *
 * Rows with changes still in the outbox keep their local state (a PATCH waits: the local row wins;
 * a DELETE waits: the server row is not brought back); the device's order of the locations stays.
 * [lock] is held by the senders too, so a read is never compared with a change being sent.
 */
@Singleton
internal class PantryReader @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
    private val clock: SyncClock,
    private val changes: PantryChanges,
) {
    /** Held by every pantry read and by the location and entry senders. */
    val lock = Mutex()

    /** Per account: the entry ids (amount 0 included) of the last complete existence check, and when. */
    private val seen = ConcurrentHashMap<String, Seen>()

    private class Seen(val ids: MutableSet<Long>, val atMillis: Long)

    /** The full read (a sync run's pantry step, pull to refresh in Hemma). */
    suspend fun sync(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> = lock.withLock { syncLocked(remote, background) }

    private suspend fun syncLocked(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> {
        val database = databases.get(remote.kind)
        val accountId = remote.accountId
        if (isGuest(database, accountId)) return Outcome.Success(Unit)
        val locations = caller.call(remote, background) {
            val api: TandoorApi = this
            Paginator.fetchAll(maxPages = MAX_PAGES) { page -> api.inventory.locations(page.takeIf { it > 1 }) }
        }
        val locationPage = when (locations) {
            is Outcome.Failure -> return locations
            is Outcome.Success -> locations.value
        }
        val entries = caller.call(remote, background) {
            val api: TandoorApi = this
            Paginator.fetchAll(maxPages = MAX_PAGES) { page -> api.inventory.entries(page.takeIf { it > 1 }) }
        }
        val entryPage = when (entries) {
            is Outcome.Failure -> return entries
            is Outcome.Success -> entries.value
        }
        if (sources.source.value.identity != remote.identity) return Outcome.Failure(SyncStop.SourceChanged)
        if (!locationPage.complete || !entryPage.complete) return Outcome.Success(Unit)
        val now = clock.nowMillis()
        store(database, accountId, locationPage.items.mapNotNull { it.toEntity(accountId, order = 0, now = now) }, entryPage.items, now)
        changes.notify(accountId)
        return Outcome.Success(Unit)
    }

    private suspend fun store(
        database: TandemDatabase,
        accountId: String,
        serverLocations: List<InventoryLocationEntity>,
        serverEntries: List<InventoryEntryDto>,
        now: Long,
    ) {
        val dao = database.pantryDao()
        val outbox = database.outboxDao()
        dao.inTransaction {
            val oldLocations = dao.getLocations(accountId).associateBy { it.localId }
            val oldEntries = dao.getEntries(accountId).associateBy { it.localId }
            val queuedLocations = outbox.getAllFor(accountId, OutboxEntities.INVENTORY_LOCATION)
            val queuedEntries = outbox.getAllFor(accountId, OutboxEntities.INVENTORY_ENTRY)
            val locations = serverLocations.distinctBy { it.localId }
                .map { row -> row.copy(locationOrder = oldLocations[row.localId]?.locationOrder ?: 0) }
                .let { rows -> keepLocal(rows, oldLocations, queuedLocations) { it.localId } }
            val entries = serverEntries.mapNotNull { it.toEntity(accountId, now) }.distinctBy { it.localId }
                .let { rows -> keepLocal(rows, oldEntries, queuedEntries) { it.localId } }
            dao.replaceServerRows(accountId, locations, entries)
        }
    }

    /**
     * [rows] with the local state of objects whose changes still wait: a queued PATCH keeps the
     * local row, a queued DELETE keeps the object away.
     */
    private fun <T> keepLocal(rows: List<T>, old: Map<Long, T>, queued: List<OutboxEntity>, key: (T) -> Long): List<T> {
        val deleting = queued.filter { it.op == OutboxOps.DELETE }.mapNotNullTo(HashSet()) { it.serverId }
        val patching = queued.filter { it.op == OutboxOps.PATCH }.mapNotNullTo(HashSet()) { it.serverId }
        return rows.filterNot { key(it) in deleting }.map { row -> if (key(row) in patching) old[key(row)] ?: row else row }
    }

    /**
     * Whether entry [serverId] still exists (amount 0 included), from a complete read with `empty`
     * not older than [CHECK_MAX_AGE_MILLIS]: an entry another phone deleted is dropped without a
     * request (no 404 burst in the proxy's log, plan 3.5). Called by the sender under [lock]. An
     * incomplete read says "yes" (the request is sent and a 404 handled).
     */
    suspend fun entryStillOnServerLocked(remote: ActiveSource.Remote, serverId: Long): Outcome<Boolean> {
        val known = seen[remote.accountId]?.takeIf { clock.nowMillis() - it.atMillis in 0..CHECK_MAX_AGE_MILLIS }
        if (known != null) return Outcome.Success(serverId in known.ids)
        val listed = caller.call(remote, background = true) {
            val api: TandoorApi = this
            Paginator.fetchAll(maxPages = MAX_PAGES) { page -> api.inventory.entries(page.takeIf { it > 1 }, empty = true) }
        }
        return when (listed) {
            is Outcome.Failure -> listed

            is Outcome.Success -> {
                if (!listed.value.complete) return Outcome.Success(true)
                val ids = listed.value.items.mapNotNullTo(ConcurrentHashMap.newKeySet<Long>()) { it.id }
                seen[remote.accountId] = Seen(ids, clock.nowMillis())
                Outcome.Success(serverId in ids)
            }
        }
    }

    /** An entry this device just created is known to exist. */
    fun noteEntryCreated(accountId: String, serverId: Long) {
        seen[accountId]?.ids?.add(serverId)
    }

    /** The next existence check reads the list again (after a 404). */
    fun invalidateSeen(accountId: String) {
        seen.remove(accountId)
    }

    companion object {
        /** 200 per page: at most 10 000 locations or entries. */
        const val MAX_PAGES: Int = 50

        /** How long an existence check is reused by later rows of the same drain. */
        const val CHECK_MAX_AGE_MILLIS: Long = 60_000
    }
}
