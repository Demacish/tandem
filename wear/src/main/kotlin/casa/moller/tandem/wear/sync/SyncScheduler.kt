package casa.moller.tandem.wear.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import casa.moller.tandem.wear.TandemWearApplication
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
    // A pairing (force) replaces whatever waits; a tap keeps a job that is already planned.
    override fun syncNow(force: Boolean) = enqueue(WORK_NAME, 0L, force, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)

    // Its own name, so planning it from inside the running job never cancels that job.
    override fun syncLater(delayMillis: Long) = enqueue(LATER_WORK_NAME, delayMillis.coerceAtLeast(0L), false, ExistingWorkPolicy.REPLACE)

    private fun enqueue(name: String, delayMillis: Long, force: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<WearSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
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
 * One sync ([WearSync.sync]); a check made while it ran is sent in the same job. Offline asks
 * WorkManager to try again (it waits for the network); a pause plans the next try after it.
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
                WearSync.Result.Offline -> return Result.retry()

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
