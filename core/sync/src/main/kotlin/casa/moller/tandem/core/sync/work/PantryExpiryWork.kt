package casa.moller.tandem.core.sync.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import casa.moller.tandem.core.domain.pantry.ExpiryReminder
import casa.moller.tandem.core.sync.pantry.PantryExpiryCheck
import casa.moller.tandem.core.sync.pantry.PantryExpiryOutcome
import casa.moller.tandem.core.sync.platform.WallClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

// Fas 4 (skafferinotis lane), plan §8.8 "Använd upp" / §8.7 "Måltidspåminnelser": the once-a-day
// expiry check (docs/BYGGE.md "Notis när något går ut").

/**
 * The morning check (`tandem.pantry.expiry`): [PantryExpiryCheck] decides everything (setting,
 * account, guest, demo, quiet hours, permission, "never twice the same day"). Always a success:
 * nothing here is worth a WorkManager retry. Fas 4 review (fix-data): whatever the outcome, every
 * run sets the time of the next one ([PantryExpiryScheduler.reschedule]).
 */
@HiltWorker
internal class PantryExpiryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val check: PantryExpiryCheck,
    private val scheduler: PantryExpiryScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var outcome: PantryExpiryOutcome? = null
        try {
            outcome = check.run()
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "expiry check: $outcome")
        } finally {
            scheduler.reschedule(id, retrySoon = outcome == PantryExpiryOutcome.VISIBLE)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "TandemPantryExpiry"
    }
}

/** Hands the morning check to WorkManager (tests record the calls). */
internal interface PantryExpiryScheduler {
    /** Once a day around [ExpiryReminder.MORNING_HOUR]; an existing schedule keeps its timing. */
    fun schedule()

    /** The setting is off, logged out or demo mode: no check at all. */
    fun cancel()

    /** Run [workId] just ended: the next one is at the next morning, or soon with [retrySoon] ([ExpiryReminder.delayUntilNextRun]). */
    fun reschedule(workId: UUID, retrySoon: Boolean)
}

/**
 * [PantryExpiryScheduler] on WorkManager: unique periodic work with a battery that is not low and
 * NO network constraint (the check works offline from the local pantry), first run at the next
 * 08:00 of the device's zone. KEEP: app starts never move it.
 *
 * Fas 4 review (fix-data): a periodic run is due a period after the last one FINISHED, so every
 * late start (Doze, a low battery) would move all later ones, and a run deferred into the night
 * would keep landing in the quiet hours. Each run therefore sets the next one itself with
 * `updateWork` and `setNextScheduleTimeOverride`, which is also how the schedule follows a DST
 * change or a new zone. The 24-hour period only applies if a run never gets that far. Work
 * cancelled during a run stays cancelled (`updateWork` does not bring it back, a failed future is
 * ignored; so does the smoke test's one-time run of the same worker).
 */
@Singleton
internal class WorkManagerPantryExpiryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: WallClock,
) : PantryExpiryScheduler {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun schedule() {
        val delay = ExpiryReminder.delayUntilNext(clock.now(), clock.zone())
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request(delay))
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    override fun reschedule(workId: UUID, retrySoon: Boolean) {
        val now = clock.now()
        val next = now + ExpiryReminder.delayUntilNextRun(now, clock.zone(), retrySoon)
        runCatching { workManager.updateWork(nextRunRequest(workId, next.toEpochMilliseconds())) }
    }

    internal companion object {
        const val WORK_NAME: String = "tandem.pantry.expiry"
        const val TAG: String = "tandem.pantry"
        val PERIOD: Duration = 24.hours

        /** The periodic request, first run after [initialDelay]. */
        fun request(initialDelay: Duration): PeriodicWorkRequest = builder()
            .setInitialDelay(initialDelay.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()

        /** The same work [workId] with its next run at [nextRunMillis]. */
        fun nextRunRequest(workId: UUID, nextRunMillis: Long): PeriodicWorkRequest = builder()
            .setId(workId)
            .setNextScheduleTimeOverride(nextRunMillis)
            .build()

        private fun builder(): PeriodicWorkRequest.Builder {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            return PeriodicWorkRequestBuilder<PantryExpiryWorker>(PERIOD.inWholeMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(TAG)
        }
    }
}
