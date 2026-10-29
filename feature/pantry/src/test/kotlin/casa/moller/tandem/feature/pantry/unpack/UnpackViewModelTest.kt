package casa.moller.tandem.feature.pantry.unpack

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import casa.moller.tandem.feature.pantry.PantrySamples
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

/** "Packa upp" (#46, Fas S hemma-ui lane): defaults, one choice for all, per item, skipping. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnpackViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pantry = FakePantryRepository(locations = PantrySamples.locations, entries = emptyList())
    private val permissions = FakeSpacePermissionsRepository()
    private val calendar = FakeCalendarDays(PantrySamples.today)
    private val eggs = SamplePantry.unpack[0].shoppingEntryId
    private val butter = SamplePantry.unpack[1].shoppingEntryId

    private fun TestScope.start(handle: SavedStateHandle = SavedStateHandle()): Pair<UnpackViewModel, MutableList<UnpackEffect>> {
        val vm = UnpackViewModel(handle, pantry, permissions, calendar)
        val effects = mutableListOf<UnpackEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        return vm to effects
    }

    @Test
    fun todaysItemsGoInTheFirstPlaceThatIsNotAFreezerWithoutADay() = runTest {
        val (vm) = start()
        val state = vm.uiState.value
        assertEquals(listOf("ägg", "smör"), state.rows.map { it.item.food.name })
        assertTrue(state.rows.all { it.locationId == PantrySamples.fridge.id && it.expiry == ExpiryChoice.NONE && it.include })
        assertEquals(PantrySamples.fridge.id, state.allLocationId)
        assertEquals(2, state.chosenCount)
    }

    @Test
    fun oneChoiceForAllThenOneItemOfItsOwn() = runTest {
        val (vm, effects) = start()
        vm.onAction(UnpackAction.AllLocation(PantrySamples.freezer.id))
        vm.onAction(UnpackAction.AllExpiry(ExpiryChoice.PLUS_1_MONTH))
        assertEquals(listOf(ExpiryChoice.PLUS_1_MONTH, ExpiryChoice.PLUS_3_MONTHS, ExpiryChoice.NONE), vm.uiState.value.allExpiryChoices)
        vm.onAction(UnpackAction.ItemLocation(eggs, PantrySamples.fridge.id))
        vm.onAction(UnpackAction.ItemExpiry(eggs, ExpiryChoice.PLUS_1_WEEK))
        assertNull("the items differ now", vm.uiState.value.allLocationId)
        vm.onAction(UnpackAction.Save)
        advanceUntilIdle()

        val eggEntry = pantry.entries.value.first { it.food.name == "ägg" }
        val butterEntry = pantry.entries.value.first { it.food.name == "smör" }
        assertEquals(PantrySamples.fridge.id, eggEntry.locationId)
        assertEquals(LocalDate(2026, 11, 5), eggEntry.expires)
        assertEquals(12.0, eggEntry.amount, 0.0)
        assertEquals(PantrySamples.freezer.id, butterEntry.locationId)
        assertEquals(LocalDate(2026, 11, 29), butterEntry.expires)
        assertEquals(UnpackEffect.Done(2), effects.last())
        assertTrue("nothing is left to unpack", vm.uiState.value.rows.isEmpty())
    }

    @Test
    fun anItemTickedOffIsNotPutAway() = runTest {
        val (vm) = start()
        vm.onAction(UnpackAction.ToggleItem(butter))
        assertEquals(1, vm.uiState.value.chosenCount)
        vm.onAction(UnpackAction.Save)
        advanceUntilIdle()
        assertEquals(listOf("ägg"), pantry.entries.value.map { it.food.name })
        assertTrue("the skipped one is done for today too", pantry.unpackable.value.isEmpty())
    }

    @Test
    fun skippingAddsNothingAndClearsTheList() = runTest {
        val (vm, effects) = start()
        vm.onAction(UnpackAction.Skip)
        advanceUntilIdle()
        assertTrue(pantry.entries.value.isEmpty())
        assertTrue(pantry.unpackable.value.isEmpty())
        assertEquals(UnpackEffect.Done(0), effects.last())
    }

    @Test
    fun theChoicesSurviveARecreatedViewModel() = runTest {
        val handle = SavedStateHandle()
        val (first) = start(handle)
        first.onAction(UnpackAction.ItemExpiry(butter, ExpiryChoice.PLUS_3_DAYS))
        val (second) = start(handle)
        assertEquals(ExpiryChoice.PLUS_3_DAYS, second.uiState.value.rows.first { it.item.shoppingEntryId == butter }.expiry)
    }

    @Test
    fun aGuestCannotUnpack() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val (vm) = start()
        assertFalse(vm.uiState.value.canSave)
        vm.onAction(UnpackAction.Save)
        vm.onAction(UnpackAction.Skip)
        advanceUntilIdle()
        assertEquals(2, pantry.unpackable.value.size)
    }
}
