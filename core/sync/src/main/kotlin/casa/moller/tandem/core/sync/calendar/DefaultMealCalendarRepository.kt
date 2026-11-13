package casa.moller.tandem.core.sync.calendar

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.mealplan.MealCalendarRepository
import casa.moller.tandem.core.datastore.LocalCalendarRecord
import casa.moller.tandem.core.datastore.MealCalendarStore
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.LocalMealCalendar
import casa.moller.tandem.core.model.mealplan.LocalMealCalendarProblem
import casa.moller.tandem.core.model.mealplan.MealCalendarAvailability
import casa.moller.tandem.core.model.mealplan.MealCalendarState
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [MealCalendarRepository] (Fas 5, kalender lane, plan §7 #36): option a through [MealCalendarLinks],
 * option b through [PhoneMealCalendar] and its setting in [MealCalendarStore]; [MealCalendarSchedule]
 * keeps both in step in the background.
 */
@Singleton
internal class DefaultMealCalendarRepository @Inject constructor(
    private val sources: ActiveSources,
    private val links: MealCalendarLinks,
    private val calendar: PhoneMealCalendar,
    private val store: MealCalendarStore,
) : MealCalendarRepository {

    override val state: Flow<MealCalendarState> = combine(sources.source, links.observe(), store.state) { source, link, record ->
        MealCalendarState(availability = availabilityOf(source), link = link, local = localOf(record))
    }
        .catch { emit(MealCalendarState()) }
        .distinctUntilChanged()

    override suspend fun enableLink(): Outcome<Unit> = links.enable()

    override suspend fun replaceLink(): Outcome<Unit> = links.replace()

    override suspend fun disableLink(): Outcome<Unit> = links.disable()

    override suspend fun setLocalCalendar(enabled: Boolean): Outcome<Unit> {
        if (!enabled) {
            calendar.remove()
            store.setEnabled(false)
            return Outcome.Success(Unit)
        }
        if (sources.current() !is ActiveSource.Remote) return Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        if (!calendar.hasPermission()) return Outcome.Failure(TandemError.Unknown(PERMISSION_MISSING))
        store.setEnabled(true)
        return when (val written = calendar.write()) {
            is Outcome.Failure -> written
            is Outcome.Success -> Outcome.Success(Unit)
        }
    }

    override suspend fun refreshLocalCalendar(): Outcome<Int> = calendar.write()

    override fun hasCalendarPermission(): Boolean = calendar.hasPermission()

    private companion object {
        const val PERMISSION_MISSING = "tandem.meal-calendar.permission"

        fun availabilityOf(source: ActiveSource): MealCalendarAvailability = when (source) {
            ActiveSource.Loading -> MealCalendarAvailability.LOADING
            ActiveSource.None -> MealCalendarAvailability.NO_ACCOUNT
            ActiveSource.Demo -> MealCalendarAvailability.DEMO
            is ActiveSource.Remote -> MealCalendarAvailability.AVAILABLE
        }

        fun localOf(record: LocalCalendarRecord): LocalMealCalendar = LocalMealCalendar(
            enabled = record.enabled,
            problem = LocalMealCalendarProblem.entries.firstOrNull { it.name == record.problem }?.takeIf { record.enabled },
            lastWritten = record.lastWrittenMillis?.let(Instant::fromEpochMilliseconds),
            events = record.events,
        )
    }
}
