package casa.moller.tandem.core.sync.calendar

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.datastore.MealCalendarStore
import casa.moller.tandem.core.domain.mealplan.MealCalendarEvents
import casa.moller.tandem.core.domain.reminder.WallClockSchedule
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.platform.WallClock
import casa.moller.tandem.core.sync.work.MealCalendarWork
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

/**
 * Fas 5 (kalender lane, plan §7 #36): keeps "Matsedeln i kalendern" in step, started by `SyncStarter`.
 *
 * - With a real account and the local calendar or the link on, the periodic [MealCalendarWork] runs
 *   (twice a day: the plan is read and the calendar written, the link renewed); otherwise it is cancelled.
 * - While the process lives and the local calendar is on, every change of the stored plan in the
 *   window (made here, or on the web and brought by the sync; a moved or removed meal) writes the
 *   calendar again after [SETTLE]; a switch to another account writes that account's plan instead.
 * - Logging out (no account) or demo mode removes the calendar and turns the option off, so another
 *   person logging in on this phone never finds it on.
 */
@OptIn(FlowPreview::class)
@Singleton
internal class MealCalendarSchedule @Inject constructor(
    private val store: MealCalendarStore,
    private val sources: ActiveSources,
    private val links: MealCalendarLinks,
    private val calendar: PhoneMealCalendar,
    private val work: MealCalendarWork,
    private val mealPlans: MealPlanRepository,
    private val clock: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    private data class Wanted(val source: ActiveSource, val local: Boolean, val link: Boolean)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val source = sources.source.filter { it != ActiveSource.Loading }.distinctUntilChangedBy { it.identity }
            val local = store.state.map { it.enabled }.distinctUntilChanged()
            val link = links.observe().map { it.isOn }.distinctUntilChanged()
            combine(source, local, link) { s, l, k -> Wanted(s, l, k) }
                .distinctUntilChanged()
                .collectLatest { wanted ->
                    when {
                        wanted.source !is ActiveSource.Remote -> {
                            work.cancel()
                            if (wanted.local) {
                                calendar.remove()
                                store.setEnabled(false)
                            }
                        }

                        !wanted.local && !wanted.link -> work.cancel()

                        else -> {
                            work.ensure()
                            if (wanted.local) followThePlan()
                        }
                    }
                }
        }
    }

    /** Writes the calendar now and after every change of the stored plan in today's window. */
    private suspend fun followThePlan() {
        val window = MealCalendarEvents.window(WallClockSchedule.today(clock.now(), clock.zone()))
        val weeks = generateSequence(MealPlanWeek.startOf(window.start)) { it.plus(MealPlanWeek.DAYS, DateTimeUnit.DAY) }
            .takeWhile { it <= window.endInclusive }
            .map { start -> mealPlans.observeWeek(start).map { week -> week.days.flatMap { it.meals } } }
            .toList()
        combine(weeks) { all -> all.flatMap { it }.distinctBy { it.id } }
            .distinctUntilChanged()
            .debounce(SETTLE)
            .collect { calendar.write() }
    }

    private companion object {
        /** Several changes in a row (a sync writing the plan) give one write. */
        val SETTLE = 2_000.milliseconds
    }
}
