package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.sync.account.ActiveSource

/**
 * Phase 2 (grund lane): sends the outbox rows of ONE entity (docs/BYGGE.md "Fas 2 – kontrakt",
 * table "Outbox"), so the shopping, meal plan and books lanes never edit [OutboxSender] at the same
 * time. A lane binds its handler in its own Hilt module:
 *
 * ```
 * @Binds @IntoMap @StringKey(OutboxEntities.SHOPPING_ENTRY)
 * abstract fun bindShoppingEntryHandler(impl: ShoppingEntryOutboxHandler): OutboxHandler
 * ```
 *
 * [OutboxSender] keeps the shared rules: safe mode, readiness and order (`OutboxDao.nextReady`),
 * the payload's temporary ids resolved through `id_mapping` (`OutboxRefs`) before [send], the id
 * rewrite after a CREATE ([OutboxResult.Created] → `IdRewriteDao.completeCreate`), and what an
 * error means (404 = deleted elsewhere: dropped; 400 = FAILED with its dependents; 401/403/429 and
 * the circuit breaker stop the drain; offline/5xx retry). `VIEW_LOG` and `COOK_LOG` keep their own
 * code. A handler only makes the request and maps the answer; it may send a whole batch (rows with
 * the same `batch_key`, `OutboxDao.nextReadyBatch`) and settle the other rows itself.
 */
internal interface OutboxHandler {
    /**
     * Sends [row] with [payload] (its payload with every temporary id already replaced by the
     * server id). Called on a background dispatcher, through `RemoteCaller` like every request.
     */
    suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult
}

/** What became of one outbox row, see [OutboxHandler]. */
internal sealed interface OutboxResult {
    /** The server took the change: the row is removed. */
    data object Sent : OutboxResult

    /** A CREATE was answered with [serverId]: the id is rewritten everywhere and the row removed (one transaction). */
    data class Created(val serverId: Long) : OutboxResult

    /** Nothing to send any more (e.g. the object is gone locally): the row is removed without a request. */
    data object Dropped : OutboxResult

    /** The request failed with [error]; [OutboxSender] applies the shared error rules. */
    data class Failed(val error: TandemError) : OutboxResult
}
