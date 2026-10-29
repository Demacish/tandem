package casa.moller.tandem.feature.pantry.hemma

import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.Staple
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeAiSettingsRepository
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeStaplesRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.feature.pantry.PantrySamples
import casa.moller.tandem.feature.pantry.PantrySyncNotice
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

/**
 * Hemma (Fas S, hemma-ui lane, plan §8.8): grouping by place, expiry colours from the device's day
 * (also across midnight), "Slut", "−1" and "Flytta" with "Ångra", "Har hemma", places, guests,
 * offline and the fridge photo only when set up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pantry = FakePantryRepository(locations = PantrySamples.locations, entries = PantrySamples.entries)
    private val staples = FakeStaplesRepository(listOf(Staple("salt", 201, onHand = true), Staple("olja", 203, onHand = false)))
    private val supermarkets = FakeSupermarketRepository()
    private val permissions = FakeSpacePermissionsRepository()
    private val sync = FakeSyncRepository()
    private val ai = FakeAiSettingsRepository()
    private val calendar = FakeCalendarDays(PantrySamples.today)

    private fun viewModel(locationId: Long? = null) =
        PantryViewModel(locationId, pantry, staples, supermarkets, permissions, sync, ai, calendar)

    private fun TestScope.start(vm: PantryViewModel): MutableList<PantryEffect> {
        val effects = mutableListOf<PantryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        return effects
    }

    @Test
    fun entriesAreGroupedByPlaceSoonestFirstAndEmptyPlacesStay() = runTest {
        val vm = viewModel()
        start(vm)
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf("Kyl", "Frys", "Skafferi", "Jordkällaren"), state.sections.map { it.location.name })
        val fridge = state.sections.first().rows.map { it.entry.food.name }
        assertEquals(listOf("köttfärssås", "grädde", "turkisk yoghurt", "mjölk"), fridge)
        assertTrue("an empty place is kept, so it can be filled", state.sections.last().rows.isEmpty())
        assertEquals("Kyl", state.sections.first().rows.first().locationName)
    }

    @Test
    fun openingHemmaRefreshesThePantryOnce() = runTest {
        // hemma-data contract: refresh() on open drains, reads and creates the default places.
        val vm = viewModel()
        start(vm)
        assertEquals(1, pantry.refreshes)
    }

    @Test
    fun expiryColoursFollowTheDeviceDayAcrossMidnight() = runTest {
        val vm = viewModel()
        start(vm)
        fun urgency(name: String) = vm.uiState.value.sections.flatMap { it.rows }.first { it.entry.food.name == name }.urgency
        assertEquals(ExpiryUrgency.EXPIRED, urgency("köttfärssås"))
        assertEquals(ExpiryUrgency.SOON, urgency("grädde"))
        assertEquals(ExpiryUrgency.LATER, urgency("mjölk"))
        assertEquals(ExpiryUrgency.NONE, urgency("pasta"))
        assertEquals(3, vm.uiState.value.expiringCount)

        // Past midnight: the cream's last day is today, the milk is within three days a few days later.
        calendar.day.value = LocalDate(2026, 10, 30)
        advanceUntilIdle()
        assertEquals(ExpiryUrgency.TODAY, urgency("grädde"))
        assertEquals(LocalDate(2026, 10, 30), vm.uiState.value.today)
        calendar.day.value = LocalDate(2026, 11, 1)
        advanceUntilIdle()
        assertEquals(ExpiryUrgency.EXPIRED, urgency("grädde"))
        assertEquals(ExpiryUrgency.SOON, urgency("mjölk"))
    }

    @Test
    fun usedUpLeavesThePantryAndUndoPutsTheAmountBack() = runTest {
        val vm = viewModel()
        val effects = start(vm)
        vm.onAction(PantryAction.MarkEmpty(PantrySamples.cream.id))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sections.flatMap { it.rows }.none { it.entry.id == PantrySamples.cream.id })
        assertEquals(PantryEffect.Show(PantryMessage.MarkedEmpty("grädde"), undoable = true), effects.last())

        vm.onAction(PantryAction.Undo)
        advanceUntilIdle()
        val back = pantry.entries.value.first { it.id == PantrySamples.cream.id }
        assertEquals(3.0, back.amount, 0.0)
        assertTrue(vm.uiState.value.sections.flatMap { it.rows }.any { it.entry.id == PantrySamples.cream.id })
    }

    @Test
    fun minusOneSaysWhatIsLeftAndCanBeUndone() = runTest {
        val vm = viewModel()
        val effects = start(vm)
        vm.onAction(PantryAction.Decrement(PantrySamples.cream.id))
        advanceUntilIdle()
        assertEquals(2.0, pantry.entries.value.first { it.id == PantrySamples.cream.id }.amount, 0.0)
        assertEquals(PantryEffect.Show(PantryMessage.Decremented("grädde", 2.0, "dl"), undoable = true), effects.last())
        vm.onAction(PantryAction.Undo)
        advanceUntilIdle()
        assertEquals(3.0, pantry.entries.value.first { it.id == PantrySamples.cream.id }.amount, 0.0)
        // One undo per change.
        vm.onAction(PantryAction.Undo)
        advanceUntilIdle()
        assertEquals(3.0, pantry.entries.value.first { it.id == PantrySamples.cream.id }.amount, 0.0)
    }

    @Test
    fun moveAsksForThePlaceMovesAndUndoes() = runTest {
        val vm = viewModel()
        val effects = start(vm)
        vm.onAction(PantryAction.StartMove(PantrySamples.cream.id))
        assertEquals(PantrySamples.cream.id, vm.uiState.value.moving?.entry?.id)
        vm.onAction(PantryAction.MoveTo(PantrySamples.freezer.id))
        advanceUntilIdle()
        assertNull(vm.uiState.value.moving)
        assertEquals(PantrySamples.freezer.id, pantry.entries.value.first { it.id == PantrySamples.cream.id }.locationId)
        assertEquals(PantryEffect.Show(PantryMessage.Moved("grädde", "Frys"), undoable = true), effects.last())
        vm.onAction(PantryAction.Undo)
        advanceUntilIdle()
        assertEquals(PantrySamples.fridge.id, pantry.entries.value.first { it.id == PantrySamples.cream.id }.locationId)
    }

    @Test
    fun onHandListsFoodsWithoutStockAndTheStaplesAndCanBeTakenOff() = runTest {
        pantry.onHandFoods.value = listOf(PantryFood(301, "honung"), PantryFood(101, "grädde"), PantryFood(201, "salt"))
        val vm = viewModel()
        start(vm)
        // Cream is stocked, so it is not repeated; salt is a staple; oil is a staple not at home.
        assertEquals(listOf("honung", "salt"), vm.uiState.value.onHand.map { it.name })
        assertTrue(vm.uiState.value.onHand.first { it.name == "salt" }.staple)

        vm.onAction(PantryAction.RemoveOnHand(vm.uiState.value.onHand.first { it.name == "honung" }))
        advanceUntilIdle()
        assertEquals(false, supermarkets.foodsOnHand[301L])
        vm.onAction(PantryAction.Undo)
        advanceUntilIdle()
        assertEquals(true, supermarkets.foodsOnHand[301L])

        vm.onAction(PantryAction.RemoveOnHand(vm.uiState.value.onHand.first { it.name == "salt" }))
        advanceUntilIdle()
        assertFalse(staples.staples.value.first { it.name == "salt" }.onHand)
    }

    @Test
    fun aPlacePageShowsOnlyThatPlace() = runTest {
        val vm = viewModel(locationId = PantrySamples.freezer.id)
        start(vm)
        val state = vm.uiState.value
        assertEquals("Frys", state.location?.name)
        assertEquals(listOf("blåbär"), state.sections.single().rows.map { it.entry.food.name })
        assertTrue(state.onHand.isEmpty())
    }

    @Test
    fun aNewTandoorGetsTheUsualPlacesInOneTap() = runTest {
        pantry.locations.value = emptyList()
        pantry.entries.value = emptyList()
        val vm = viewModel()
        val effects = start(vm)
        assertTrue(vm.uiState.value.noLocations)
        vm.onAction(PantryAction.CreateDefaultLocations(listOf("Kyl" to false, "Frys" to true, "Skafferi" to false)))
        advanceUntilIdle()
        assertEquals(listOf("Kyl", "Frys", "Skafferi"), pantry.locations.value.map { it.name })
        assertTrue(pantry.locations.value.first { it.name == "Frys" }.isFreezer)
        assertEquals(PantryEffect.Show(PantryMessage.LocationsCreated(3)), effects.last())
        assertFalse(vm.uiState.value.noLocations)
    }

    @Test
    fun placesAreAddedRenamedAndRemovedAfterAsking() = runTest {
        val vm = viewModel()
        start(vm)
        vm.onAction(PantryAction.EditLocation(null))
        vm.onAction(PantryAction.LocationName("  Vinkyl "))
        vm.onAction(PantryAction.SaveLocation)
        advanceUntilIdle()
        assertTrue(pantry.locations.value.any { it.name == "Vinkyl" })

        vm.onAction(PantryAction.EditLocation(PantrySamples.cellar.id))
        assertEquals("Jordkällaren", vm.uiState.value.locationEditor?.name)
        vm.onAction(PantryAction.LocationName("Källaren"))
        vm.onAction(PantryAction.SaveLocation)
        advanceUntilIdle()
        assertTrue(pantry.locations.value.any { it.name == "Källaren" })

        vm.onAction(PantryAction.AskDeleteLocation(PantrySamples.fridge.id))
        assertEquals("Kyl", vm.uiState.value.confirmDelete?.name)
        vm.onAction(PantryAction.ConfirmDeleteLocation(false))
        advanceUntilIdle()
        assertTrue(pantry.locations.value.any { it.id == PantrySamples.fridge.id })
        vm.onAction(PantryAction.AskDeleteLocation(PantrySamples.fridge.id))
        vm.onAction(PantryAction.ConfirmDeleteLocation(true))
        advanceUntilIdle()
        assertTrue(pantry.locations.value.none { it.id == PantrySamples.fridge.id })
        assertTrue("its entries went with it", pantry.entries.value.none { it.locationId == PantrySamples.fridge.id })
    }

    @Test
    fun aGuestSeesThePantryButChangesNothing() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val vm = viewModel()
        start(vm)
        assertTrue(vm.uiState.value.readOnly)
        assertEquals(0, vm.uiState.value.unpackCount)
        vm.onAction(PantryAction.MarkEmpty(PantrySamples.cream.id))
        vm.onAction(PantryAction.StartMove(PantrySamples.cream.id))
        vm.onAction(PantryAction.EditLocation(null))
        advanceUntilIdle()
        assertEquals(3.0, pantry.entries.value.first { it.id == PantrySamples.cream.id }.amount, 0.0)
        assertNull(vm.uiState.value.moving)
        assertNull(vm.uiState.value.locationEditor)
    }

    @Test
    fun offlineChangesWaitAndAreCounted() = runTest {
        sync.setStatus(SyncStatus(pause = SyncPause.OFFLINE))
        val vm = viewModel()
        start(vm)
        assertEquals(PantrySyncNotice.OFFLINE, vm.uiState.value.syncNotice)
        assertEquals("the yoghurt made offline waits", 1, vm.uiState.value.pendingCount)
    }

    @Test
    fun theUnpackPromptCountsTodaysCheckedItems() = runTest {
        val vm = viewModel()
        start(vm)
        assertEquals(2, vm.uiState.value.unpackCount)
    }

    @Test
    fun theFridgePhotoIsOfferedOnlyWhenSetUpAndNotInDemoMode() = runTest {
        val vm = viewModel()
        start(vm)
        assertFalse(vm.uiState.value.fridgePhoto)
        ai.storeKey(AiProvider.CLAUDE, "sk-test-not-a-real-key")
        ai.setProvider(AiProvider.CLAUDE)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.fridgePhoto)
        permissions.state.value = SpacePermissions(SpaceRole.USER, isDemo = true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.fridgePhoto)
    }

    @Test
    fun aFailedWriteIsShown() = runTest {
        pantry.failure = TandemError.Unknown("broken")
        val vm = viewModel()
        val effects = start(vm)
        vm.onAction(PantryAction.MarkEmpty(PantrySamples.cream.id))
        advanceUntilIdle()
        assertTrue(effects.last() is PantryEffect.Failed)
    }
}
