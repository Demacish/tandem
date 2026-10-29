package casa.moller.tandem.feature.pantry.makenow

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeMakeNowRepository
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeStaplesRepository
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import casa.moller.tandem.feature.pantry.PantrySamples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Kan laga nu" (#23, #28, Fas S hemma-ui lane): the three groups without drinks, "Använd upp"
 * first, the missing foods, "Lägg det som saknas på listan" with exactly the missing ones, the
 * filters, "Överraska mig", the staples prompt and guests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MakeNowViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val makeNow = FakeMakeNowRepository(PantrySamples.makeNow)
    private val staples = FakeStaplesRepository()
    private val pantry = FakePantryRepository(locations = PantrySamples.locations, entries = PantrySamples.entries)
    private val permissions = FakeSpacePermissionsRepository()

    private fun TestScope.start(
        useUpFirst: Boolean = false,
        recipes: FakeRecipeRepository = FakeRecipeRepository(),
    ): Pair<MakeNowViewModel, MutableList<MakeNowEffect>> {
        val vm = MakeNowViewModel(
            useUpFirst,
            SavedStateHandle(),
            makeNow,
            staples,
            pantry,
            recipes,
            FakeMealPlanRepository(),
            permissions,
            FakeCalendarDays(PantrySamples.today),
        )
        val effects = mutableListOf<MakeNowEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        return vm to effects
    }

    @Test
    fun drinksAreLeftOutAndRecipesUsingUpComeFirst() = runTest {
        val (vm) = start()
        val state = vm.uiState.value
        assertEquals(listOf("Krämig kycklinggryta", "Pannkakor", "Omelett"), state.rows(MakeNowGroup.HAS_ALL).map { it.recipe.name })
        assertEquals(listOf("Potatisgratäng"), state.rows(MakeNowGroup.MISSING_1).map { it.recipe.name })
        assertEquals(listOf("tahini", "citron"), state.rows(MakeNowGroup.MISSING_2).single().missing?.map { it.name })
        assertTrue(state.rows(MakeNowGroup.HAS_ALL).first().usesUp)
        assertEquals(MakeNowGroup.HAS_ALL, state.selected)
        assertEquals("the expiring pantry entries for the Använd upp line", 3, state.expiring.size)
        assertTrue("refreshed once when opened", makeNow.refreshes >= 1)
    }

    @Test
    fun fromExpiringSoonItOpensOnTheFirstGroupThatUsesSomethingUp() = runTest {
        makeNow.state.value = MakeNowState(
            mapOf(
                MakeNowGroup.HAS_ALL to listOf(MakeNowRecipe(PantrySamples.pancakes, MakeNowGroup.HAS_ALL, missingFoods = emptyList())),
                MakeNowGroup.MISSING_1 to PantrySamples.makeNow.getValue(MakeNowGroup.MISSING_1),
            ),
        )
        val (vm) = start(useUpFirst = true)
        assertEquals(MakeNowGroup.MISSING_1, vm.uiState.value.selected)
        vm.onAction(MakeNowAction.SelectGroup(MakeNowGroup.MISSING_2))
        assertEquals(MakeNowGroup.MISSING_2, vm.uiState.value.selected)
    }

    @Test
    fun theMissingFoodsAreWorkedOutWhenARowIsShown() = runTest {
        makeNow.state.value = MakeNowState(
            mapOf(MakeNowGroup.MISSING_1 to listOf(MakeNowRecipe(PantrySamples.gratin, MakeNowGroup.MISSING_1, missingFoods = null))),
        )
        val (vm) = start()
        val row = vm.uiState.value.rows(MakeNowGroup.MISSING_1).single()
        assertEquals(null, row.missing)
        vm.onAction(MakeNowAction.RowShown(PantrySamples.gratin.id))
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.rows(MakeNowGroup.MISSING_1).single().missing)
    }

    @Test
    fun addTheMissingOnesOpensTheSheetWithExactlyThose() = runTest {
        val (vm, effects) = start()
        vm.onAction(MakeNowAction.AddMissingToList(PantrySamples.hummus.id))
        advanceUntilIdle()
        assertEquals(MakeNowEffect.AddToList(PantrySamples.hummus.id, setOf(108L, 109L)), effects.last())
        // A recipe that has it all has nothing to add.
        vm.onAction(MakeNowAction.AddMissingToList(PantrySamples.pancakes.id))
        advanceUntilIdle()
        assertEquals(1, effects.count { it is MakeNowEffect.AddToList })
    }

    @Test
    fun aMealTypeIsOfferedThroughAKeywordOfTheSameNameAndFiltersOnTheDevice() = runTest {
        // FakeMealPlanRepository has "Middag" and "Lunch"; only "Middag" has a keyword.
        val dinner = Keyword(id = 50, name = "middag")
        val weekday = Keyword(id = 51, name = "Vardag", parentId = 50)
        makeNow.state.value = MakeNowState(
            mapOf(
                MakeNowGroup.HAS_ALL to listOf(
                    MakeNowRecipe(PantrySamples.creamSauce.copy(keywords = listOf(weekday)), MakeNowGroup.HAS_ALL, missingFoods = emptyList()),
                    MakeNowRecipe(PantrySamples.omelette, MakeNowGroup.HAS_ALL, missingFoods = emptyList()),
                ),
            ),
        )
        val (vm, effects) = start(recipes = FakeRecipeRepository(keywords = listOf(dinner, weekday)))
        assertEquals(listOf("Middag"), vm.uiState.value.mealTypes.map { it.name })
        assertEquals(2, vm.uiState.value.rows(MakeNowGroup.HAS_ALL).size)

        val middag = vm.uiState.value.mealTypes.single().id
        vm.onAction(MakeNowAction.SetMealType(middag))
        advanceUntilIdle()
        // A child keyword of "Middag" counts.
        assertEquals(listOf("Krämig kycklinggryta"), vm.uiState.value.rows(MakeNowGroup.HAS_ALL).map { it.recipe.name })
        vm.onAction(MakeNowAction.Surprise(MakeNowGroup.HAS_ALL))
        advanceUntilIdle()
        assertEquals(MakeNowEffect.OpenRecipe(PantrySamples.creamSauce.id), effects.last())
    }

    @Test
    fun theFiltersGoToTheRepositoryAndCanBeCleared() = runTest {
        val (vm) = start()
        vm.onAction(MakeNowAction.SetTime(TimeFilter.QUICK))
        vm.onAction(MakeNowAction.SetMealType(3))
        vm.onAction(MakeNowAction.ToggleKeyword(7))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(30, state.filter.maxMinutes)
        assertEquals(3L, state.filter.mealTypeId)
        assertEquals(setOf(7L), state.filter.keywordIds)
        assertTrue(state.filtered)
        assertEquals(state.filter, makeNow.filters.last())
        assertTrue("a recipe over 30 minutes is left out", state.rows(MakeNowGroup.MISSING_1).isEmpty())
        vm.onAction(MakeNowAction.ClearFilters)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.filtered)
    }

    @Test
    fun surpriseOpensARecipeOfTheGroupButNeverADrink() = runTest {
        val (vm, effects) = start()
        vm.onAction(MakeNowAction.Surprise(MakeNowGroup.MISSING_2))
        advanceUntilIdle()
        assertEquals(MakeNowEffect.OpenRecipe(PantrySamples.hummus.id), effects.last())
        // The fake's "random" is the first of the group: the drink.
        vm.onAction(MakeNowAction.Surprise(MakeNowGroup.HAS_ALL))
        advanceUntilIdle()
        assertEquals(MakeNowEffect.NothingToSurprise, effects.last())
    }

    @Test
    fun theStaplesAreOfferedUntilShownOrDismissed() = runTest {
        val (vm) = start()
        assertTrue(vm.uiState.value.staplesPrompt)
        vm.onAction(MakeNowAction.DismissStaples)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.staplesPrompt)
    }

    @Test
    fun aGuestSeesTheGroupsButAddsNothingAndGetsNoStaplesPrompt() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val (vm, effects) = start()
        assertTrue(vm.uiState.value.readOnly)
        assertFalse(vm.uiState.value.staplesPrompt)
        assertEquals(3, vm.uiState.value.rows(MakeNowGroup.HAS_ALL).size)
        vm.onAction(MakeNowAction.AddMissingToList(PantrySamples.hummus.id))
        advanceUntilIdle()
        assertTrue(effects.none { it is MakeNowEffect.AddToList })
    }
}
