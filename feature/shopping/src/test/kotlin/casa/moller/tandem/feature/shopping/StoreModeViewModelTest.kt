package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.shopping.ShoppingSamples.eggs
import casa.moller.tandem.feature.shopping.ShoppingSamples.milk
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
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

    private fun viewModel(
        key: StoreModeKey = StoreModeKey(supermarketId = ShoppingSamples.store.id),
        saved: SavedStateHandle = SavedStateHandle(),
    ) = StoreModeViewModel(
        key = key,
        savedStateHandle = saved,
        source = ShoppingSource(shopping, supermarkets, permissions, foreground, FakeSyncRepository(), FakePreferencesRepository(), clock),
        shopping = shopping,
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
