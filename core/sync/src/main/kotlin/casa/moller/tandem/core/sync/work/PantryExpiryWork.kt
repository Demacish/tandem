package casa.moller.tandem.core.sync.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.domain.pantry.ExpiryReminder
import casa.moller.tandem.core.domain.reminder.DailyRun
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.pantry.PantryExpiryCheck
import casa.moller.tandem.core.sync.pantry.PantryExpiryOutcome
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

// Fas 4 (skafferinotis lane), plan §8.8 "Använd upp" / §8.7 "Måltidspåminnelser": the once-a-day
// expiry check (docs/BYGGE.md "Notis när något går ut"). Fas 5 (planera lane): at the time the user
// chooses, planned by the daily reminders' scheduler (docs/BYGGE.md "Påminnelser kvällen innan och
// på morgonen").

/**
 * The morning check: [PantryExpiryCheck] decides everything (setting, account, guest, demo, quiet
 * hours, permission, "never twice the same day"). The worker class keeps Fas 4's name (WorkManager
 * stores it, and the release smoke test starts it by name).
 */
@HiltWorker
internal class PantryExpiryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    job: PantryExpiryJob,
    scheduler: DailyReminderScheduler,
    clock: WallClock,
) : DailyReminderWorker(context, params, job, scheduler, clock)

/**
 * The morning check as a daily reminder: wanted while Settings → "Notis när något går ut" is on and
 * a real account is active, at `AppSettings.pantryExpiryTime`; a run that found Tandem on screen is
 * tried again two hours later the same day ([ExpiryReminder.retryRun]).
 */
@Singleton
internal class PantryExpiryJob @Inject constructor(
    private val check: PantryExpiryCheck,
    private val settings: SettingsStore,
    private val sources: ActiveSources,
    private val clock: WallClock,
) : DailyReminderJob {
    override val kind: DailyReminderKind = DailyReminderKind.PANTRY_EXPIRY

    override suspend fun time(): LocalTime? {
        val current = settings.settings.first()
        if (!current.pantryExpiryNotifications || sources.current() !is ActiveSource.Remote) return null
        return current.pantryExpiryTime
    }

    override suspend fun run(run: DailyRun?): DailyRun? {
        val outcome = check.run()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "expiry check: $outcome")
        if (outcome != PantryExpiryOutcome.VISIBLE) return null
        return ExpiryReminder.retryRun(clock.now(), clock.zone(), settings.settings.first().pantryExpiryTime)
    }

    private companion object {
        const val TAG = "TandemPantryExpiry"
    }
}
