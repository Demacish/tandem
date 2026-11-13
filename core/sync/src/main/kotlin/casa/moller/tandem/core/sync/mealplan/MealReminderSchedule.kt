package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.domain.mealplan.MealReminders
import casa.moller.tandem.core.domain.reminder.WallClockSchedule
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.WallClock
import casa.moller.tandem.core.sync.reminder.DailyReminderKind
import casa.moller.tandem.core.sync.reminder.DailyReminderScheduler
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan": the evening reminder exists only
 * while Settings → "Påminnelse kvällen innan" is on AND a real account is active (started by
 * `SyncStarter`), at the chosen time (`AppSettings.mealReminderTime`); off, logging out or demo mode
 * cancel it and switching it off removes the reminders already posted.
 *
 * While the app process lives (it does whenever a sync runs, also in the background) the evening
 * is watched: from the chosen time until midnight every change of tomorrow's meals (made here, on
 * the web and brought by the sync, or a meal moved or removed) runs the check again, so a reminder
 * comes for a meal planned after the evening's run and goes away when its meal moves or is
 * removed. The check itself keeps "once per meal and evening".
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
internal class MealReminderSchedule @Inject constructor(
    private val settings: SettingsStore,
    private val sources: ActiveSources,
    private val scheduler: DailyReminderScheduler,
    private val check: MealReminderCheck,
    private val mealPlans: MealPlanRepository,
    private val clock: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val remote = sources.source.filter { it != ActiveSource.Loading }.map { it is ActiveSource.Remote }.distinctUntilChanged()
            val wanted = settings.settings.map { s -> if (s.mealReminders) s.mealReminderTime else null }.distinctUntilChanged()
            var wasOn = false
            combine(remote, wanted) { account, time -> time.takeIf { account } }
                .distinctUntilChanged()
                .collectLatest { time ->
                    if (time == null) {
                        scheduler.cancel(DailyReminderKind.MEAL)
                        if (wasOn) check.withdrawAll()
                        wasOn = false
                        return@collectLatest
                    }
                    wasOn = true
                    scheduler.ensure(DailyReminderKind.MEAL, time)
                    watchEvenings(time)
                }
        }
    }

    /** Every evening from [time] until midnight: tomorrow's reminders follow the local plan. */
    private suspend fun watchEvenings(time: LocalTime) {
        while (true) {
            val now = clock.now()
            val zone = clock.zone()
            val today = WallClockSchedule.today(now, zone)
            val start = WallClockSchedule.occurrence(today, time, zone)
            if (now < start) {
                delay(start - now)
                continue
            }
            val tomorrow = today.plus(1, DateTimeUnit.DAY)
            val midnight = WallClockSchedule.occurrence(tomorrow, LocalTime(0, 0), zone)
            withTimeoutOrNull(midnight - now) {
                mealPlans.observeWeek(MealPlanWeek.startOf(tomorrow))
                    .map { week -> MealReminders.due(week.days.flatMap { it.meals }, tomorrow) }
                    .distinctUntilChanged()
                    .debounce(SETTLE.milliseconds)
                    .collect { check.run(today, onTime = false, refresh = false) }
            }
            // Past midnight (or the zone moved the evening): wait for the next one.
            val after = clock.now()
            if (WallClockSchedule.today(after, clock.zone()) == today) delay(MIDNIGHT_SLACK.milliseconds)
        }
    }

    private companion object {
        /** Several changes in a row (a sync writing the plan) give one check. */
        const val SETTLE = 2_000L

        /** A clock that reached "midnight" a little early must not watch the same evening twice in a row. */
        const val MIDNIGHT_SLACK = 60_000L
    }
}
