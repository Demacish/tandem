package casa.moller.tandem.core.data.sync

import casa.moller.tandem.core.model.sync.ForegroundSyncState
import casa.moller.tandem.core.model.sync.ForegroundTarget
import kotlinx.coroutines.flow.Flow

/**
 * The sync hook for visible screens (plan 3.5, 8.6, Phase 2 "Klart när": two phones see each
 * other's checks within 30 s while the list is open). Collect [keepFresh] while the screen is
 * STARTED (`repeatOnLifecycle`/`collectAsStateWithLifecycle`); cancelling the collection stops
 * polling. Implemented in core:sync (until then a temporary binding in :app).
 *
 * - Shopping list: a delta (`updated_after` with the server's last `timestamp`, URL-encoded: an
 *   unencoded `+` makes Tandoor return the whole list, FACTS "Fas 2") every
 *   `ForegroundPolling.intervalFor(shopping_min_autosync_interval)`, a full re-read every 10th time
 *   with the window rule (core:domain `ShoppingWindowRule`); the outbox is sent first.
 * - Meal plan: the window −14..+42 days, at most every 60 s while visible.
 * - Never in demo mode, never while the sync guard pauses (offline, 401/403, 429, circuit breaker,
 *   safe mode reads but does not send). Two collectors of the same target share one poller.
 */
interface ForegroundSync {
    fun keepFresh(target: ForegroundTarget): Flow<ForegroundSyncState>
}
