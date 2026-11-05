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
import casa.moller.tandem.core.sync.platform.WallClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * nothing here is worth a retry, the next morning's run comes anyway.
 */
@HiltWorker
internal class PantryExpiryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val check: PantryExpiryCheck,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = check.run()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "expiry check: $outcome")
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
}

/**
 * [PantryExpiryScheduler] on WorkManager: unique periodic work every 24 hours with a battery that is
 * not low and NO network constraint (the check works offline from the local pantry), first run at
 * the next 08:00 of the device's zone. KEEP: app starts never move it. The period is plain 24
 * hours, so after a DST change it may run at 07:00 or 09:00 until it is scheduled again (switched
 * off and on); the quiet hours keep it out of the night.
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

    internal companion object {
        const val WORK_NAME: String = "tandem.pantry.expiry"
        const val TAG: String = "tandem.pantry"
        val PERIOD: Duration = 24.hours

        /** The periodic request, first run after [initialDelay]. */
        fun request(initialDelay: Duration): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            return PeriodicWorkRequestBuilder<PantryExpiryWorker>(PERIOD.inWholeMinutes, TimeUnit.MINUTES)
                .setInitialDelay(initialDelay.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
        }
    }
}
