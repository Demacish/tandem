package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.platform.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncScheduler] on WorkManager. Every request is unique work, so it never runs twice:
 *
 * - `tandem.sync.now` ([SyncWorker]): expedited when the quota allows, else normal; a request
 *   while one is waiting or running is dropped (KEEP).
 * - `tandem.sync.periodic` ([PeriodicSyncWorker]): every 15 minutes with any network and a
 *   battery that is not low; no exact alarms and no foreground service, which keeps it friendly
 *   to Samsung's battery management (plan 4.4, question 13).
 * - `tandem.sync.outbox` ([SyncOutboxWorker]): appended after a running drain, exponential
 *   backoff from [SyncSchedule.OUTBOX_BACKOFF].
 *
 * All of them are tagged [SyncSchedule.TAG_SYNC] (outbox: [SyncSchedule.TAG_OUTBOX]).
 */
@Singleton
internal class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncScheduler {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    private val networkConstraint: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSync(reason: SyncReason) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(SyncWorker.KEY_REASON to reason.name))
            .addTag(SyncSchedule.TAG_SYNC)
            .build()
        workManager.enqueueUniqueWork(SyncSchedule.WORK_ONE_TIME, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleOutbox() {
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncSchedule.OUTBOX_BACKOFF.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .addTag(SyncSchedule.TAG_OUTBOX)
            .build()
        workManager.enqueueUniqueWork(SyncSchedule.WORK_OUTBOX, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(SyncSchedule.PERIODIC.inWholeMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(SyncSchedule.TAG_SYNC)
            .build()
        workManager.enqueueUniquePeriodicWork(SyncSchedule.WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancelPeriodic() {
        workManager.cancelUniqueWork(SyncSchedule.WORK_PERIODIC)
    }
}
