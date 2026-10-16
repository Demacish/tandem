package casa.moller.tandem.core.model.sync

import casa.moller.tandem.core.model.error.TandemError

/**
 * Internal markers for a sync run that stopped before it finished, without a failure the user
 * should see (plan 5.4). They arrive as [TandemError.Unknown] with one of these codes in
 * [SyncStatus.lastError] or [SyncProgress.error]; screens ask [isTransientSyncStop] instead of
 * knowing the codes (feature modules never depend on core:sync).
 */
object SyncStop {
    /**
     * The run was cancelled: WorkManager stopped the worker, or pull to refresh replaced a running
     * sync with a new one (`ExistingWorkPolicy.REPLACE`).
     */
    const val CANCELLED: String = "tandem.sync.cancelled"

    /** The active account changed (logout, account switch) while the run was busy. */
    const val SOURCE_CHANGED: String = "tandem.sync.source-changed"

    /** [CANCELLED] as an error value. */
    val Cancelled: TandemError = TandemError.Unknown(CANCELLED)

    /** [SOURCE_CHANGED] as an error value. */
    val SourceChanged: TandemError = TandemError.Unknown(SOURCE_CHANGED)
}

/**
 * True for a sync run that only stopped ([SyncStop]): cancelled or overtaken by an account
 * change. Not a failure: nothing to show, the next run tries again.
 */
val TandemError.isTransientSyncStop: Boolean
    get() = this is TandemError.Unknown && (detail == SyncStop.CANCELLED || detail == SyncStop.SOURCE_CHANGED)
