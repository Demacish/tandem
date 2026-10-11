package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.engine.OutboxSender
import casa.moller.tandem.core.sync.engine.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One sync run (`tandem.sync.now`): app start, resume, pull to refresh, account change. The
 * reason travels in the input data ([KEY_REASON]); the engine applies its throttles.
 */
@HiltWorker
internal class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON)
            ?.let { name -> SyncReason.entries.firstOrNull { it.name == name } }
            ?: SyncReason.APP_RESUMED
        engine.run(reason)
        // A failed run is not retried here: the periodic sync and the next start try again, so a
        // broken server never sees a retry loop (plan 4.1).
        return Result.success()
    }

    companion object {
        const val KEY_REASON: String = "reason"
    }
}

/** The periodic background sync (`tandem.sync.periodic`, every 15 minutes with a network, plan 3.5). */
@HiltWorker
internal class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        engine.run(SyncReason.PERIODIC)
        return Result.success()
    }
}

/**
 * Sends the outbox (`tandem.sync.outbox`, plan 3.5 "SyncOutboxWorker"): with a network
 * constraint and exponential backoff from 30 seconds while sending fails temporarily.
 */
@HiltWorker
internal class SyncOutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sources: ActiveSources,
    private val outbox: OutboxSender,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val remote = sources.current() as? ActiveSource.Remote ?: return Result.success()
        return when (outbox.drain(remote)) {
            DrainResult.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            DrainResult.DONE, DrainResult.STOPPED, DrainResult.PAUSED -> Result.success()
        }
    }

    private companion object {
        /** After this many attempts the periodic sync takes over (its drain sends what is left). */
        const val MAX_ATTEMPTS = 8
    }
}
