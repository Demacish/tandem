package casa.moller.tandem.wear.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import casa.moller.tandem.wear.TandemWearApplication
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** When the watch syncs: right away, or once the watch has network. */
interface SyncScheduler {
    /** Sends the outbox (and reads the list when it is old, or always with [force]) as soon as there is network. */
    fun syncNow(force: Boolean = false)

    /** The same after [delayMillis] (the guard's pause). */
    fun syncLater(delayMillis: Long)
}

/**
 * [SyncScheduler] on WorkManager: one unique job with a network constraint, so a check made
 * offline is sent by itself when the watch is online again (Wi-Fi, LTE or through the phone),
 * also after the app was closed or the watch restarted. Nothing runs periodically: the list is read
 * when the app opens, when the tile is shown and after the phone paired the watch.
 */
class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    // A pairing (force) replaces whatever waits; a tap keeps a job that is already planned or
    // running, but replaces one that waits out a retry (0.8.0 review: a check made when the server is
    // back must not wait for WorkManager's backoff). The look at the job never blocks the caller.
    override fun syncNow(force: Boolean) {
        if (force) return enqueue(WORK_NAME, 0L, true, ExistingWorkPolicy.REPLACE)
        val manager = WorkManager.getInstance(context)
        val infos = manager.getWorkInfosForUniqueWork(WORK_NAME)
        infos.addListener(
            {
                val existing = runCatching { infos.get() }.getOrDefault(emptyList()).map { it.state to it.runAttemptCount }
                enqueue(WORK_NAME, 0L, false, WearSyncRetry.policyForNow(existing))
            },
            // Directly on the thread that finished the look: enqueueing does not block.
            Executor { it.run() },
        )
    }

    // Its own name, so planning it from inside the running job never cancels that job.
    override fun syncLater(delayMillis: Long) = enqueue(LATER_WORK_NAME, delayMillis.coerceAtLeast(0L), false, ExistingWorkPolicy.REPLACE)

    private fun enqueue(name: String, delayMillis: Long, force: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<WearSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            // An explicit, short backoff; the worker stops retrying after a few tries (WearSyncRetry).
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WearSyncRetry.BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(WearSyncWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    companion object {
        const val WORK_NAME: String = "tandem.wear.sync"
        const val LATER_WORK_NAME: String = "tandem.wear.sync.later"
    }
}

/**
 * When the watch tries again after it could not reach the server (0.8.0 review). WorkManager's own
 * backoff doubles up to five hours, so a check could wait hours after the server was back:
 *
 * - the worker retries with an explicit backoff of [BACKOFF_MILLIS] (30 s, 1, 2, 4 min) only
 *   [MAX_RETRIES] times, then ends and plans one more try after [LATER_MILLIS] (15 min), and so on:
 *   a waiting check is tried at least every 15 minutes while the watch has network;
 * - a tap, opening the app or the tile replaces a job that waits out a retry ([policyForNow]), so
 *   a user action always starts a fresh attempt; a planned or running job is kept (it sends every
 *   check made meanwhile). Reads stay limited by `WearSync`'s minimum interval and the guard.
 */
object WearSyncRetry {
    const val BACKOFF_MILLIS: Long = 30_000L
    const val MAX_RETRIES: Int = 4
    const val LATER_MILLIS: Long = 15 * 60_000L

    /** Retry through WorkManager (true) or end and plan [LATER_MILLIS] (false) after [runAttemptCount] tries. */
    fun retries(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES

    /** The policy for a non-forced "sync now", given the unique job's (state, run attempts). */
    fun policyForNow(existing: List<Pair<WorkInfo.State, Int>>): ExistingWorkPolicy =
        if (existing.any { (state, attempts) -> state == WorkInfo.State.ENQUEUED && attempts > 0 }) {
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
}

/**
 * One sync ([WearSync.sync]); a check made while it ran is sent in the same job. Offline asks
 * WorkManager to try again (it waits for the network) a few times, then plans the next try in
 * [WearSyncRetry.LATER_MILLIS]; a pause plans the next try after it.
 */
class WearSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as? TandemWearApplication)?.graph ?: return Result.success()
        var force = inputData.getBoolean(KEY_FORCE, false)
        repeat(MAX_ROUNDS) {
            val result = graph.sync.sync(force)
            force = false
            graph.refreshTile()
            when (result) {
                WearSync.Result.Offline -> {
                    if (WearSyncRetry.retries(runAttemptCount)) return Result.retry()
                    graph.scheduler.syncLater(WearSyncRetry.LATER_MILLIS)
                    return Result.success()
                }

                is WearSync.Result.Paused -> {
                    graph.scheduler.syncLater(result.untilMillis - graph.clock())
                    return Result.success()
                }

                WearSync.Result.Done, WearSync.Result.Fresh ->
                    if (graph.store.load().pending.isEmpty()) return Result.success()

                WearSync.Result.NotPaired, WearSync.Result.SignedOut -> return Result.success()
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_FORCE: String = "force"
        private const val MAX_ROUNDS = 3
    }
}
