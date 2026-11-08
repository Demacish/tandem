package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.shopping.UnitConversions
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeIngredientParser
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeShoppingViewPreferences
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeStoreTripRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.FakeUnitConversionRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.shopping.ShoppingSamples.cream
import casa.moller.tandem.feature.shopping.ShoppingSamples.creamCarton
import casa.moller.tandem.feature.shopping.ShoppingSamples.creamGrams
import casa.moller.tandem.feature.shopping.ShoppingSamples.eggs
import casa.moller.tandem.feature.shopping.ShoppingSamples.eggsMore
import casa.moller.tandem.feature.shopping.ShoppingSamples.milk
import casa.moller.tandem.feature.shopping.ShoppingSamples.milkMore
import casa.moller.tandem.feature.shopping.navigation.ShoppingListKey
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
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

/**
 * Fas 5, lane handla (plan §8.2 "Sammanslagning i vyn"): the list shows the same food in
 * convertible units as one row; checking it, "köp senare" and their undo act on every entry
 * behind it (the repository queues one BULK_CHECK per entry, `MergedRowSyncTest` in core:sync);
 * the grid is remembered per account.
 */
class MergedRowsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(ShoppingSamples.now)
    private val shopping = FakeShoppingRepository(entries = ShoppingSamples.mergedEntries, lists = ShoppingSamples.lists)
    private val supermarkets = FakeSupermarketRepository(ShoppingSamples.supermarkets, ShoppingSamples.categories).apply {
        selected.value = ShoppingSamples.store.id
    }
    private val permissions = FakeSpacePermissionsRepository()
    private val conversions = FakeUnitConversionRepository(ShoppingSamples.conversions)
    private val view = FakeShoppingViewPreferences()

    private fun source() = ShoppingSource(
        shopping,
        supermarkets,
        permissions,
        FakeForegroundSync(),
        FakeSyncRepository(),
        FakePreferencesRepository(UserPreferences(defaultDelayHours = 4.0)),
        clock,
        conversions,
    )

    private fun viewModel() = ShoppingViewModel(
        key = ShoppingListKey(),
        savedStateHandle = SavedStateHandle(),
        source = source(),
        shopping = shopping,
        supermarkets = supermarkets,
        parser = FakeIngredientParser(),
        clock = clock,
        viewPreferences = view,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: ShoppingViewModel): MutableList<ShoppingEffect> {
        val effects = mutableListOf<ShoppingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    private fun checked(vararg ids: Long) = ids.map { id -> shopping.entries.value.first { it.id == id }.checked }

    private fun delays() = listOf(milk.id, milkMore.id).map { id -> shopping.entries.value.first { it.id == id }.delayUntil }

    private fun dairy(vm: ShoppingViewModel) = vm.uiState.value.grouped.sections.first { it.name == "Mejeri" }

    @Test
    fun theSameFoodInConvertibleUnitsIsOneRow() = runTest {
        val vm = viewModel()
        open(vm)
        val rows = dairy(vm).rows
        val format = ShoppingFormat(Locale.forLanguageTag("sv"))
        assertEquals(listOf("5 dl + 1 st grädde", "3 l mjölk"), rows.map(format::line))
        assertEquals(listOf(cream.id, creamGrams.id, creamCarton.id), rows.first().ids)
        // The section counts rows; the server's entries stay separate (all of them are there).
        assertEquals(2, rows.size)
        assertEquals(5, dairy(vm).entries.size)
        // The checked eggs (6 + 6 st) are one row in "Avbockat"; the progress counts rows.
        assertEquals(listOf(listOf(eggs.id, eggsMore.id).sorted()), vm.uiState.value.grouped.checkedRows.map { it.ids.sorted() })
        assertEquals(1, vm.uiState.value.progressDone)
    }

    @Test
    fun withoutConversionsOnlyTheSameUnitMerges() = runTest {
        conversions.conversions.value = UnitConversions.NONE
        val vm = viewModel()
        open(vm)
        val format = ShoppingFormat(Locale.forLanguageTag("sv"))
        assertEquals(listOf("3 dl + 200 g + 1 st grädde", "3 l mjölk"), dairy(vm).rows.map(format::line))
        // New conversions (a sync) merge at once.
        conversions.conversions.value = ShoppingSamples.conversions
        assertEquals("5 dl + 1 st grädde", format.line(dairy(vm).rows.first()))
    }

    @Test
    fun checkingAMergedRowChecksEveryEntryAndUndoUnchecksThemAll() = runTest {
        val vm = viewModel()
        val effects = open(vm)

        // Any id of the row works (the screen sends the row's id, its first entry's).
        vm.onAction(ShoppingAction.ToggleChecked(creamGrams.id))
        assertEquals(listOf(true, true, true), checked(cream.id, creamGrams.id, creamCarton.id))
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Checked("grädde", 1), undoable = true), effects.last())
        assertEquals(listOf(cream.id, creamGrams.id, creamCarton.id), vm.uiState.value.grouped.checkedRows.first { cream.id in it }.ids)

        vm.onAction(ShoppingAction.Undo)
        assertEquals(listOf(false, false, false), checked(cream.id, creamGrams.id, creamCarton.id))
        // Nothing else was touched.
        assertEquals(listOf(false, false), checked(milk.id, milkMore.id))
    }

    @Test
    fun uncheckingAMergedCheckedRowUnchecksAllOfIt() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        val row = vm.uiState.value.grouped.checkedRows.single()

        vm.onAction(ShoppingAction.ToggleChecked(row.id))
        assertEquals(listOf(false, false), checked(eggs.id, eggsMore.id))
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Unchecked("ägg"), undoable = true), effects.last())
        // Back in its aisle, still one row.
        assertEquals(listOf(eggs.id, eggsMore.id).sorted(), dairy(vm).rows.first { eggs.id in it }.ids.sorted())
    }

    @Test
    fun buyLaterDelaysTheWholeRowAndUndoRestoresEachTime() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ShoppingAction.ToggleLater(milk.id))
        val until = ShoppingSamples.now + 4.hours
        assertEquals(listOf(until, until), delays())
        assertEquals(listOf(milk.id, milkMore.id), vm.uiState.value.grouped.laterRows.first { milk.id in it }.ids)

        // Delayed to different times: "köp nu" brings both back, undo puts each back to its own.
        val later = ShoppingSamples.now + 2.days
        shopping.entries.value = shopping.entries.value.map { if (it.id == milkMore.id) it.copy(delayUntil = later) else it }
        vm.onAction(ShoppingAction.ToggleLater(milk.id))
        assertEquals(listOf(null, null), delays())
        vm.onAction(ShoppingAction.Undo)
        assertEquals(listOf(until, later), delays())
    }

    @Test
    fun theEditSheetOfAMergedRowEditsOneEntryAndSwitches() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ShoppingAction.OpenEdit(cream.id))
        val edit = vm.uiState.value.edit!!
        assertEquals(cream.id, edit.entry.id)
        assertEquals(listOf(cream.id, creamGrams.id, creamCarton.id), edit.parts.map { it.id })

        vm.onAction(ShoppingAction.EditPart(creamGrams.id))
        val switched = vm.uiState.value.edit!!
        assertEquals(creamGrams.id, switched.entry.id)
        assertEquals("200", switched.amount)
        assertEquals("g", switched.unit)
        assertEquals(edit.parts, switched.parts)

        // Saving changes only that entry.
        vm.onAction(ShoppingAction.EditAmount("250"))
        vm.onAction(ShoppingAction.SaveEdit)
        assertEquals(250.0, shopping.entries.value.first { it.id == creamGrams.id }.amount, 0.0)
        assertEquals(3.0, shopping.entries.value.first { it.id == cream.id }.amount, 0.0)

        // A row of its own has no parts.
        vm.onAction(ShoppingAction.OpenEdit(ShoppingSamples.potato.id))
        assertTrue(vm.uiState.value.edit!!.parts.isEmpty())
    }

    @Test
    fun theGridIsRememberedPerAccountAndAGuestMaySwitchToo() = runTest {
        val vm = viewModel()
        open(vm)
        assertFalse(vm.uiState.value.gridView)
        vm.onAction(ShoppingAction.ToggleGrid)
        assertTrue(view.grid.value)
        assertTrue(vm.uiState.value.gridView)

        permissions.setGuest(true)
        vm.onAction(ShoppingAction.ToggleGrid)
        assertFalse(vm.uiState.value.gridView)
        // A guest's tap on a row still changes nothing.
        vm.onAction(ShoppingAction.ToggleChecked(cream.id))
        assertEquals(listOf(false, false, false), checked(cream.id, creamGrams.id, creamCarton.id))
    }

    @Test
    fun storeModeChecksEveryEntryOfAMergedRow() = runTest {
        val vm = StoreModeViewModel(
            key = StoreModeKey(),
            savedStateHandle = SavedStateHandle(),
            source = source(),
            shopping = shopping,
            supermarkets = supermarkets,
            trips = FakeStoreTripRepository(),
            clock = clock,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val before = vm.uiState.value.toBuy
        vm.onAction(StoreModeAction.Check(cream.id))
        assertEquals(listOf(true, true, true), checked(cream.id, creamGrams.id, creamCarton.id))
        assertEquals(before - 1, vm.uiState.value.toBuy)
        assertEquals(1, vm.uiState.value.inCart)
        vm.onAction(StoreModeAction.Undo)
        assertEquals(listOf(false, false, false), checked(cream.id, creamGrams.id, creamCarton.id))
        assertNull(vm.uiState.value.learnedOrder)
    }
}
