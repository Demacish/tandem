package casa.moller.tandem.feature.cook.kitchen

import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeHomeRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fas 5 (kok lane, plan §8.7 "Köksskärmsläge", #41): the kitchen display's data. */
@OptIn(ExperimentalCoroutinesApi::class)
class KitchenViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val clock = TestClock()
    private val home = FakeHomeRepository()
    private val timers = FakeTimerController(clock)

    @Test
    fun todaysAndTomorrowsMealsFromTheMealPlan() = runTest(mainDispatcher.dispatcher) {
        val viewModel = KitchenViewModel(home, timers)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(
            listOf(KitchenMeal(1, SampleRecipes.MushroomPasta.name, SampleRecipes.MushroomPasta.id, null, 4.0, "Middag")),
            state.todayMeals.map { it.copy(imageUrl = null) },
        )
        // A title-only meal has no recipe to cook.
        assertEquals(listOf("Rester", SampleRecipes.PeaSoup.name), state.tomorrowMeals.map { it.title })
        assertEquals(null, state.tomorrowMeals.first().recipeId)
    }

    @Test
    fun activeTimersRingingFirstAndTheirButtonsWork() = runTest(mainDispatcher.dispatcher) {
        val viewModel = KitchenViewModel(home, timers)
        backgroundScope.launch { viewModel.uiState.collect {} }
        timers.start("Pastan · 10 min", 10.minutes, SampleRecipes.MushroomPasta.id, 1)
        timers.start("Svampen · 5 min", 5.minutes, SampleRecipes.MushroomPasta.id, 0)
        timers.advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(listOf(TimerState.RINGING, TimerState.RUNNING), viewModel.uiState.value.timers.map { it.state })

        val ringing = viewModel.uiState.value.timers.first().id
        viewModel.onAction(KitchenAction.StopRinging(ringing))
        runCurrent()
        // A finished timer leaves the display.
        assertEquals(listOf("Pastan · 10 min"), viewModel.uiState.value.timers.map { it.label })

        val pasta = viewModel.uiState.value.timers.single().id
        viewModel.onAction(KitchenAction.PauseTimer(pasta))
        runCurrent()
        assertEquals(TimerState.PAUSED, viewModel.uiState.value.timers.single().state)
        viewModel.onAction(KitchenAction.ResumeTimer(pasta))
        viewModel.onAction(KitchenAction.AddMinute(pasta))
        runCurrent()
        assertEquals(TimerState.RUNNING, viewModel.uiState.value.timers.single().state)
        viewModel.onAction(KitchenAction.CancelTimer(pasta))
        runCurrent()
        assertTrue(viewModel.uiState.value.timers.isEmpty())
    }

    /**
     * The 3-hour plan criterion in CI (the device test is the owner's): with nothing changing, three
     * hours bring no new state and no new subscription to the data (no ticking loop, no leak).
     */
    @Test
    fun threeHoursOnTheCounterChangeNothing() = runTest(mainDispatcher.dispatcher) {
        val viewModel = KitchenViewModel(home, timers)
        timers.start("Marinera · 4 timmar", 4.hours, SampleRecipes.MushroomPasta.id, 0)
        val states = mutableListOf<KitchenUiState>()
        backgroundScope.launch { viewModel.uiState.collect { states += it } }
        runCurrent()
        val settled = states.size
        assertEquals(1, home.upcomingMeals.subscriptionCount.value)

        repeat(180) {
            clock.advanceBy(1.minutes)
            advanceTimeBy(1.minutes)
        }
        assertEquals(settled, states.size)
        assertEquals(1, home.upcomingMeals.subscriptionCount.value)

        // A change still arrives after the three hours.
        home.upcomingMeals.value = home.upcomingMeals.value.copy(todayMeals = emptyList())
        runCurrent()
        assertTrue(viewModel.uiState.value.todayMeals.isEmpty())
    }
}
