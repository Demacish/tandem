package casa.moller.tandem.feature.home

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.home.HomeLayout
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeHomeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val home = FakeHomeRepository()
    private val sync = FakeSyncRepository()

    private fun newViewModel(hour: Int = 19) = HomeViewModel(home, sync, currentHour = { hour })

    /** The first state that is not the initial loading placeholder. */
    private suspend fun ReceiveTurbine<HomeUiState>.awaitLoaded(): HomeUiState {
        var state = awaitItem()
        while (state.loading) state = awaitItem()
        return state
    }

    @Test
    fun showsTonightTomorrowAndTheSectionsFromTheRepository() = runTest {
        newViewModel().uiState.test {
            val state = awaitLoaded()
            assertEquals(SampleRecipes.Today, state.today)
            assertEquals(SampleRecipes.MushroomPasta.id, state.tonight?.recipe?.id)
            assertTrue(state.alsoToday.isEmpty())
            // Tomorrow in meal type order: lunch (title only) before dinner.
            assertEquals(listOf("Rester", "Ärtsoppa med fläsk"), state.tomorrow.map { it.displayTitle })
            assertEquals(SampleRecipes.homeSections.map { it.type }, state.sections.map { it.type })
            assertFalse(state.nothingPlannedToday)
            assertFalse(state.offline)
            assertEquals(DayPart.EVENING, state.dayPart)
        }
    }

    @Test
    fun theUserOrdersAndHidesTheCarousels() = runTest {
        // Fas 3's leftovers ("Hemsektioner som användaren konfigurerar"): saved on the device at once.
        val viewModel = newViewModel()
        viewModel.uiState.test {
            awaitLoaded()
            viewModel.onAction(HomeAction.Customize(open = true))
            assertTrue(expectMostRecentItem().customizing)
            viewModel.onAction(HomeAction.MoveSection(HomeSectionType.NEW, -2))
            var state = expectMostRecentItem()
            assertEquals(
                listOf(HomeSectionType.NEW, HomeSectionType.PINNED, HomeSectionType.OFTEN_COOKED, HomeSectionType.NOT_COOKED_LATELY),
                state.sections.map { it.type },
            )
            viewModel.onAction(HomeAction.ShowSection(HomeSectionType.PINNED, visible = false))
            state = expectMostRecentItem()
            val shown = listOf(HomeSectionType.NEW, HomeSectionType.OFTEN_COOKED, HomeSectionType.NOT_COOKED_LATELY)
            assertEquals(shown, state.sections.map { it.type })
            assertFalse(state.layout.isVisible(HomeSectionType.PINNED))
            assertEquals(state.layout, home.layout.value)
            viewModel.onAction(HomeAction.ResetSections)
            state = expectMostRecentItem()
            assertEquals(SampleRecipes.homeSections.map { it.type }, state.sections.map { it.type })
            viewModel.onAction(HomeAction.Customize(open = false))
            assertFalse(expectMostRecentItem().customizing)
        }
    }

    @Test
    fun aStoredLayoutSurvivesUnknownNamesAndKeepsNewSectionsLast() {
        val layout = HomeLayout.decode("NEW,GONE,PINNED;NOT_COOKED_LATELY,GONE")
        assertEquals(HomeSectionType.NEW, layout.types.first())
        assertEquals(HomeSectionType.entries.toSet(), layout.types.toSet())
        assertFalse(layout.isVisible(HomeSectionType.NOT_COOKED_LATELY))
        val again = HomeLayout.decode(layout.encode())
        assertEquals(layout.types, again.types)
        assertEquals(layout.hidden, again.hidden)
        assertEquals(HomeLayout(), HomeLayout.decode(null))
        assertEquals(HomeLayout(), HomeLayout.decode("nonsens"))
    }

    @Test
    fun refreshesOnceWhenOpenedWithoutAskingTheSync() = runTest {
        newViewModel()
        assertEquals(1, home.refreshCount)
        assertTrue(sync.requests.isEmpty())
    }

    @Test
    fun pullToRefreshRefreshesAndAsksForAUserSync() = runTest {
        val vm = newViewModel()
        vm.onAction(HomeAction.Refresh)
        assertEquals(2, home.refreshCount)
        assertEquals(listOf(SyncReason.USER_REFRESH), sync.requests)
    }

    @Test
    fun navigationActionsDoNotTouchTheRepositories() = runTest {
        val vm = newViewModel()
        vm.onAction(HomeAction.OpenRecipe(SampleRecipes.PeaSoup.id))
        vm.onAction(HomeAction.OpenRecipes)
        vm.onAction(HomeAction.OpenWeek)
        assertEquals(1, home.refreshCount)
        assertTrue(sync.requests.isEmpty())
    }

    @Test
    fun failedRefreshKeepsTheContentMarksOfflineAndTellsTheUser() = runTest {
        val vm = newViewModel()
        home.refreshResult = Outcome.Failure(TandemError.NetworkUnavailable)
        vm.effects.test {
            vm.onAction(HomeAction.Refresh)
            assertEquals(HomeEffect.RefreshFailed(TandemError.NetworkUnavailable), awaitItem())
        }
        vm.uiState.test {
            val state = awaitLoaded()
            assertTrue(state.offline)
            assertFalse(state.refreshing)
            assertEquals(SampleRecipes.homeSections.size, state.sections.size)
            assertEquals(SampleRecipes.MushroomPasta.id, state.tonight?.recipe?.id)
        }
    }

    @Test
    fun failedAutomaticRefreshIsQuiet() = runTest {
        home.refreshResult = Outcome.Failure(TandemError.Timeout)
        val vm = newViewModel()
        vm.effects.test { expectNoEvents() }
        vm.uiState.test { assertTrue(awaitLoaded().offline) }
    }

    @Test
    fun serverErrorIsNotOffline() = runTest {
        home.refreshResult = Outcome.Failure(TandemError.ServerError(502))
        val vm = newViewModel()
        vm.uiState.test { assertFalse(awaitLoaded().offline) }
    }

    @Test
    fun syncPausedOfflineMarksOffline() = runTest {
        sync.setStatus(SyncStatus(pause = SyncPause.OFFLINE))
        newViewModel().uiState.test { assertTrue(awaitLoaded().offline) }
    }

    @Test
    fun syncStatusIsShown() = runTest {
        val lastSuccess = Instant.parse("2026-10-06T16:14:00Z")
        sync.setStatus(SyncStatus(running = true, lastSuccess = lastSuccess))
        newViewModel().uiState.test {
            val state = awaitLoaded()
            assertEquals(lastSuccess, state.lastSynced)
            assertTrue(state.syncRunning)
        }
    }

    @Test
    fun emptyPlanShowsNothingPlannedAndEmptySectionsAreLeftOut() = runTest {
        home.upcomingMeals.value = UpcomingMeals(today = SampleRecipes.Today)
        home.sections.value = emptyList()
        newViewModel(hour = 8).uiState.test {
            val state = awaitLoaded()
            assertTrue(state.nothingPlannedToday)
            assertNull(state.tonight)
            assertTrue(state.tomorrow.isEmpty())
            assertTrue(state.sections.isEmpty())
            assertEquals(DayPart.MORNING, state.dayPart)
        }
    }

    @Test
    fun followsChangesInTheStoredMealPlan() = runTest {
        newViewModel().uiState.test {
            awaitLoaded()
            home.upcomingMeals.value = UpcomingMeals(today = SampleRecipes.Today)
            var state = awaitItem()
            while (!state.nothingPlannedToday) state = awaitItem()
            assertNull(state.tonight)
        }
    }

    @Test
    fun tonightIsTheEveningMeal() {
        val breakfast = entry(1, MealType(id = 10, name = "Frukost", order = 0, time = LocalTime(7, 0)))
        val lunch = entry(2, SampleRecipes.Lunch)
        val dinner = entry(3, SampleRecipes.Dinner)
        val (tonight, rest) = meals(dinner, breakfast, lunch).splitToday()
        assertEquals(dinner, tonight)
        assertEquals(listOf(breakfast, lunch), rest)
    }

    @Test
    fun withoutTimesTonightIsTheLastMealOfTheDay() {
        val first = entry(1, MealType(id = 10, name = "Lunch", order = 0))
        val last = entry(2, MealType(id = 11, name = "Middag", order = 1))
        assertEquals(last, meals(last, first).splitToday().first)
    }

    @Test
    fun aDaytimeMealIsNeverTonight() {
        val lunch = entry(1, SampleRecipes.Lunch)
        val (tonight, rest) = meals(lunch).splitToday()
        assertNull(tonight)
        assertEquals(listOf(lunch), rest)
    }

    @Test
    fun dayParts() {
        assertEquals(DayPart.NIGHT, DayPart.of(3))
        assertEquals(DayPart.MORNING, DayPart.of(5))
        assertEquals(DayPart.DAY, DayPart.of(12))
        assertEquals(DayPart.EVENING, DayPart.of(17))
        assertEquals(DayPart.NIGHT, DayPart.of(22))
    }

    private fun entry(id: Long, type: MealType) =
        MealPlanEntry(id = id, title = "Meal $id", fromDate = SampleRecipes.Today, mealType = type)

    private fun meals(vararg entries: MealPlanEntry) = UpcomingMeals(today = SampleRecipes.Today, todayMeals = entries.toList())
}
