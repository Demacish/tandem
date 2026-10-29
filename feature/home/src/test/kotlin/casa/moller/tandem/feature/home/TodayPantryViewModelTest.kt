package casa.moller.tandem.feature.home

import casa.moller.tandem.core.model.makenow.ExpiringFood
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeMakeNowRepository
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Idag's "Kan laga nu" and "Går ut snart" (Fas S, hemma-ui lane, plan §8.8, #23, #28). */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayPantryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val cream = ExpiringFood(101, "grädde", LocalDate(2026, 10, 30))
    private val wine = RecipeOverview(id = 61, name = "Rioja Crianza", keywords = listOf(Keyword(90, "Dryck")))
    private val sauce = RecipeOverview(id = 4, name = "Krämig kycklinggryta")

    private val makeNow = FakeMakeNowRepository(
        mapOf(
            MakeNowGroup.HAS_ALL to listOf(
                MakeNowRecipe(wine, MakeNowGroup.HAS_ALL, missingFoods = emptyList()),
                MakeNowRecipe(SamplePantry.pancakes, MakeNowGroup.HAS_ALL, missingFoods = emptyList()),
                MakeNowRecipe(sauce, MakeNowGroup.HAS_ALL, missingFoods = emptyList(), expiring = listOf(cream)),
            ),
            MakeNowGroup.MISSING_1 to listOf(MakeNowRecipe(SamplePantry.gratin, MakeNowGroup.MISSING_1, expiring = listOf(cream))),
            MakeNowGroup.MISSING_2 to listOf(MakeNowRecipe(SamplePantry.hummus, MakeNowGroup.MISSING_2)),
        ),
    )
    private val pantry = FakePantryRepository()
    private val calendar = FakeCalendarDays(SamplePantry.TODAY)

    @Test
    fun haveItAllFirstThenMissingOneWithoutDrinks() = runTest {
        val vm = TodayPantryViewModel(makeNow, pantry, calendar)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(listOf("Pannkakor", "Krämig kycklinggryta", "Potatisgratäng"), state.makeNow.map { it.name })
        assertEquals(2, state.hasAll)
    }

    @Test
    fun expiringSoonCountsTheRecipesThatUseIt() = runTest {
        val vm = TodayPantryViewModel(makeNow, pantry, calendar)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        val expiring = vm.uiState.value.expiring
        assertEquals(listOf("grädde"), expiring.map { it.entry.food.name })
        assertEquals(2, expiring.single().recipeCount)
        assertEquals(ExpiryUrgency.SOON, expiring.single().urgency)

        // The next day the cream's last day is today; in a week nothing of the samples is close but the milk.
        calendar.day.value = LocalDate(2026, 10, 30)
        advanceUntilIdle()
        assertEquals(ExpiryUrgency.TODAY, vm.uiState.value.expiring.first().urgency)
    }

    @Test
    fun emptyWhenThePantryAndCookNowAreEmpty() = runTest {
        val vm = TodayPantryViewModel(FakeMakeNowRepository(emptyMap()), FakePantryRepository(entries = emptyList()), calendar)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEmpty)
    }
}
