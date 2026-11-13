package casa.moller.tandem.core.sync.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.domain.reminder.DailyRun
import casa.moller.tandem.core.domain.reminder.WallClockSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.mealplan.MealReminderCheck
import casa.moller.tandem.core.sync.platform.WallClock
import casa.moller.tandem.core.sync.reminder.DailyReminderJob
import casa.moller.tandem.core.sync.reminder.DailyReminderKind
import casa.moller.tandem.core.sync.reminder.DailyReminderScheduler
import casa.moller.tandem.core.sync.reminder.DailyReminderWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalTime

// Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan": the evening run (docs/BYGGE.md
// "Påminnelser kvällen innan och på morgonen").

/** The evening reminder "Tina ut kycklingen" ([MealReminderCheck]), planned by the daily reminders' scheduler. */
@HiltWorker
internal class MealReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    job: MealReminderJob,
    scheduler: DailyReminderScheduler,
    clock: WallClock,
) : DailyReminderWorker(context, params, job, scheduler, clock)

/**
 * The evening reminder as a daily reminder: wanted while Settings → "Påminnelse kvällen innan" is
 * on and a real account is active, at `AppSettings.mealReminderTime`. A planned run is for its own
 * evening (a run deferred past midnight posts nothing); a run started by hand is for this evening,
 * whatever the time.
 */
@Singleton
internal class MealReminderJob @Inject constructor(
    private val check: MealReminderCheck,
    private val settings: SettingsStore,
    private val sources: ActiveSources,
    private val clock: WallClock,
) : DailyReminderJob {
    override val kind: DailyReminderKind = DailyReminderKind.MEAL

    override suspend fun time(): LocalTime? {
        val current = settings.settings.first()
        if (!current.mealReminders || sources.current() !is ActiveSource.Remote) return null
        return current.mealReminderTime
    }

    override suspend fun run(run: DailyRun?): DailyRun? {
        val evening = run?.date ?: WallClockSchedule.today(clock.now(), clock.zone())
        val outcome = check.run(evening)
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "meal reminders for $evening: $outcome")
        return null
    }

    private companion object {
        const val TAG = "TandemMealReminder"
    }
}
