package casa.moller.tandem.feature.shopping.storereminder

import casa.moller.tandem.core.model.shopping.CurrentLocationResult
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.storereminder.StoreReminderSamples.OTHER_STORE
import casa.moller.tandem.feature.shopping.storereminder.StoreReminderSamples.STORE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 5 (butik lane, plan §7 #34, §8.7): the permissions are asked in context and in order (the
 * precise location, then "Tillåt alltid" after an explanation, then notifications) and only then is
 * the reminder turned on; a no leaves it off with the card; the position needs only the location;
 * the sheet saves a pasted place, the distance and the store's own switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreReminderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeStoreReminderRepository()
    private val supermarkets = FakeSupermarketRepository(
        supermarkets = listOf(Supermarket(OTHER_STORE, "Hörnbutiken"), Supermarket(STORE, "Testbutiken")),
    )

    private fun TestScope.start(): Pair<StoreReminderViewModel, MutableList<StoreReminderEffect>> {
        val viewModel = StoreReminderViewModel(repository, supermarkets)
        val effects = mutableListOf<StoreReminderEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effect.toList(effects) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel to effects
    }

    @Test
    fun theStoresAreListedByNameWithTheirPlaces() = runTest {
        repository.state.value = StoreReminderSamples.on
        val (viewModel, _) = start()
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("Hörnbutiken", "Testbutiken"), state.stores.map { it.name })
        assertEquals(350, state.stores.first().spot?.radiusMeters)
        assertTrue(state.enabled)
    }

    @Test
    fun turningOnAsksForEachPermissionInOrderAndOnlyThenTurnsOn() = runTest {
        repository.permissions = StoreReminderPermissions()
        val (viewModel, effects) = start()

        viewModel.onAction(StoreReminderAction.SetEnabled(true))
        advanceUntilIdle()
        assertEquals(StoreReminderEffect.RequestPermission(PermissionStep.LOCATION), effects.single())

        repository.permissions = StoreReminderPermissions(precise = true)
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.LOCATION, granted = true))
        advanceUntilIdle()
        assertTrue("The explanation comes before Android's settings page", viewModel.uiState.value.rationale)
        assertEquals(1, effects.size)

        viewModel.onAction(StoreReminderAction.ContinueToBackground)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.rationale)
        assertEquals(StoreReminderEffect.RequestPermission(PermissionStep.BACKGROUND), effects.last())

        repository.permissions = StoreReminderPermissions(precise = true, background = true)
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.BACKGROUND, granted = true))
        advanceUntilIdle()
        assertEquals(StoreReminderEffect.RequestPermission(PermissionStep.NOTIFICATIONS), effects.last())
        assertTrue(repository.calls.none { it.startsWith("enabled") })

        repository.permissions = StoreReminderSamples.allowed
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.NOTIFICATIONS, granted = true))
        advanceUntilIdle()
        assertTrue("enabled true" in repository.calls)
        assertTrue(viewModel.uiState.value.enabled)
        assertEquals(StoreReminderEffect.Message(R.string.store_reminder_msg_on), effects.last())
    }

    @Test
    fun aNoLeavesItOffWithTheCard() = runTest {
        repository.permissions = StoreReminderPermissions(precise = true)
        val (viewModel, effects) = start()
        viewModel.onAction(StoreReminderAction.SetEnabled(true))
        viewModel.onAction(StoreReminderAction.ContinueToBackground)
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.BACKGROUND, granted = false))
        advanceUntilIdle()
        assertEquals(PermissionStep.BACKGROUND, viewModel.uiState.value.denied)
        assertFalse(viewModel.uiState.value.enabled)
        assertTrue(repository.calls.none { it.startsWith("enabled") })
        assertEquals(StoreReminderEffect.RequestPermission(PermissionStep.BACKGROUND), effects.last())

        // "Not now" in the explanation: nothing asked, nothing on.
        viewModel.onAction(StoreReminderAction.SetEnabled(true))
        viewModel.onAction(StoreReminderAction.DismissRationale)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.rationale)
        assertFalse(viewModel.uiState.value.enabled)
    }

    @Test
    fun withEverythingAllowedItTurnsOnAtOnceAndOffAgain() = runTest {
        val (viewModel, effects) = start()
        viewModel.onAction(StoreReminderAction.SetEnabled(true))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.enabled)
        assertTrue(effects.none { it is StoreReminderEffect.RequestPermission })
        viewModel.onAction(StoreReminderAction.SetEnabled(false))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.enabled)
        assertEquals(StoreReminderEffect.Message(R.string.store_reminder_msg_off), effects.last())
    }

    @Test
    fun thePositionNeedsOnlyTheLocation() = runTest {
        repository.permissions = StoreReminderPermissions()
        val (viewModel, effects) = start()
        viewModel.onAction(StoreReminderAction.OpenStore(STORE))
        viewModel.onAction(StoreReminderAction.UseCurrentLocation)
        advanceUntilIdle()
        assertEquals(StoreReminderEffect.RequestPermission(PermissionStep.LOCATION), effects.single())

        repository.permissions = StoreReminderPermissions(precise = true)
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.LOCATION, granted = true))
        advanceUntilIdle()
        assertTrue("locate $STORE" in repository.calls)
        assertFalse("No background question for a position", viewModel.uiState.value.rationale)
        assertEquals(StoreReminderSamples.point, viewModel.uiState.value.sheet?.spot?.point)
        // Saved while the reminder is off: the snackbar says to turn it on.
        assertEquals(StoreReminderEffect.Message(R.string.store_reminder_msg_saved_turn_on), effects.last())
    }

    @Test
    fun aPositionThatDoesNotComeSaysSo() = runTest {
        repository.state.value = StoreReminderSamples.on
        repository.location = CurrentLocationResult.Unavailable
        val (viewModel, effects) = start()
        viewModel.onAction(StoreReminderAction.OpenStore(OTHER_STORE))
        viewModel.onAction(StoreReminderAction.UseCurrentLocation)
        advanceUntilIdle()
        assertEquals(StoreReminderEffect.Message(R.string.store_reminder_msg_no_position), effects.last())
        assertFalse(viewModel.uiState.value.sheet?.locating ?: true)
    }

    @Test
    fun thePastedPlaceTheDistanceAndTheStoresSwitch() = runTest {
        repository.state.value = StoreReminderSamples.on
        val (viewModel, effects) = start()
        viewModel.onAction(StoreReminderAction.OpenStore(OTHER_STORE))
        viewModel.onAction(StoreReminderAction.PasteChanged("no place here"))
        viewModel.onAction(StoreReminderAction.SavePasted)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sheet!!.pasteError)

        viewModel.onAction(StoreReminderAction.PasteChanged("geo:12.3456,-45.6789"))
        assertFalse(viewModel.uiState.value.sheet!!.pasteError)
        viewModel.onAction(StoreReminderAction.SavePasted)
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.sheet!!.pasteText)
        assertEquals(StoreReminderEffect.Message(R.string.store_reminder_msg_saved), effects.last())

        viewModel.onAction(StoreReminderAction.RadiusChanged(412f))
        assertEquals("Shown at once", 400, viewModel.uiState.value.sheet!!.radius)
        viewModel.onAction(StoreReminderAction.RadiusDone)
        advanceUntilIdle()
        assertEquals(400, repository.state.value.spots.getValue(OTHER_STORE).radiusMeters)

        viewModel.onAction(StoreReminderAction.SetStoreEnabled(false))
        advanceUntilIdle()
        assertFalse(repository.state.value.spots.getValue(OTHER_STORE).enabled)

        viewModel.onAction(StoreReminderAction.ForgetPlace)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.sheet?.spot)
        assertEquals(StoreReminderEffect.Message(R.string.store_reminder_msg_forgotten), effects.last())

        viewModel.onAction(StoreReminderAction.CloseStore)
        assertNull(viewModel.uiState.value.sheet)
    }

    @Test
    fun comingBackReadsThePermissionsAgain() = runTest {
        val (viewModel, _) = start()
        viewModel.onAction(StoreReminderAction.Resumed)
        assertEquals(listOf("refresh"), repository.calls)
    }
}
