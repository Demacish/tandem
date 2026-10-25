package casa.moller.tandem.feature.shopping

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
import kotlinx.coroutines.flow.toList
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

    private fun viewModel(key: StoreModeKey = StoreModeKey(supermarketId = ShoppingSamples.store.id)) = StoreModeViewModel(
        key = key,
        source = ShoppingSource(shopping, supermarkets, permissions, foreground, FakeSyncRepository(), FakePreferencesRepository(), clock),
        shopping = shopping,
    )

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
        assertEquals(7 to 8, state.toBuy to state.total)
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
        assertEquals(2, vm.uiState.value.inCart)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Checked("mjölk", 1), undoable = true), effects.last())

        vm.onAction(StoreModeAction.Undo)
        assertFalse(shopping.entries.value.first { it.id == milk.id }.checked)
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
