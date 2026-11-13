package casa.moller.tandem.wear.sync

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.wear.data.CredentialsStore
import casa.moller.tandem.wear.data.WearState
import casa.moller.tandem.wear.data.WearStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The watch's sync (plan §7 #33, §5.7): first the outbox, then the list, straight to Tandoor with
 * the watch's own token over whatever network the watch has (its own Wi-Fi or LTE, or the phone's
 * connection while they are near; the platform decides). One sync at a time; every answer goes
 * through [WearGuard], so a revoked token, a 429 or a failing server never gets a second request
 * before its pause is over.
 *
 * Checking an item ([setChecked]) only writes the outbox; [sync] sends it as one `bulk` call per
 * checked state (deleted entries are ignored by the server, never a 404). The server keeps the
 * entries; Tandem never deletes anything from the watch.
 */
class WearSync(
    private val credentials: CredentialsStore,
    private val store: WearStateStore,
    private val remotes: ShoppingRemote.Factory,
    private val clock: () -> Long,
) {
    private val running = Mutex()

    /** What a [sync] did. */
    sealed interface Result {
        /** The outbox is empty and the list is fresh. */
        data object Done : Result

        /** The list was read a moment ago and nothing waits: no request was sent. */
        data object Fresh : Result

        /** No pairing: nothing to do until the phone pairs the watch. */
        data object NotPaired : Result

        /** The token was refused: nothing until the phone pairs the watch again. */
        data object SignedOut : Result

        /** No network: try again when the watch is online. */
        data object Offline : Result

        /** The guard holds requests back until [untilMillis] (wall clock). */
        data class Paused(val untilMillis: Long) : Result
    }

    /**
     * Sends the outbox and reads the list. Without [force] the list is read at most every
     * [WearGuard.MIN_READ_INTERVAL]; the outbox is always sent when the guard allows it.
     */
    suspend fun sync(force: Boolean = false): Result = running.withLock {
        val pairing = credentials.load() ?: return@withLock Result.NotPaired
        val state = store.load()
        if (state.account?.accountKey != pairing.accountKey) return@withLock Result.NotPaired
        if (state.signedOut) return@withLock Result.SignedOut
        val now = clock()
        if (!WearGuard.mayRequest(state.guard, now)) return@withLock Result.Paused(state.guard.pausedUntilMillis)
        val remote = remotes.create(pairing)
        flush(remote)?.let { return@withLock it }
        val afterFlush = store.load()
        val fresh = afterFlush.lastSyncMillis?.let { now - it < WearGuard.MIN_READ_INTERVAL.inWholeMilliseconds } == true
        if (!force && fresh && afterFlush.pending.isEmpty()) return@withLock Result.Fresh
        read(remote, afterFlush, now)
    }

    /** The user checked or unchecked [ids] (one row, maybe merged): the outbox gets the wish. */
    suspend fun setChecked(ids: Collection<Long>, checked: Boolean): WearState = store.update { state ->
        val onServer = state.entries.associate { it.id to it.checked }
        val pending = state.pending.toMutableMap()
        ids.forEach { id ->
            // Back to what the server has: nothing to send (two taps that cancel each other).
            if (onServer[id] == checked) pending.remove(id) else pending[id] = checked
        }
        state.copy(pending = pending)
    }

    /** The store whose aisle order the list follows (null: the first one with an order). */
    suspend fun chooseSupermarket(id: Long?): WearState = store.update { it.copy(supermarketId = id) }

    private suspend fun flush(remote: ShoppingRemote): Result? {
        val pending = store.load().pending
        if (pending.isEmpty()) return null
        val byState = pending.entries.groupBy({ it.value }, { it.key })
        for ((checked, ids) in byState) {
            when (val sent = remote.setChecked(ids.sorted(), checked)) {
                is Outcome.Failure -> return failed(sent.error)

                is Outcome.Success -> store.update { state ->
                    val done = ids.filter { state.pending[it] == checked }.toSet()
                    state.copy(
                        entries = state.entries.map { if (it.id in done) it.copy(checked = checked) else it },
                        pending = state.pending - done,
                        guard = WearGuard.success(),
                    )
                }
            }
        }
        return null
    }

    private suspend fun read(remote: ShoppingRemote, state: WearState, now: Long): Result {
        val entries = when (val read = remote.entries()) {
            is Outcome.Failure -> return failed(read.error)
            is Outcome.Success -> read.value
        }
        val storesDue = state.supermarketsReadMillis?.let { now - it >= WearGuard.SUPERMARKETS_INTERVAL.inWholeMilliseconds } ?: true
        val stores = if (storesDue) {
            when (val read = remote.supermarkets()) {
                is Outcome.Failure -> return failed(read.error)
                is Outcome.Success -> read.value
            }
        } else {
            null
        }
        store.update { current ->
            current.copy(
                entries = entries,
                supermarkets = stores ?: current.supermarkets,
                supermarketsReadMillis = if (stores != null) now else current.supermarketsReadMillis,
                lastSyncMillis = now,
                guard = WearGuard.success(),
            )
        }
        return Result.Done
    }

    private suspend fun failed(error: TandemError): Result {
        val now = clock()
        val current = store.load()
        return when (val verdict = WearGuard.failure(current.guard, error, now)) {
            WearGuard.Verdict.SignedOut -> {
                store.update { it.copy(signedOut = true) }
                Result.SignedOut
            }

            is WearGuard.Verdict.Offline -> {
                store.update { it.copy(guard = verdict.guard) }
                Result.Offline
            }

            is WearGuard.Verdict.Pause -> {
                store.update { it.copy(guard = verdict.guard) }
                Result.Paused(verdict.guard.pausedUntilMillis)
            }
        }
    }
}
