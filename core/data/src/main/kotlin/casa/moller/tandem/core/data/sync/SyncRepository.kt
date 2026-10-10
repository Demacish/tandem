package casa.moller.tandem.core.data.sync

import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The sync as the UI may see and start it (plan 3.5, 4.1). The work itself runs in core:sync
 * (SyncManager, WorkManager workers); features only use this interface.
 *
 * - **Threading:** main-safe. [requestSync] returns at once; the work runs in the background and
 *   survives the screen that asked for it.
 * - **Protection (plan 4.1):** implementations throttle per [SyncReason] (the recipe index at
 *   most once per hour on start), pause on 401/JSON-403/blocked/circuit breaker/safe mode
 *   ([SyncStatus.pause]) and never discard queued changes.
 */
interface SyncRepository {

    /** The current sync state; starts as [SyncStatus.Idle]. */
    val status: StateFlow<SyncStatus>

    /**
     * Asks for a sync. Ignored (but not an error) when one is running, when the reason is
     * throttled, or in demo mode. [SyncReason.USER_REFRESH] is never throttled.
     */
    fun requestSync(reason: SyncReason)

    /**
     * Runs the first sync after a login (plan 6.2 "Efter inloggning": "Fetching 312 recipes…")
     * and streams its progress. The flow completes after a [SyncProgress] whose phase is DONE or
     * FAILED (with the error); it never throws for network errors. Collecting it again after a
     * failure starts a new attempt. Cancelling the collector does not cancel the sync: it goes
     * on in the background and shows up in [status].
     */
    fun firstSync(): Flow<SyncProgress>
}
