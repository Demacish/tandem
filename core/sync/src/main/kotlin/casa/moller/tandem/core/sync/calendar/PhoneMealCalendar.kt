package casa.moller.tandem.core.sync.calendar

import android.content.OperationApplicationException
import android.database.SQLException
import android.os.RemoteException
import android.util.Log
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.datastore.MealCalendarStore
import casa.moller.tandem.core.domain.mealplan.MealCalendarEvents
import casa.moller.tandem.core.domain.reminder.WallClockSchedule
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.LocalMealCalendarProblem
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.WallClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Fas 5 (kalender lane, plan §7 #36), option b: the stored meal plan of the active account in the
 * local calendar "Tandem – matsedel" ([CalendarGateway]), written from Room (offline) for the sync's
 * plan window ([MealCalendarEvents.window]): one event per meal, all-day unless the meal type has a
 * time. Every [write] brings the calendar exactly in step ([MealCalendarEvents.changes]): no
 * duplicates, a moved meal moves, a removed meal disappears; running it twice changes nothing.
 * [remove] deletes the calendar and every event in it. Serialized: two writes never interleave.
 */
@Singleton
internal class PhoneMealCalendar @Inject constructor(
    private val gateway: CalendarGateway,
    private val sources: ActiveSources,
    private val mealPlans: MealPlanRepository,
    private val store: MealCalendarStore,
    private val clock: WallClock,
) {
    private val mutex = Mutex()

    /**
     * Writes the calendar when the option is on and a real account is active (guests too: it only
     * reads what is stored); the number of meals it holds. Off, demo mode or no account: nothing is
     * written (the caller removes the calendar). A missing permission or a refusing provider is
     * recorded for the screen.
     */
    suspend fun write(): Outcome<Int> = mutex.withLock {
        if (!store.state.first().enabled) return@withLock Outcome.Success(0)
        val source = sources.current() as? ActiveSource.Remote ?: return@withLock Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        if (!gateway.hasPermission()) return@withLock failed(LocalMealCalendarProblem.PERMISSION)
        val zone = clock.zone()
        val today = WallClockSchedule.today(clock.now(), zone)
        val window = MealCalendarEvents.window(today)
        val wanted = MealCalendarEvents.of(source.accountId, mealsIn(window), window, zone)
        val written = onProvider {
            val calendar = gateway.calendarId(create = true) ?: return@onProvider null
            gateway.apply(calendar, MealCalendarEvents.changes(wanted, gateway.events(calendar)))
            wanted.size
        }
        when (written) {
            null -> failed(LocalMealCalendarProblem.PROVIDER)

            is ProviderResult.Refused -> failed(written.problem)

            is ProviderResult.Done -> {
                store.recordWrite(atMillis = clock.now().toEpochMilliseconds(), events = written.value, problem = null)
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "meal calendar written: ${written.value} meals")
                Outcome.Success(written.value)
            }
        }
    }

    /** Deletes Tandem's calendar with all its events (turned off, logged out, demo mode); true when one was there. */
    suspend fun remove(): Boolean = mutex.withLock {
        if (!gateway.hasPermission()) return@withLock false
        when (val removed = onProvider { gateway.removeCalendar() }) {
            is ProviderResult.Done -> removed.value
            else -> false
        }
    }

    /** True when the calendar permissions are granted. */
    fun hasPermission(): Boolean = gateway.hasPermission()

    /** The stored meals of the weeks that cover [window] (a meal over several days once). */
    private suspend fun mealsIn(window: ClosedRange<LocalDate>): List<MealPlanEntry> {
        val weeks = generateSequence(MealPlanWeek.startOf(window.start)) { it.plus(MealPlanWeek.DAYS, DateTimeUnit.DAY) }
            .takeWhile { it <= window.endInclusive }
            .map { mealPlans.observeWeek(it) }
            .toList()
        return combineAll(weeks).first().flatten().distinctBy { it.id }
    }

    private fun combineAll(weeks: List<Flow<MealPlanWeek>>): Flow<List<List<MealPlanEntry>>> =
        combine(weeks) { all -> all.map { week -> week.days.flatMap { it.meals } } }

    private suspend fun failed(problem: LocalMealCalendarProblem): Outcome.Failure {
        store.recordWrite(atMillis = clock.now().toEpochMilliseconds(), events = 0, problem = problem.name)
        return Outcome.Failure(TandemError.Unknown("tandem.meal-calendar.${problem.name.lowercase()}"))
    }

    private sealed interface ProviderResult<out T> {
        data class Done<T>(val value: T) : ProviderResult<T>

        data class Refused(val problem: LocalMealCalendarProblem) : ProviderResult<Nothing>
    }

    /** Runs [block] on the IO dispatcher; a revoked permission or a failing provider becomes [ProviderResult.Refused]. */
    private suspend fun <T : Any> onProvider(block: () -> T?): ProviderResult<T>? = withContext(Dispatchers.IO) {
        try {
            block()?.let { ProviderResult.Done(it) }
        } catch (e: SecurityException) {
            ProviderResult.Refused(LocalMealCalendarProblem.PERMISSION)
        } catch (e: IllegalArgumentException) {
            // No calendar provider on the phone (an unknown authority), or it refused the values.
            Log.w(TAG, "calendar provider refused: ${e.javaClass.simpleName}")
            ProviderResult.Refused(LocalMealCalendarProblem.PROVIDER)
        } catch (e: SQLException) {
            Log.w(TAG, "calendar provider failed: ${e.javaClass.simpleName}")
            ProviderResult.Refused(LocalMealCalendarProblem.PROVIDER)
        } catch (e: OperationApplicationException) {
            Log.w(TAG, "calendar batch failed: ${e.javaClass.simpleName}")
            ProviderResult.Refused(LocalMealCalendarProblem.PROVIDER)
        } catch (e: RemoteException) {
            Log.w(TAG, "calendar provider gone: ${e.javaClass.simpleName}")
            ProviderResult.Refused(LocalMealCalendarProblem.PROVIDER)
        }
    }

    private companion object {
        const val TAG = "TandemMealCalendar"
    }
}
