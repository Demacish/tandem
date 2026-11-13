package casa.moller.tandem.feature.mealplan.calendar

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.mealplan.MealCalendarRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.LocalMealCalendar
import casa.moller.tandem.core.model.mealplan.LocalMealCalendarProblem
import casa.moller.tandem.core.model.mealplan.MealCalendarAvailability
import casa.moller.tandem.core.model.mealplan.MealCalendarLink
import casa.moller.tandem.core.model.mealplan.MealCalendarLinkBlock
import casa.moller.tandem.core.model.mealplan.MealCalendarState
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** "Matsedeln i kalendern" in memory: the link and the local calendar as the tests set them. */
internal class FakeMealCalendarRepository(initial: MealCalendarState = MealCalendarSamples.off) : MealCalendarRepository {
    override val state = MutableStateFlow(initial)
    var permission = true
    var linkFailure: TandemError? = null
    val calls = mutableListOf<String>()

    override suspend fun enableLink(): Outcome<Unit> = link("enable") { MealCalendarSamples.link }

    override suspend fun replaceLink(): Outcome<Unit> =
        link("replace") { MealCalendarSamples.link.copy(url = MealCalendarSamples.URL + "_2") }

    override suspend fun disableLink(): Outcome<Unit> = link("disable") { MealCalendarLink() }

    override suspend fun setLocalCalendar(enabled: Boolean): Outcome<Unit> {
        calls += "local $enabled"
        if (enabled && !permission) return Outcome.Failure(TandemError.Unknown("tandem.meal-calendar.permission"))
        state.update { it.copy(local = if (enabled) MealCalendarSamples.written else LocalMealCalendar()) }
        return Outcome.Success(Unit)
    }

    override suspend fun refreshLocalCalendar(): Outcome<Int> {
        calls += "refresh"
        return Outcome.Success(state.value.local.events)
    }

    override fun hasCalendarPermission(): Boolean = permission

    private fun link(name: String, next: () -> MealCalendarLink): Outcome<Unit> {
        calls += name
        linkFailure?.let { return Outcome.Failure(it) }
        state.update { it.copy(link = next()) }
        return Outcome.Success(Unit)
    }
}

/** The states the screen, its screenshots and its ATF checks show. */
internal object MealCalendarSamples {
    const val URL = "https://tandoor.example.com/api/meal-plan/ical/?access_token=tda_00000000_0000_0000_0000_000000000041"

    /** Thursday 13 November 2026, 14:05 UTC. */
    val writtenAt: Instant = Instant.parse("2026-11-13T14:05:00Z")

    val link = MealCalendarLink(url = URL, expires = Instant.parse("2027-11-13T11:00:00Z"))

    val written = LocalMealCalendar(enabled = true, lastWritten = writtenAt, events = 12)

    val off = MealCalendarState(availability = MealCalendarAvailability.AVAILABLE)

    val on = off.copy(link = link, local = written)

    val permissionLost = off.copy(local = written.copy(problem = LocalMealCalendarProblem.PERMISSION))

    val guest = off.copy(link = MealCalendarLink(block = MealCalendarLinkBlock.GUEST), local = written)

    val unsupported = off.copy(link = MealCalendarLink(block = MealCalendarLinkBlock.UNSUPPORTED))

    val demo = MealCalendarState(availability = MealCalendarAvailability.DEMO)

    fun ui(state: MealCalendarState, canOpen: Boolean = true, confirm: LinkConfirm? = null, denied: Boolean = false) = MealCalendarUiState(
        loading = false,
        availability = state.availability,
        link = state.link,
        local = state.local,
        confirm = confirm,
        permissionDenied = denied,
        canOpenInCalendarApp = canOpen,
    )

    val readOnly: TandemError = DataProblem.READ_ONLY.toError()
}
