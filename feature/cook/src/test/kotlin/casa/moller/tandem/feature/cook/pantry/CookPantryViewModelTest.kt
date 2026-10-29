package casa.moller.tandem.feature.cook.pantry

import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryUnit
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** "Tog något slut?" (Fas S, hemma-ui lane, plan §8.8): the recipe's foods in the pantry, "Slut" and "−1" with "Ångra", guests. */
@OptIn(ExperimentalCoroutinesApi::class)
class CookPantryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pasta = SampleRecipes.MushroomPasta
    private val butter = InventoryEntry(21, 1, PantryFood(2, "smör"), PantryUnit(4, "paket"), 2.0, LocalDate(2026, 11, 20))
    private val salt = InventoryEntry(22, 3, PantryFood(5, "salt"), PantryUnit(5, "st"), 1.0)
    private val pantry = FakePantryRepository(entries = listOf(butter, salt, SamplePantry.cream))
    private val permissions = FakeSpacePermissionsRepository()
    private val recipes = FakeRecipeRepository(details = listOf(pasta))

    private fun viewModel() = CookPantryViewModel(pasta.id, recipes, pantry, permissions, FakeCalendarDays(SamplePantry.TODAY))

    @Test
    fun theRecipesFoodsInThePantryAreOfferedAndNothingIsTakenByItself() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<CookPantryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        assertEquals(listOf("smör", "salt"), vm.uiState.value.rows.map { it.entry.food.name })
        assertEquals(listOf("Kyl", "Skafferi"), vm.uiState.value.rows.map { it.locationName })
        assertEquals(2.0, pantry.entries.value.first { it.id == butter.id }.amount, 0.0)

        vm.onAction(CookPantryAction.Decrement(butter.id))
        advanceUntilIdle()
        assertEquals(1.0, pantry.entries.value.first { it.id == butter.id }.amount, 0.0)
        assertEquals(CookPantryEffect.Changed("smör", 1.0, "paket"), effects.last())
        vm.onAction(CookPantryAction.MarkEmpty(salt.id))
        advanceUntilIdle()
        assertEquals(listOf("smör"), vm.uiState.value.rows.map { it.entry.food.name })
        vm.onAction(CookPantryAction.Undo)
        advanceUntilIdle()
        assertEquals(1.0, pantry.entries.value.first { it.id == salt.id }.amount, 0.0)
    }

    @Test
    fun aGuestIsNotAsked() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.visible)
        vm.onAction(CookPantryAction.MarkEmpty(butter.id))
        advanceUntilIdle()
        assertEquals(2.0, pantry.entries.value.first { it.id == butter.id }.amount, 0.0)
    }
}
