package casa.moller.tandem.feature.pantry.entry

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import casa.moller.tandem.feature.pantry.PantrySamples
import casa.moller.tandem.feature.pantry.navigation.PantryEntryKey
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Adding and changing a stock entry (Fas S, hemma-ui lane): defaults, quick picks, food search, replace, remove. */
@OptIn(ExperimentalCoroutinesApi::class)
class PantryEntryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pantry = FakePantryRepository(locations = PantrySamples.locations, entries = PantrySamples.entries)
    private val recipes = FakeRecipeRepository(
        foods = listOf(Food(101, "grädde"), Food(120, "gräddfil"), Food(121, "matlagningsgrädde"), Food(122, "smör")),
    )
    private val permissions = FakeSpacePermissionsRepository()
    private val calendar = FakeCalendarDays(PantrySamples.today)

    private fun viewModel(key: PantryEntryKey) = PantryEntryViewModel(key, SavedStateHandle(), pantry, recipes, permissions, calendar)

    private fun TestScope.start(vm: PantryEntryViewModel): MutableList<PantryEntryEffect> {
        val effects = mutableListOf<PantryEntryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onAction(PantryEntryAction.SetLocale(Locale.forLanguageTag("sv-SE")))
        advanceUntilIdle()
        return effects
    }

    @Test
    fun aNewEntryGoesInTheFirstPlaceThatIsNotAFreezerWithTheQuickPicks() = runTest {
        val vm = viewModel(PantryEntryKey())
        val effects = start(vm)
        val state = vm.uiState.value
        assertTrue(state.isNew)
        assertEquals(PantrySamples.fridge.id, state.locationId)
        assertEquals(
            listOf(ExpiryChoice.PLUS_3_DAYS, ExpiryChoice.PLUS_1_WEEK, ExpiryChoice.PLUS_1_MONTH, ExpiryChoice.NONE),
            state.expiryChoices,
        )
        assertEquals("1", state.amount)

        vm.onAction(PantryEntryAction.FoodText("gräd"))
        advanceUntilIdle()
        assertEquals(listOf("grädde", "gräddfil", "matlagningsgrädde"), vm.uiState.value.suggestions.map { it.name })
        vm.onAction(PantryEntryAction.PickFood(Food(101, "grädde")))
        vm.onAction(PantryEntryAction.AmountText("2,5"))
        vm.onAction(PantryEntryAction.UnitText("dl"))
        vm.onAction(PantryEntryAction.PickExpiry(ExpiryChoice.PLUS_1_WEEK))
        vm.onAction(PantryEntryAction.NoteText("Till söndagen"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.suggestions.isEmpty())
        assertEquals(ExpiryChoice.PLUS_1_WEEK, vm.uiState.value.selectedChoice)
        vm.onAction(PantryEntryAction.Save)
        advanceUntilIdle()

        val added = pantry.entries.value.last()
        assertEquals("grädde", added.food.name)
        assertEquals(101L, added.food.id)
        assertEquals(2.5, added.amount, 0.0)
        assertEquals("dl", added.unit?.name)
        assertEquals(LocalDate(2026, 11, 5), added.expires)
        assertEquals("Till söndagen", added.note)
        assertEquals(PantryEntryEffect.Done, effects.last())
    }

    @Test
    fun aPlaceOpenedFromItsCardIsChosenAndTheFreezerOffersLongerDays() = runTest {
        val vm = viewModel(PantryEntryKey(locationId = PantrySamples.freezer.id))
        start(vm)
        assertEquals(PantrySamples.freezer.id, vm.uiState.value.locationId)
        assertEquals(listOf(ExpiryChoice.PLUS_1_MONTH, ExpiryChoice.PLUS_3_MONTHS, ExpiryChoice.NONE), vm.uiState.value.expiryChoices)
        vm.onAction(PantryEntryAction.PickExpiry(ExpiryChoice.PLUS_3_MONTHS))
        assertEquals(LocalDate(2027, 1, 29), vm.uiState.value.expires)
    }

    @Test
    fun aWrongAmountIsNotSaved() = runTest {
        val vm = viewModel(PantryEntryKey())
        start(vm)
        vm.onAction(PantryEntryAction.FoodText("mjöl"))
        vm.onAction(PantryEntryAction.AmountText("två"))
        vm.onAction(PantryEntryAction.Save)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.amountError)
        assertEquals(PantrySamples.entries.size, pantry.entries.value.size)
        vm.onAction(PantryEntryAction.AmountText("0"))
        vm.onAction(PantryEntryAction.Save)
        advanceUntilIdle()
        assertTrue("0 is \"Slut\", not a new entry", vm.uiState.value.amountError)
    }

    @Test
    fun anExistingEntryFillsTheFormOnceAndSavesItsChanges() = runTest {
        val vm = viewModel(PantryEntryKey(entryId = PantrySamples.cream.id))
        start(vm)
        val state = vm.uiState.value
        assertFalse(state.isNew)
        assertEquals("grädde", state.food)
        assertEquals("3", state.amount)
        assertEquals("dl", state.unit)
        assertEquals(LocalDate(2026, 10, 30), state.expires)
        assertNull("a day picked in the calendar is no quick pick", state.selectedChoice)

        vm.onAction(PantryEntryAction.AmountText("1,5"))
        vm.onAction(PantryEntryAction.PickLocation(PantrySamples.freezer.id))
        vm.onAction(PantryEntryAction.PickExpiry(ExpiryChoice.NONE))
        vm.onAction(PantryEntryAction.Save)
        advanceUntilIdle()
        val changed = pantry.entries.value.first { it.id == PantrySamples.cream.id }
        assertEquals(1.5, changed.amount, 0.0)
        assertEquals(PantrySamples.freezer.id, changed.locationId)
        assertNull(changed.expires)
    }

    @Test
    fun anotherFoodReplacesTheEntry() = runTest {
        val vm = viewModel(PantryEntryKey(entryId = PantrySamples.cream.id))
        start(vm)
        vm.onAction(PantryEntryAction.FoodText("crème fraiche"))
        vm.onAction(PantryEntryAction.Save)
        advanceUntilIdle()
        assertTrue(pantry.entries.value.none { it.id == PantrySamples.cream.id })
        val replacement = pantry.entries.value.last()
        assertEquals("crème fraiche", replacement.food.name)
        assertEquals(3.0, replacement.amount, 0.0)
        assertEquals(LocalDate(2026, 10, 30), replacement.expires)
    }

    @Test
    fun removingAsksFirst() = runTest {
        val vm = viewModel(PantryEntryKey(entryId = PantrySamples.milk.id))
        val effects = start(vm)
        vm.onAction(PantryEntryAction.AskDelete)
        assertTrue(vm.uiState.value.confirmDelete)
        vm.onAction(PantryEntryAction.ConfirmDelete(false))
        advanceUntilIdle()
        assertTrue(pantry.entries.value.any { it.id == PantrySamples.milk.id })
        vm.onAction(PantryEntryAction.AskDelete)
        vm.onAction(PantryEntryAction.ConfirmDelete(true))
        advanceUntilIdle()
        assertTrue(pantry.entries.value.none { it.id == PantrySamples.milk.id })
        assertEquals(PantryEntryEffect.Done, effects.last())
    }

    @Test
    fun anEntryRemovedElsewhereSaysSo() = runTest {
        val vm = viewModel(PantryEntryKey(entryId = PantrySamples.milk.id))
        start(vm)
        pantry.deleteEntry(PantrySamples.milk.id)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.missing)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test
    fun aGuestCannotSave() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val vm = viewModel(PantryEntryKey())
        start(vm)
        vm.onAction(PantryEntryAction.FoodText("mjöl"))
        vm.onAction(PantryEntryAction.Save)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canSave)
        assertEquals(PantrySamples.entries.size, pantry.entries.value.size)
    }
}
