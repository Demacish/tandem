package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.shopping.IngredientParser
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeIngredientParser
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.shopping.ShoppingSamples.coffee
import casa.moller.tandem.feature.shopping.ShoppingSamples.cream
import casa.moller.tandem.feature.shopping.ShoppingSamples.eggs
import casa.moller.tandem.feature.shopping.ShoppingSamples.milk
import casa.moller.tandem.feature.shopping.ShoppingSamples.onions
import casa.moller.tandem.feature.shopping.ShoppingSamples.potato
import casa.moller.tandem.feature.shopping.navigation.ShoppingListKey
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShoppingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(ShoppingSamples.now)
    private val shopping = FakeShoppingRepository(entries = ShoppingSamples.entries, lists = ShoppingSamples.lists)
    private val supermarkets = FakeSupermarketRepository(ShoppingSamples.supermarkets, ShoppingSamples.categories).apply {
        selected.value = ShoppingSamples.store.id
    }
    private val parser = FakeIngredientParser()
    private val permissions = FakeSpacePermissionsRepository()
    private val foreground = FakeForegroundSync()
    private val sync = FakeSyncRepository()
    private val preferences = FakePreferencesRepository(UserPreferences(defaultDelayHours = 4.0))

    private fun viewModel(key: ShoppingListKey = ShoppingListKey(), saved: SavedStateHandle = SavedStateHandle()) = ShoppingViewModel(
        key = key,
        savedStateHandle = saved,
        source = ShoppingSource(shopping, supermarkets, permissions, foreground, sync, preferences, clock),
        shopping = shopping,
        supermarkets = supermarkets,
        parser = parser,
        clock = clock,
    )

    /** Starts collecting the state (as the screen does) and the effects. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: ShoppingViewModel): MutableList<ShoppingEffect> {
        val effects = mutableListOf<ShoppingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    @Test
    fun groupsByTheChosenStoresAisleOrderAndKeepsTheListFresh() = runTest {
        val vm = viewModel()
        open(vm)
        val state = vm.uiState.value

        assertFalse(state.loading)
        assertEquals(ShoppingSamples.store, state.store)
        assertEquals(listOf("Frukt & grönt", "Skafferi", "Mejeri", null), state.grouped.sections.map { it.name })
        assertEquals(listOf(onions, potato), state.grouped.sections.first().entries)
        assertEquals(listOf(coffee), state.grouped.later)
        assertEquals(listOf(eggs), state.grouped.checked)
        assertEquals(1 to 8, state.progressDone to state.progressTotal)
        // Plan 3.5: the list polls while it is visible.
        assertEquals(listOf<ForegroundTarget>(ForegroundTarget.ShoppingList), foreground.started)
    }

    @Test
    fun theStoreOfTheKeyWinsAndChoosingAStoreIsRememberedOnTheDevice() = runTest {
        val vm = viewModel(ShoppingListKey(supermarketId = ShoppingSamples.otherStore.id))
        open(vm)
        assertEquals(ShoppingSamples.otherStore, vm.uiState.value.store)
        assertEquals("Mejeri", vm.uiState.value.grouped.sections.first().name)

        vm.onAction(ShoppingAction.SelectStore(null))
        assertNull(supermarkets.selected.value)
        assertNull(vm.uiState.value.store)
        assertEquals(listOf("Frukt & grönt", "Mejeri", "Skafferi", null), vm.uiState.value.grouped.sections.map { it.name })
    }

    @Test
    fun aNamedListShowsOnlyItsEntriesAndNewEntriesGoOnIt() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ShoppingAction.SelectList(1))
        assertEquals(listOf(milk.id, eggs.id), vm.uiState.value.grouped.all.map { it.id }.sorted())

        vm.onAction(ShoppingAction.AddFood("bröd"))
        assertEquals(setOf(1L), shopping.entries.value.first { it.food.name == "bröd" }.listIds)
    }

    @Test
    fun checkingShowsUndoAndUndoUnchecks() = runTest {
        val vm = viewModel()
        val effects = open(vm)

        vm.onAction(ShoppingAction.ToggleChecked(milk.id))
        assertTrue(shopping.entries.value.first { it.id == milk.id }.checked)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Checked("mjölk", 1), undoable = true), effects.last())
        assertTrue(milk.id in vm.uiState.value.grouped.checked.map { it.id })

        vm.onAction(ShoppingAction.Undo)
        assertFalse(shopping.entries.value.first { it.id == milk.id }.checked)
        // Undo works once.
        vm.onAction(ShoppingAction.Undo)
        assertFalse(shopping.entries.value.first { it.id == milk.id }.checked)
    }

    @Test
    fun uncheckingACheckedEntryPutsItBack() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ShoppingAction.ToggleChecked(eggs.id))
        assertFalse(shopping.entries.value.first { it.id == eggs.id }.checked)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Unchecked("ägg"), undoable = true), effects.last())
    }

    @Test
    fun checkAllInAnAisleChecksItsEntriesAtOnce() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        val dairy = vm.uiState.value.grouped.sections.first { it.name == "Mejeri" }

        vm.onAction(ShoppingAction.CheckSection(dairy.key))
        assertTrue(shopping.entries.value.filter { it.id == milk.id || it.id == cream.id }.all { it.checked })
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Checked("grädde", 2), undoable = true), effects.last())

        vm.onAction(ShoppingAction.Undo)
        assertTrue(shopping.entries.value.filter { it.id == milk.id || it.id == cream.id }.none { it.checked })
    }

    @Test
    fun buyLaterDelaysByTheUsersDefaultDelayAndBuyNowUndoesIt() = runTest {
        val vm = viewModel()
        val effects = open(vm)

        vm.onAction(ShoppingAction.ToggleLater(milk.id))
        assertEquals(ShoppingSamples.now + 4.hours, shopping.entries.value.first { it.id == milk.id }.delayUntil)
        assertTrue(milk.id in vm.uiState.value.grouped.later.map { it.id })
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Delayed("mjölk"), undoable = true), effects.last())

        vm.onAction(ShoppingAction.ToggleLater(coffee.id))
        assertNull(shopping.entries.value.first { it.id == coffee.id }.delayUntil)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Undelayed("kaffe"), undoable = true), effects.last())
        // Undo puts coffee back to its old delay.
        vm.onAction(ShoppingAction.Undo)
        assertEquals(coffee.delayUntil, shopping.entries.value.first { it.id == coffee.id }.delayUntil)
    }

    @Test
    fun theAddPreviewUsesTheLocalParserAndAddingUsesTheServersParser() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ShoppingAction.OpenAdd)
        vm.onAction(ShoppingAction.AddTextChanged("2 dl grädde, mjölk"))

        val preview = vm.uiState.value.add.preview
        assertEquals(listOf("grädde", "mjölk"), preview.map { it.foodName })
        assertEquals(2.0, preview.first().amount, 0.0)
        assertEquals("dl", preview.first().unitName)
        // Nothing reached Tandoor's parser while typing (it creates foods).
        assertTrue(parser.parsed.isEmpty())

        parser.serverResults["2 dl grädde"] =
            ParsedIngredient("2 dl grädde", 2.0, "dl", "grädde", unitId = 5, foodId = 39, fromServer = true)
        vm.onAction(ShoppingAction.SubmitAdd)
        assertEquals(listOf(listOf("2 dl grädde", "mjölk")), parser.parsed)
        val added = shopping.entries.value.filter { it.id < 0 && it.id != ShoppingSamples.butter.id }
        assertEquals(listOf("grädde", "mjölk"), added.map { it.food.name })
        assertEquals(39L, added.first().food.id)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Added("grädde", 2), undoable = true), effects.last())
        // The field is cleared and stays open for the next item.
        assertEquals("", vm.uiState.value.add.text)
        assertTrue(vm.uiState.value.addSheetOpen)

        vm.onAction(ShoppingAction.Undo)
        assertTrue(shopping.entries.value.none { it.id in added.map { entry -> entry.id } })
    }

    @Test
    fun withoutTheServerParserTheLocalOneIsUsed() = runTest {
        val failing = object : IngredientParser by parser {
            override suspend fun parse(lines: List<String>): Outcome<List<ParsedIngredient>> =
                Outcome.Failure(TandemError.NetworkUnavailable)
        }
        val vm = ShoppingViewModel(
            ShoppingListKey(),
            SavedStateHandle(),
            ShoppingSource(shopping, supermarkets, permissions, foreground, sync, preferences, clock),
            shopping,
            supermarkets,
            failing,
            clock,
        )
        open(vm)
        vm.onAction(ShoppingAction.AddTextChanged("1,5 kg potatis"))
        vm.onAction(ShoppingAction.SubmitAdd)
        val added = shopping.entries.value.last()
        assertEquals("potatis", added.food.name)
        assertEquals(1.5, added.amount, 0.0)
        assertEquals("kg", added.unit?.name)
    }

    @Test
    fun suggestionsFollowTheItemBeingTypedAndOneTapAddsIt() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ShoppingAction.AddTextChanged("mjölk, ka"))
        assertEquals(listOf("kaffe"), vm.uiState.value.add.suggestions)

        vm.onAction(ShoppingAction.AddFood("kaffe"))
        assertEquals("mjölk", vm.uiState.value.add.text)
        assertTrue(shopping.entries.value.any { it.id < 0 && it.food.name == "kaffe" })
    }

    @Test
    fun recentFoodsComeFromTheHistory() = runTest {
        shopping.history.value = ShoppingSamples.history + ShoppingSamples.history.first().copy(entryId = 104)
        val vm = viewModel()
        open(vm)
        assertEquals(listOf("havregryn", "bananer", "olivolja"), vm.uiState.value.add.recent)
    }

    @Test
    fun editingSavesTheChangedFieldsAndTheAisle() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ShoppingAction.OpenEdit(milk.id))
        val edit = vm.uiState.value.edit
        assertEquals("2", edit?.amount)
        assertEquals("l", edit?.unit)

        vm.onAction(ShoppingAction.EditAmount("tre"))
        vm.onAction(ShoppingAction.SaveEdit)
        assertTrue(vm.uiState.value.edit?.amountError == true)

        vm.onAction(ShoppingAction.EditAmount("1,5"))
        vm.onAction(ShoppingAction.EditCategory(ShoppingSamples.pantry.id))
        vm.onAction(ShoppingAction.SaveEdit)
        assertEquals(1.5, shopping.entries.value.first { it.id == milk.id }.amount, 0.0)
        assertEquals(ShoppingSamples.pantry.id, supermarkets.foodCategories[milk.food.id])
        assertNull(vm.uiState.value.edit)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Saved), effects.last())
    }

    @Test
    fun removingFromTheEditSheetCanBeUndone() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ShoppingAction.OpenEdit(milk.id))
        vm.onAction(ShoppingAction.DeleteEdited)
        assertTrue(shopping.entries.value.none { it.id == milk.id })

        vm.onAction(ShoppingAction.Undo)
        val back = shopping.entries.value.single { it.food.name == "mjölk" }
        assertEquals(2.0, back.amount, 0.0)
        assertEquals("l", back.unit?.name)
    }

    @Test
    fun clearingCheckedAsksFirst() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ShoppingAction.AskClearChecked)
        assertTrue(vm.uiState.value.confirmClearChecked)
        vm.onAction(ShoppingAction.ConfirmClearChecked(false))
        assertTrue(shopping.entries.value.any { it.checked })

        vm.onAction(ShoppingAction.AskClearChecked)
        vm.onAction(ShoppingAction.ConfirmClearChecked(true))
        assertTrue(shopping.entries.value.none { it.checked })
        assertFalse(vm.uiState.value.confirmClearChecked)
    }

    @Test
    fun aGuestSeesTheListButNothingIsWritten() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val vm = viewModel()
        open(vm)
        assertTrue(vm.uiState.value.readOnly)

        vm.onAction(ShoppingAction.ToggleChecked(milk.id))
        vm.onAction(ShoppingAction.ToggleLater(milk.id))
        vm.onAction(ShoppingAction.OpenAdd)
        vm.onAction(ShoppingAction.OpenEdit(milk.id))
        vm.onAction(ShoppingAction.AskClearChecked)
        vm.onAction(ShoppingAction.AddFood("godis"))

        assertEquals(ShoppingSamples.entries, shopping.entries.value)
        assertEquals(0, shopping.pendingCount.value)
        assertFalse(vm.uiState.value.addSheetOpen)
        assertNull(vm.uiState.value.edit)
        assertFalse(vm.uiState.value.confirmClearChecked)
    }

    /** 0.3.0 review (fix-data, plan 8.6): without a household the list says it is not shared. */
    @Test
    fun aMemberWithoutAHouseholdIsWarned() = runTest {
        val vm = viewModel()
        open(vm)
        assertFalse(vm.uiState.value.householdMissing)
        permissions.state.value = SpacePermissions(SpaceRole.USER, householdMissing = true)
        assertTrue(vm.uiState.value.householdMissing)
        assertFalse("the list can still be changed", vm.uiState.value.readOnly)
    }

    @Test
    fun demoModeIsNeverReadOnly() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST, isDemo = true)
        val vm = viewModel()
        open(vm)
        assertFalse(vm.uiState.value.readOnly)
    }

    @Test
    fun aRefusedWriteIsShown() = runTest {
        shopping.readOnly = true
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ShoppingAction.ToggleChecked(milk.id))
        val failed = effects.last() as ShoppingEffect.Failed
        assertEquals(DataProblem.READ_ONLY, failed.error.dataProblem())
    }

    @Test
    fun offlineShowsTheNoticeAndThePendingCount() = runTest {
        sync.setStatus(SyncStatus(pause = SyncPause.OFFLINE))
        shopping.pendingCount.value = 3
        val vm = viewModel()
        open(vm)
        assertEquals(ShoppingSyncNotice.OFFLINE, vm.uiState.value.syncNotice)
        assertEquals(3, vm.uiState.value.pendingCount)

        sync.setStatus(SyncStatus(pause = SyncPause.BLOCKED))
        assertEquals(ShoppingSyncNotice.PAUSED, vm.uiState.value.syncNotice)
        // fix-ui: the app's own pause after many refused requests has its own text.
        sync.setStatus(SyncStatus(pause = SyncPause.CIRCUIT_BREAKER))
        assertEquals(ShoppingSyncNotice.BREAKER, vm.uiState.value.syncNotice)
        sync.setStatus(SyncStatus())
        assertNull(vm.uiState.value.syncNotice)
    }

    @Test
    fun pullToRefreshReadsTheWholeListAndReportsAFailure() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ShoppingAction.Refresh)
        assertEquals(listOf(true), shopping.refreshes)

        shopping.failure = TandemError.NetworkUnavailable
        vm.onAction(ShoppingAction.Refresh)
        assertEquals(ShoppingEffect.Failed(TandemError.NetworkUnavailable), effects.last())
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test
    fun changesByOthersAreAnnouncedOncePerRead() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        val others = mutableListOf<ShoppingEffect.OtherChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.otherChanges.toList(others) }
        shopping.changes.emit(ShoppingChange.Added("mjölk", by = "Anna"))
        advanceTimeBy(CHANGE_BURST_WINDOW + 100.milliseconds)
        assertEquals(listOf(ShoppingEffect.OtherChange(ShoppingChange.Added("mjölk", by = "Anna"))), others)

        // fix-ui: one read that saw a partner's "Rensa" is ONE message, not one per row.
        repeat(3) { shopping.changes.emit(ShoppingChange.Removed("vara $it")) }
        advanceTimeBy(CHANGE_BURST_WINDOW + 100.milliseconds)
        assertEquals(2, others.size)
        assertEquals(3, others.last().changes.size)
        assertTrue("others' changes never go through the own effects", effects.none { it is ShoppingEffect.OtherChange })
    }

    @Test
    fun theProgressCountsOnlyWhatWasCheckedToday() = runTest {
        // Checked on Saturday's trip: Tandoor still lists it for days (FACTS "Fas 2 b").
        val saturday = eggs.copy(id = 50, completedAt = ShoppingSamples.now - 3.days)
        shopping.entries.value = ShoppingSamples.entries + saturday
        val vm = viewModel()
        open(vm)
        assertEquals(2, vm.uiState.value.grouped.checked.size)
        assertEquals(1 to 8, vm.uiState.value.progressDone to vm.uiState.value.progressTotal)
    }

    @Test
    fun anEntryMovesBetweenNamedListsWhenSaved() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ShoppingAction.OpenEdit(milk.id))
        assertEquals(ShoppingSamples.lists, vm.uiState.value.edit?.lists)
        assertEquals(setOf(1L), vm.uiState.value.edit?.listIds)

        vm.onAction(ShoppingAction.EditList(1))
        vm.onAction(ShoppingAction.EditList(2))
        assertEquals(setOf(2L), vm.uiState.value.edit?.listIds)
        assertEquals("nothing moves before Spara", setOf(1L), shopping.entries.value.first { it.id == milk.id }.listIds)

        vm.onAction(ShoppingAction.SaveEdit)
        assertEquals(setOf(2L), shopping.entries.value.first { it.id == milk.id }.listIds)
        assertEquals(ShoppingEffect.Show(ShoppingMessage.Saved), effects.last())
    }

    @Test
    fun removingARecipeFromTheEditSheetCanBeUndone() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ShoppingAction.OpenEdit(cream.id))
        assertEquals(2, vm.uiState.value.edit?.recipeRows)
        vm.onAction(ShoppingAction.RemoveEditedRecipe)
        assertTrue(shopping.entries.value.none { it.recipe?.listRecipeId == 3L })
        assertEquals(ShoppingEffect.Show(ShoppingMessage.RecipeRemoved("Potatisgratäng"), undoable = true), effects.last())

        // The potato row has no recipe ingredient: the rows come back one by one, with their amounts.
        vm.onAction(ShoppingAction.Undo)
        val back = shopping.entries.value.filter { it.id < 0 && it.id != ShoppingSamples.butter.id }
        assertEquals(listOf("grädde", "potatis"), back.map { it.food.name }.sorted())
        assertEquals(3.0, back.first { it.food.name == "grädde" }.amount, 0.0)
    }

    @Test
    fun theChosenListAndOpenSectionsSurviveProcessDeath() = runTest {
        val saved = SavedStateHandle()
        val first = viewModel(saved = saved)
        open(first)
        first.onAction(ShoppingAction.SelectList(2))
        first.onAction(ShoppingAction.ToggleCheckedExpanded)
        first.onAction(ShoppingAction.AddTextChanged("tvål"))

        val second = viewModel(saved = saved)
        open(second)
        assertEquals(2L, second.uiState.value.selectedListId)
        assertTrue(second.uiState.value.checkedExpanded)
        assertEquals("tvål", second.uiState.value.add.text)
    }
}
