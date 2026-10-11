package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.model.sync.SyncReason

/**
 * Hands sync work to WorkManager (implemented by `WorkManagerSyncScheduler`; tests record the
 * calls). Every request is unique work, so asking twice never runs two syncs.
 */
internal interface SyncScheduler {
    /** A one-off sync as soon as there is a network (expedited when the quota allows). */
    fun requestSync(reason: SyncReason)

    /** Sends the outbox when there is a network, with exponential backoff on failures. */
    fun scheduleOutbox()

    /** The periodic background sync every 15 minutes (plan 3.5), for a logged-in account. */
    fun schedulePeriodic()

    /** No account (logged out or demo): no background sync. Queued rows stay in the database. */
    fun cancelPeriodic()
}
