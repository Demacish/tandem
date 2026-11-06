package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.shopping.StoreTrip
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeStoreTripRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.shopping.ShoppingSamples.eggs
import casa.moller.tandem.feature.shopping.ShoppingSamples.milk
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StoreModeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(ShoppingSamples.now)
    private val shopping = FakeShoppingRepository(entries = ShoppingSamples.entries, lists = ShoppingSamples.lists)
    private val supermarkets = FakeSupermarketRepository(ShoppingSamples.supermarkets, ShoppingSamples.categories)
    private val permissions = FakeSpacePermissionsRepository()
    private val foreground = FakeForegroundSync()
    private val trips = FakeStoreTripRepository()

    private fun viewModel(
        key: StoreModeKey = StoreModeKey(supermarketId = ShoppingSamples.store.id),
        saved: SavedStateHandle = SavedStateHandle(),
    ) = StoreModeViewModel(
        key = key,
        savedStateHandle = saved,
        source = ShoppingSource(shopping, supermarkets, permissions, foreground, FakeSyncRepository(), FakePreferencesRepository(), clock),
        shopping = shopping,
        supermarkets = supermarkets,
        trips = trips,
        clock = clock,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: StoreModeViewModel): MutableList<ShoppingEffect> {
        val effects = mutableListOf<ShoppingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    @Test
    fun showsOnlyWhatIsLeftToBuyInTheStoresOrder() = runTest {
        val vm = viewModel()
        open(vm)
        val state = vm.uiState.value
        assertEquals(listOf("Frukt & grönt", "Skafferi", "Mejeri", null), state.sections.map { it.name })
        val shown = state.sections.flatMap { it.entries }
        assertFalse(eggs in shown)
        assertFalse(ShoppingSamples.coffee in shown)
        // The eggs were checked an hour before store mode opened: not in this trip's cart (fix-ui).
        assertEquals(7 to 7, state.toBuy to state.total)
        assertEquals(0, state.inCart)
        assertEquals(listOf<ForegroundTarget>(ForegroundTarget.ShoppingList), foreground.started)
    }

    @Test
    fun aNamedListLimitsTheEntries() = runTest {
        val vm = viewModel(StoreModeKey(listId = 1))
        open(vm)
        assertEquals(listOf(milk), vm.uiState.value.sections.flatMap { it.entries })
    }

    @Test
    fun checkingPutsItInTheCartWithUndo() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(StoreModeAction.Check(milk.id))
        assertTrue(shopping.entries.value.first { it.id == milk.id }.checked)
        assertFalse(milk in vm.uiState.value.sections.flatMap { it.entries })
        assertEquals(1, vm.uiState.value.inCart)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Checked("mjölk", 1), undoable = true), effects.last())

        vm.onAction(StoreModeAction.Undo)
        assertFalse(shopping.entries.value.first { it.id == milk.id }.checked)
    }

    @Test
    fun onlyChecksSinceStoreModeOpenedCountAndTheTimeSurvivesProcessDeath() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved = saved)
        open(vm)
        clock.advanceBy(10.minutes)
        // A partner checks something during the trip (the server's completed_at): it counts too.
        shopping.entries.update { list ->
            list.map { if (it.id == milk.id) it.copy(checked = true, completedAt = clock.now()) else it }
        }
        assertEquals(1, vm.uiState.value.inCart)
        assertEquals(7, vm.uiState.value.total)

        clock.advanceBy(1.hours)
        val again = viewModel(saved = saved)
        open(again)
        assertEquals("store mode keeps the time it opened", 1, again.uiState.value.inCart)
    }

    // Fas 4 "Inlärd butiksordning".

    private fun aisleNames(): List<String> = supermarkets.supermarkets.value.first().aisles.sortedBy { it.order }.map { it.category.name }

    /** Two earlier trips in the test store that walked [categories] (newest first, before this one). */
    private fun earlierTrips(vararg categories: Long) {
        trips.trips.value = (1..2).map { ago ->
            StoreTrip(ShoppingSamples.store.id, (ShoppingSamples.now - ago.days).toEpochMilliseconds(), categories.toList())
        }
    }

    @Test
    fun afterThreeTripsInTheSameOrderTheOrderIsOfferedAndSavingReordersTheStore() = runTest {
        earlierTrips(2, 3, 1) // Mejeri, Skafferi, Frukt & grönt: twice before.
        val saved = SavedStateHandle()
        val vm = viewModel(saved = saved)
        val effects = open(vm)
        assertNull("this trip has walked nothing yet", vm.uiState.value.learnedOrder)
        vm.onAction(StoreModeAction.Check(milk.id)) // Mejeri (third aisle) first…
        assertNull("one aisle says nothing", vm.uiState.value.learnedOrder)
        vm.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id)) // …then Skafferi.
        val order = vm.uiState.value.learnedOrder!!
        assertEquals(listOf("Mejeri", "Skafferi", "Frukt & grönt"), order.names)
        assertEquals(listOf(13L, 12L, 11L), order.relationIds)
        assertEquals(3, order.trips)
        // This trip is recorded (once, as it grows) for the next ones.
        assertEquals(listOf(2L, 3L), trips.trips.value.single { it.startedAt == ShoppingSamples.now.toEpochMilliseconds() }.categoryIds)

        // The walked order survives process death with the trip, and its own record is not counted twice.
        val again = viewModel(saved = saved)
        open(again)
        assertEquals(order, again.uiState.value.learnedOrder)

        vm.onAction(StoreModeAction.SaveOrder)
        assertEquals(listOf("Mejeri", "Skafferi", "Frukt & grönt"), aisleNames())
        assertEquals(ShoppingEffect.Show(ShoppingMessage.OrderSaved("Testbutiken"), undoable = true), effects.last())
        assertNull("saved: nothing more to offer", vm.uiState.value.learnedOrder)
    }

    @Test
    fun undoPutsTheHouseholdsEarlierOrderBack() = runTest {
        earlierTrips(2, 3, 1)
        val vm = viewModel()
        open(vm)
        val before = aisleNames()
        vm.onAction(StoreModeAction.Check(milk.id))
        vm.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id))
        vm.onAction(StoreModeAction.SaveOrder)
        assertEquals(listOf("Mejeri", "Skafferi", "Frukt & grönt"), aisleNames())

        vm.onAction(StoreModeAction.Undo)
        assertEquals(before, aisleNames())
    }

    @Test
    fun leavingAndReopeningDuringOneVisitIsOneTrip() = runTest {
        val first = viewModel()
        open(first)
        first.onAction(StoreModeAction.Check(milk.id)) // Mejeri.
        first.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id)) // Skafferi.

        // Left to answer a call: a new store mode entry within the same visit.
        clock.advanceBy(20.minutes)
        val second = viewModel()
        open(second)
        second.onAction(StoreModeAction.Check(ShoppingSamples.onions.id)) // Frukt & grönt.

        clock.advanceBy(20.minutes)
        val third = viewModel()
        open(third)
        third.onAction(StoreModeAction.Check(ShoppingSamples.cream.id))
        assertEquals(listOf(listOf(2L, 3L, 1L)), trips.trips.value.map { it.categoryIds })
        assertNull("the pieces of one visit agree with each other, but they are not three trips", third.uiState.value.learnedOrder)
    }

    @Test
    fun aVisitHoursLaterIsANewTrip() = runTest {
        val first = viewModel()
        open(first)
        first.onAction(StoreModeAction.Check(milk.id))
        first.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id))

        clock.advanceBy(5.hours)
        val second = viewModel()
        open(second)
        second.onAction(StoreModeAction.Check(ShoppingSamples.cream.id))
        second.onAction(StoreModeAction.Check(ShoppingSamples.potato.id))
        assertEquals(2, trips.trips.value.size)
    }

    @Test
    fun aContinuedTripSurvivesProcessDeath() = runTest {
        val first = viewModel()
        open(first)
        first.onAction(StoreModeAction.Check(milk.id))
        first.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id))

        clock.advanceBy(10.minutes)
        val saved = SavedStateHandle()
        val second = viewModel(saved = saved)
        open(second)
        second.onAction(StoreModeAction.Check(ShoppingSamples.onions.id))

        val restored = viewModel(saved = saved)
        open(restored)
        restored.onAction(StoreModeAction.Check(ShoppingSamples.cream.id))
        assertEquals(listOf(listOf(2L, 3L, 1L)), trips.trips.value.map { it.categoryIds })
    }

    @Test
    fun undoneCheckLeavesNoTraceOfItsAisle() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(StoreModeAction.Check(milk.id)) // Mejeri.
        vm.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id)) // Skafferi: recorded now…
        vm.onAction(StoreModeAction.Undo) // …and it was a mis-tap.
        assertEquals("the stale two-aisle copy is replaced", listOf(listOf(2L)), trips.trips.value.map { it.categoryIds })

        vm.onAction(StoreModeAction.Check(ShoppingSamples.onions.id)) // Frukt & grönt.
        vm.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id)) // Skafferi, at its true place.
        assertEquals(listOf(listOf(2L, 1L, 3L)), trips.trips.value.map { it.categoryIds })
    }

    @Test
    fun undoKeepsAnAisleThatHasAnEarlierCheck() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(StoreModeAction.Check(milk.id)) // Mejeri.
        vm.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id)) // Skafferi.
        vm.onAction(StoreModeAction.Check(ShoppingSamples.cream.id)) // Mejeri again.
        vm.onAction(StoreModeAction.Undo)
        assertEquals(listOf(listOf(2L, 3L)), trips.trips.value.map { it.categoryIds })
    }

    @Test
    fun oneOrTwoTripsAreNotEnoughAndEveryTripIsRecorded() = runTest {
        val first = viewModel()
        open(first)
        first.onAction(StoreModeAction.Check(milk.id))
        first.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id))
        assertNull("the first trip", first.uiState.value.learnedOrder)

        clock.advanceBy(7.days)
        val second = viewModel()
        open(second)
        second.onAction(StoreModeAction.Check(ShoppingSamples.cream.id))
        second.onAction(StoreModeAction.Check(ShoppingSamples.potato.id))
        assertNull("the second trip", second.uiState.value.learnedOrder)
        assertEquals(listOf(listOf(2L, 1L), listOf(2L, 3L)), trips.trips.value.map { it.categoryIds })
    }

    @Test
    fun walkingTheStoresOwnOrderOffersNothingAndNoThanksHidesTheOffer() = runTest {
        earlierTrips(1, 3, 2)
        val vm = viewModel()
        open(vm)
        vm.onAction(StoreModeAction.Check(ShoppingSamples.onions.id)) // Frukt & grönt, the first aisle.
        vm.onAction(StoreModeAction.Check(ShoppingSamples.tomatoes.id)) // Skafferi, the second.
        assertNull(vm.uiState.value.learnedOrder)
        vm.onAction(StoreModeAction.Check(milk.id))
        assertNull("the whole store's order", vm.uiState.value.learnedOrder)
    }

    @Test
    fun noThanksHidesTheOfferAndSendsNothing() = runTest {
        earlierTrips(2, 1, 3)
        val other = viewModel()
        open(other)
        other.onAction(StoreModeAction.Check(ShoppingSamples.cream.id))
        other.onAction(StoreModeAction.Check(ShoppingSamples.potato.id))
        assertEquals(listOf("Mejeri", "Frukt & grönt", "Skafferi"), other.uiState.value.learnedOrder?.names)
        other.onAction(StoreModeAction.DismissOrder)
        assertNull(other.uiState.value.learnedOrder)
        assertEquals("nothing sent", listOf("Frukt & grönt", "Skafferi", "Mejeri"), aisleNames())
    }

    @Test
    fun aGuestCannotCheck() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val vm = viewModel()
        open(vm)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(StoreModeAction.Check(milk.id))
        assertFalse(shopping.entries.value.first { it.id == milk.id }.checked)
    }
}
