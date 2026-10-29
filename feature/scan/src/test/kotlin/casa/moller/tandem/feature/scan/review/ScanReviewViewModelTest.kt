package casa.moller.tandem.feature.scan.review

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeFoodAliasRepository
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.scan.ScanMatcher
import casa.moller.tandem.feature.scan.ScanSamples
import casa.moller.tandem.feature.scan.ScanSaver
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import casa.moller.tandem.feature.scan.session.ScanSessionStore
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

/** The shared review list: every row editable, skip, learn, nothing saved before "Spara", the guest and a lost session. */
class ScanReviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(ScanSamples.now)
    private val store = ScanSessionStore()
    private val aliases = FakeFoodAliasRepository()
    private val pantry = FakePantryRepository(entries = emptyList())
    private val permissions = FakeSpacePermissionsRepository()
    private val recipes = FakeRecipeRepository(foods = ScanSamples.foods)

    private fun viewModel(sessionId: String) = ScanReviewViewModel(
        key = ScanReviewKey(sessionId),
        store = store,
        matcher = ScanMatcher(aliases, recipes),
        saver = ScanSaver(pantry, aliases, FakeShoppingRepository(entries = emptyList())),
        pantry = pantry,
        permissions = permissions,
        clock = clock,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: ScanReviewViewModel): MutableList<ScanReviewEffect> {
        val effects = mutableListOf<ScanReviewEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    private fun receipt(locationId: Long? = SamplePantry.fridge.id): String =
        store.create(ScanSource.RECEIPT, locationId, ScanSamples.receiptRows())

    @Test
    fun theRowsAreGroupedAndNothingIsSavedBeforeSave() = runTest {
        val id = receipt()
        val vm = viewModel(id)
        open(vm)
        val state = vm.uiState.value
        assertEquals(listOf("r3"), state.toCheck.map { it.key })
        assertEquals(listOf("r1", "r2", "r4"), state.ready.map { it.key })
        assertEquals(listOf("r5"), state.skipped.map { it.key })
        assertEquals(3, state.readyCount)
        assertEquals(1, state.unmatchedCount)
        assertEquals(SamplePantry.fridge.id, state.defaultLocationId)
        assertTrue(pantry.entries.value.isEmpty())
        assertEquals(SamplePantry.aliases, aliases.aliases.value)
    }

    @Test
    fun savingPutsTheReadyRowsInThePantryAndLearnsTheMatchedLines() = runTest {
        val id = receipt()
        val vm = viewModel(id)
        val effects = open(vm)
        vm.onAction(ScanReviewAction.Save)
        val entries = pantry.entries.value
        assertEquals(listOf("grädde", "banan", "kaffe"), entries.map { it.food.name })
        // Their own place, or the default (Kyl).
        assertEquals(listOf(1L, 1L, 3L), entries.map { it.locationId })
        assertEquals(listOf(5.0, 1.234, 900.0), entries.map { it.amount })
        assertEquals(listOf("dl", "kg", "g"), entries.map { it.unit?.name })
        // The learned name was not learned again; the suggestion and the manual pick were.
        val learned = aliases.aliases.value - SamplePantry.aliases.toSet()
        assertEquals(setOf("Bananer Eko" to "banan", "Kaffe Mellanrost 450g" to "kaffe"), learned.map { it.alias to it.foodName }.toSet())
        assertTrue(learned.all { it.kind == FoodAliasKind.RECEIPT })
        assertEquals(listOf<ScanReviewEffect>(ScanReviewEffect.Saved(3)), effects)
        assertEquals(3, store.get(id)!!.saved)
        // While the page closes, it keeps showing the rows (the scanner drops the session).
        store.remove(id)
        assertFalse(vm.uiState.value.missing)
    }

    @Test
    fun anUnknownRowIsMatchedInTheEditAndSavedWithItsChanges() = runTest {
        val id = receipt()
        val vm = viewModel(id)
        open(vm)
        vm.onAction(ScanReviewAction.Edit("r3"))
        val edit = vm.uiState.value.edit!!
        assertEquals("TVÄTTMEDEL COLOR 1,5L", edit.text)
        assertTrue(edit.canLearn)
        vm.onAction(ScanReviewAction.EditQuery("gu"))
        assertEquals(listOf("gurka"), vm.uiState.value.edit!!.picker.results.map { it.name })
        vm.onAction(ScanReviewAction.EditPick(ScanSamples.cucumber))
        vm.onAction(ScanReviewAction.EditAmount("2,5"))
        vm.onAction(ScanReviewAction.EditUnit("st"))
        vm.onAction(ScanReviewAction.EditLocation(SamplePantry.pantry.id))
        vm.onAction(ScanReviewAction.EditExpiry(ExpiryChoice.PLUS_1_WEEK))
        vm.onAction(ScanReviewAction.ApplyEdit)
        assertNull(vm.uiState.value.edit)
        val row = store.get(id)!!.items.single { it.key == "r3" }
        assertEquals("gurka", row.food?.name)
        assertEquals(FoodMatchVia.MANUAL, row.food?.via)
        assertEquals(2.5, row.amount!!, 0.0)
        assertEquals("st", row.unit)
        assertEquals(SamplePantry.pantry.id, row.locationId)
        assertEquals(ExpiryChoice.PLUS_1_WEEK.dateFrom(ScanSamples.today), row.expires)
        assertTrue(row.learn)
        assertEquals(0, vm.uiState.value.unmatchedCount)
    }

    @Test
    fun aWrongAmountKeepsTheEditOpen() = runTest {
        val id = receipt()
        val vm = viewModel(id)
        open(vm)
        vm.onAction(ScanReviewAction.Edit("r1"))
        assertEquals("5", vm.uiState.value.edit!!.amount)
        vm.onAction(ScanReviewAction.EditAmount("fem"))
        assertTrue(vm.uiState.value.edit!!.amountError)
        vm.onAction(ScanReviewAction.ApplyEdit)
        assertTrue(vm.uiState.value.edit != null)
        vm.onAction(ScanReviewAction.CancelEdit)
        assertEquals(5.0, store.get(id)!!.items.single { it.key == "r1" }.amount!!, 0.0)
    }

    @Test
    fun theLearnSwitchAndSkippingAreRespected() = runTest {
        val id = receipt()
        val vm = viewModel(id)
        open(vm)
        vm.onAction(ScanReviewAction.Edit("r2"))
        vm.onAction(ScanReviewAction.EditLearn(false))
        vm.onAction(ScanReviewAction.ApplyEdit)
        vm.onAction(ScanReviewAction.ToggleInclude("r4"))
        vm.onAction(ScanReviewAction.Remove("r1"))
        vm.onAction(ScanReviewAction.SetDefaultLocation(SamplePantry.freezer.id))
        vm.onAction(ScanReviewAction.Save)
        assertEquals(listOf("banan"), pantry.entries.value.map { it.food.name })
        assertEquals(SamplePantry.freezer.id, pantry.entries.value.single().locationId)
        assertEquals(SamplePantry.aliases, aliases.aliases.value)
    }

    @Test
    fun theFridgePhotoIsNeverLearned() = runTest {
        val id = store.create(ScanSource.FRIDGE, null, ScanSamples.fridgeRows())
        val vm = viewModel(id)
        open(vm)
        vm.onAction(ScanReviewAction.Edit("f2"))
        assertFalse(vm.uiState.value.edit!!.canLearn)
        vm.onAction(ScanReviewAction.EditPick(ScanSamples.pasta))
        vm.onAction(ScanReviewAction.ApplyEdit)
        vm.onAction(ScanReviewAction.Save)
        assertEquals(listOf("mjölk", "pasta"), pantry.entries.value.map { it.food.name })
        assertEquals(SamplePantry.aliases, aliases.aliases.value)
    }

    @Test
    fun aFailedSaveKeepsTheRowsThatWereNotSaved() = runTest {
        val id = receipt()
        val vm = viewModel(id)
        val effects = open(vm)
        pantry.failure = TandemError.Unknown("disk-full")
        vm.onAction(ScanReviewAction.Save)
        assertEquals(listOf<ScanReviewEffect>(ScanReviewEffect.Failed(TandemError.Unknown("disk-full"))), effects)
        assertEquals(5, store.get(id)!!.items.size)
        assertNull(store.get(id)!!.saved)
        assertFalse(vm.uiState.value.saving)
    }

    @Test
    fun aGuestCannotSave() = runTest {
        permissions.setGuest(true)
        val vm = viewModel(receipt())
        val effects = open(vm)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(ScanReviewAction.Save)
        assertTrue(effects.isEmpty())
        assertTrue(pantry.entries.value.isEmpty())
    }

    @Test
    fun aLostSessionOffersToScanAgain() = runTest {
        val vm = viewModel("gone-after-process-death")
        open(vm)
        assertTrue(vm.uiState.value.missing)
    }

    @Test
    fun withoutPlacesTheReviewAsksHemmaForThem() = runTest {
        pantry.locations.value = emptyList()
        val vm = viewModel(receipt(locationId = null))
        val effects = open(vm)
        assertEquals(1, pantry.refreshes)
        assertNull(vm.uiState.value.defaultLocationId)
        vm.onAction(ScanReviewAction.Save)
        assertEquals(listOf<ScanReviewEffect>(ScanReviewEffect.Failed(TandemError.Unknown(ScanSaver.NO_LOCATION))), effects.take(1))
        // r1 has its own place: it was saved before the first row without one stopped the save.
        assertEquals(listOf("grädde"), pantry.entries.value.map { it.food.name })
    }
}
