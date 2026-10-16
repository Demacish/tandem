package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import casa.moller.tandem.core.model.settings.SyncInterval
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
 * - `tandem.sync.now` ([SyncWorker]): expedited when the quota allows, else normal; an automatic
 *   request while one is waiting or running is dropped (KEEP). Pull to refresh
 *   ([SyncReason.USER_REFRESH]) replaces it instead (REPLACE): an app start run that skips the
 *   hourly recipe index must not swallow the user's explicit refresh, which always reads it.
 * - `tandem.sync.periodic` ([PeriodicSyncWorker]): every 15 minutes (or the interval chosen under
 *   Settings → Sync: 1 h, 6 h, none) with any network and a
 *   battery that is not low; no exact alarms and no foreground service, which keeps it friendly
 *   to Samsung's battery management (plan 4.4, question 13). The first run comes one period
 *   after it is scheduled (a login): the onboarding's FIRST_SYNC makes the first full sync, and a
 *   periodic run at once would do it all twice. UPDATE keeps an existing schedule's timing, so
 *   later app starts neither delay nor hurry it.
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
        workManager.enqueueUniqueWork(SyncSchedule.WORK_ONE_TIME, policyFor(reason), request)
    }

    override fun scheduleOutbox() {
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncSchedule.OUTBOX_BACKOFF.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .addTag(SyncSchedule.TAG_OUTBOX)
            .build()
        workManager.enqueueUniqueWork(SyncSchedule.WORK_OUTBOX, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodic(interval: SyncInterval) {
        // MANUAL has no period; the starter cancels instead (see SyncScheduler).
        val minutes = interval.minutes ?: return cancelPeriodic()
        workManager.enqueueUniquePeriodicWork(SyncSchedule.WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(minutes))
    }

    override fun cancelPeriodic() {
        workManager.cancelUniqueWork(SyncSchedule.WORK_PERIODIC)
    }

    internal companion object {
        /**
         * The periodic sync's request (see the class comment: the first run one period later).
         * Phase 1b: the period is the "Sync → Interval" setting; UPDATE gives an existing schedule
         * the new period without starting over.
         */
        fun periodicRequest(periodMinutes: Int = SyncSchedule.PERIODIC.inWholeMinutes.toInt()): PeriodicWorkRequest {
            val minutes = periodMinutes.toLong().coerceAtLeast(SyncSchedule.PERIODIC.inWholeMinutes)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            return PeriodicWorkRequestBuilder<PeriodicSyncWorker>(minutes, TimeUnit.MINUTES)
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(SyncSchedule.TAG_SYNC)
                .build()
        }

        /** See the class comment: only the user's own refresh replaces a waiting or running sync. */
        fun policyFor(reason: SyncReason): ExistingWorkPolicy =
            if (reason == SyncReason.USER_REFRESH) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
    }
}
