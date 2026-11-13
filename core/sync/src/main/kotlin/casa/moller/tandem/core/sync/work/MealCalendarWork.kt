package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.datastore.MealCalendarStore
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.calendar.MealCalendarLinks
import casa.moller.tandem.core.sync.calendar.PhoneMealCalendar
import casa.moller.tandem.core.sync.platform.NetworkStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Fas 5 (kalender lane, plan §7 #36): "Matsedeln i kalendern" kept fresh in the background
// (docs/BYGGE.md "Matsedeln i kalendern").

/**
 * Twice a day while "Visa i telefonens kalender" or the subscription link is on for a real account
 * ([MealCalendarWork.ensure]): reads the plan when online and writes the local calendar, renews the
 * link's token. The class name is stored by WorkManager (kept by consumer-rules.pro).
 */
@HiltWorker
internal class MealCalendarWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val job: MealCalendarJob,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        job.run()
        return Result.success()
    }
}

/** One background run of [MealCalendarWorker] (tests call it directly). */
@Singleton
internal class MealCalendarJob @Inject constructor(
    private val calendar: PhoneMealCalendar,
    private val links: MealCalendarLinks,
    private val store: MealCalendarStore,
    private val sources: ActiveSources,
    private val mealPlans: MealPlanRepository,
    private val network: NetworkStatus,
) {
    suspend fun run() {
        if (sources.current() !is ActiveSource.Remote) return
        val local = store.state.first().enabled
        if (network.online.value) {
            // Both through RemoteCaller (circuit breaker, rate limits); a failure waits for the next run.
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { links.renewIfDue() }
            if (local) withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { mealPlans.refresh() }
        }
        if (local) calendar.write()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 60_000L
    }
}

/** Plans [MealCalendarWorker]. */
internal interface MealCalendarWork {
    /** Keeps the periodic work (an existing plan stays as it is). */
    fun ensure()

    fun cancel()
}

internal class WorkManagerMealCalendarWork @Inject constructor(@ApplicationContext private val context: Context) : MealCalendarWork {
    override fun ensure() {
        val request = PeriodicWorkRequestBuilder<MealCalendarWorker>(PERIOD_HOURS, TimeUnit.HOURS).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    companion object {
        const val NAME: String = "tandem.meal_calendar"
        const val TAG: String = "tandem.meal_calendar"

        /** Twice a day: the calendar is never more than a day behind, even when a run is deferred. */
        const val PERIOD_HOURS: Long = 12
    }
}
