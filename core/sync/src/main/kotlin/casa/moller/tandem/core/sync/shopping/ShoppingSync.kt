package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.engine.OutboxSender
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One refresh of the shopping list outside a sync run (pull to refresh, the list screen's polling,
 * plan 3.5): the outbox first, so the read sees this device's changes on the server (its updates
 * and deletes are checked against a full read before they go, see [ShoppingEntryOutboxHandler]),
 * then the list ([ShoppingReader]: a delta, or the full list every 10th time or on request).
 */
@Singleton
internal class ShoppingSync @Inject constructor(
    private val reader: ShoppingReader,
    private val outbox: OutboxSender,
    private val scheduler: SyncScheduler,
    private val network: NetworkStatus,
) {
    /**
     * @param background true for polling (the circuit breaker may hold it back), false for pull to refresh.
     * @param full read the whole list (window rule) instead of a delta.
     */
    suspend fun refresh(remote: ActiveSource.Remote, background: Boolean, full: Boolean = false): Outcome<Unit> {
        if (!network.online.value) return Outcome.Failure(TandemError.NetworkUnavailable)
        if (outbox.drain(remote) == DrainResult.RETRY) scheduler.scheduleOutbox()
        return reader.sync(remote, background, forceFull = full)
    }
}

/**
 * The shopping list's step of a sync run (`SyncEngine`, plan 3.5): with the small tables also the
 * named lists, stores and aisles ([ShoppingTables]; otherwise only the queued food and aisle
 * changes are applied again on top of the foods the engine re-read), then the list ([ShoppingReader]:
 * the full list when [full], else a delta or every 10th time the full list). The engine sends the
 * outbox after it; updates and deletes of entries are checked before they go. A failure keeps the
 * stored list and never fails the run.
 */
@Singleton
internal class ShoppingSyncStep @Inject constructor(
    private val reader: ShoppingReader,
    private val tables: ShoppingTables,
    private val databases: TandemDatabaseFactory,
) {
    suspend fun run(remote: ActiveSource.Remote, background: Boolean, smallTables: Boolean, full: Boolean) {
        if (smallTables) {
            tables.sync(remote, background)
        } else {
            tables.reapplyQueued(databases.get(remote.kind), remote.accountId)
        }
        reader.sync(remote, background, forceFull = full)
    }
}
