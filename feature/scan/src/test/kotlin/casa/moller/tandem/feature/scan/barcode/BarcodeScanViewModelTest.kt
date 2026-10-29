package casa.moller.tandem.feature.scan.barcode

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeBarcodeLookup
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
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.scan.session.ScanSessionStore
import casa.moller.tandem.feature.scan.ui.ScanRowStatus
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The barcode scanner (#27): known, unknown, not found and offline codes, de-duplication, saving and the guest. */
class BarcodeScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(ScanSamples.now)
    private val store = ScanSessionStore()
    private val aliases = FakeFoodAliasRepository()
    private val lookup = FakeBarcodeLookup()
    private val pantry = FakePantryRepository(entries = emptyList())
    private val shopping = FakeShoppingRepository(entries = emptyList())
    private val permissions = FakeSpacePermissionsRepository()
    private val recipes = FakeRecipeRepository(foods = ScanSamples.foods)

    private fun viewModel(key: BarcodeScanKey = BarcodeScanKey(), saved: SavedStateHandle = SavedStateHandle()) = BarcodeScanViewModel(
        key = key,
        savedStateHandle = saved,
        store = store,
        matcher = ScanMatcher(aliases, recipes),
        lookup = lookup,
        saver = ScanSaver(pantry, aliases, shopping),
        pantry = pantry,
        permissions = permissions,
        clock = clock,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: BarcodeScanViewModel): MutableList<BarcodeScanEffect> {
        val effects = mutableListOf<BarcodeScanEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    @Test
    fun aLearnedCodeGoesStraightToItsFoodWithoutALookup() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        val row = vm.uiState.value.selected!!
        assertEquals(BarcodeLookupState.KNOWN, row.lookup)
        assertEquals("kikärtor", row.item.food?.name)
        assertEquals(FoodMatchVia.ALIAS, row.item.food?.via)
        assertEquals(ScanRowStatus.LEARNED, row.status)
        assertTrue(lookup.lookups.isEmpty())
        assertEquals(listOf<BarcodeScanEffect>(BarcodeScanEffect.Scanned), effects)
    }

    @Test
    fun aKnownCodeIsInThePantryInThreeTaps() = runTest {
        val vm = viewModel(BarcodeScanKey(locationId = SamplePantry.fridge.id))
        val effects = open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        val key = vm.uiState.value.selected!!.item.key
        // 1: the freezer, 2: +1 month, 3: "Lägg i Frys".
        vm.onAction(BarcodeScanAction.SetLocation(key, SamplePantry.freezer.id))
        vm.onAction(BarcodeScanAction.SetExpiry(key, ExpiryChoice.PLUS_1_MONTH))
        vm.onAction(BarcodeScanAction.SaveToPantry)
        val entry = pantry.entries.value.single()
        assertEquals(SamplePantry.freezer.id, entry.locationId)
        assertEquals("kikärtor", entry.food.name)
        assertEquals(110L, entry.food.id)
        assertEquals(ExpiryChoice.PLUS_1_MONTH.dateFrom(ScanSamples.today), entry.expires)
        assertEquals(1.0, entry.amount, 0.0)
        // Already learned: no second alias.
        assertEquals(SamplePantry.aliases, aliases.aliases.value)
        assertTrue(BarcodeScanEffect.Saved(1, "Frys") in effects)
        assertTrue(vm.uiState.value.rows.isEmpty())
    }

    @Test
    fun anUnknownCodeIsLookedUpAndTheChosenFoodIsLearnedOnSave() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.PRODUCT_CODE))
        val row = vm.uiState.value.selected!!
        assertEquals(listOf(ScanSamples.PRODUCT_CODE), lookup.lookups)
        assertEquals(BarcodeLookupState.PRODUCT, row.lookup)
        assertEquals("Vispgrädde 36% Exempelmejeriet", row.productName)
        // The product name suggests the stored food "vispgrädde".
        assertEquals("vispgrädde", row.item.food?.name)
        assertEquals(FoodMatchVia.SEARCH, row.item.food?.via)
        assertEquals(listOf("vispgrädde", "grädde"), row.suggestions.map { it.name })
        // The user prefers plain "grädde".
        vm.onAction(BarcodeScanAction.ChooseFood(row.item.key, ScanSamples.cream))
        vm.onAction(BarcodeScanAction.SaveToPantry)
        assertEquals("grädde", pantry.entries.value.single().food.name)
        assertEquals(SamplePantry.fridge.id, pantry.entries.value.single().locationId)
        val learned = aliases.aliases.value.single { it.alias == ScanSamples.PRODUCT_CODE }
        assertEquals("grädde", learned.foodName)
        assertEquals(FoodAliasKind.BARCODE, learned.kind)
        assertEquals(101L, learned.foodId)
    }

    @Test
    fun aCodeNobodyKnowsGetsANewFood() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.UNKNOWN_CODE))
        val row = vm.uiState.value.selected!!
        assertEquals(BarcodeLookupState.NOT_FOUND, row.lookup)
        assertEquals(ScanRowStatus.NOT_FOUND, row.status)
        assertNull(row.item.food)
        vm.onAction(BarcodeScanAction.OpenPicker(row.item.key))
        vm.onAction(BarcodeScanAction.PickerQuery("rabarber"))
        assertTrue(vm.uiState.value.picker!!.canCreate)
        vm.onAction(BarcodeScanAction.CreateFood(row.item.key, "rabarber"))
        assertNull(vm.uiState.value.picker)
        vm.onAction(BarcodeScanAction.SaveToPantry)
        val entry = pantry.entries.value.single()
        assertEquals("rabarber", entry.food.name)
        assertNull(entry.food.id)
        assertEquals("rabarber", aliases.aliases.value.single { it.alias == ScanSamples.UNKNOWN_CODE }.foodName)
    }

    @Test
    fun offlineTheUserPicksTheFoodAndTheCodeIsStillLearned() = runTest {
        lookup.failure = TandemError.NetworkUnavailable
        val vm = viewModel()
        open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.UNKNOWN_CODE))
        val row = vm.uiState.value.selected!!
        assertEquals(BarcodeLookupState.OFFLINE, row.lookup)
        assertEquals(ScanRowStatus.OFFLINE, row.status)
        vm.onAction(BarcodeScanAction.ChooseFood(row.item.key, ScanSamples.milk))
        assertEquals(ScanRowStatus.CHOSEN, vm.uiState.value.selected!!.status)
        vm.onAction(BarcodeScanAction.SaveToPantry)
        assertEquals("mjölk", pantry.entries.value.single().food.name)
        assertEquals("mjölk", aliases.aliases.value.single { it.alias == ScanSamples.UNKNOWN_CODE }.foodName)
    }

    @Test
    fun theSameCodeInEveryFrameIsTakenOnce() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        repeat(5) { vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE)) }
        clock.advanceBy(10.seconds)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        assertEquals(1, vm.uiState.value.rows.size)
        assertEquals(1, effects.count { it == BarcodeScanEffect.Scanned })
        // Not digits (a QR code's text): ignored.
        vm.onAction(BarcodeScanAction.CodeScanned("https://example.com"))
        assertEquals(1, vm.uiState.value.rows.size)
    }

    @Test
    fun aRemovedCodeCanBeScannedAgainAfterTheCameraLostIt() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        vm.onAction(BarcodeScanAction.Remove(vm.uiState.value.selected!!.item.key))
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        assertTrue(vm.uiState.value.rows.isEmpty())
        clock.advanceBy(5.seconds)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        assertEquals(1, vm.uiState.value.rows.size)
    }

    @Test
    fun severalPackagesGoThroughTheSharedReview() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.PRODUCT_CODE))
        val state = vm.uiState.value
        assertEquals(listOf(ScanSamples.PRODUCT_CODE, ScanSamples.KNOWN_CODE), state.rows.map { it.item.barcode })
        // "Lägg i …" is only for one package: with two it opens the review.
        vm.onAction(BarcodeScanAction.SaveToPantry)
        val review = effects.filterIsInstance<BarcodeScanEffect.OpenReview>().single()
        assertEquals(2, store.get(review.sessionId)!!.items.size)
        assertTrue(pantry.entries.value.isEmpty())
        // The review saved: the scanner says so and starts over.
        store.markSaved(review.sessionId, 2)
        assertTrue(BarcodeScanEffect.Saved(2, null) in effects)
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertNull(store.get(review.sessionId))
    }

    @Test
    fun theSelectedPackageGoesOnTheShoppingList() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        vm.onAction(BarcodeScanAction.AddToShoppingList)
        assertEquals(listOf("kikärtor"), shopping.entries.value.map { it.food.name })
        assertTrue(BarcodeScanEffect.AddedToList(1) in effects)
        assertTrue(pantry.entries.value.isEmpty())
    }

    @Test
    fun aPickedPhotoWithoutACodeSaysSo() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(BarcodeScanAction.ImageRead(emptyList()))
        vm.onAction(BarcodeScanAction.ImageFailed)
        vm.onAction(BarcodeScanAction.ImageRead(listOf(ScanSamples.KNOWN_CODE)))
        assertEquals(
            listOf(BarcodeScanEffect.NoCodeInImage, BarcodeScanEffect.ImageUnreadable, BarcodeScanEffect.Scanned),
            effects,
        )
        assertEquals(1, vm.uiState.value.rows.size)
    }

    @Test
    fun aGuestCanScanButNotSave() = runTest {
        permissions.setGuest(true)
        pantry.readOnly = true
        aliases.readOnly = true
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(BarcodeScanAction.SaveToPantry)
        assertTrue(pantry.entries.value.isEmpty())
        assertTrue(effects.none { it is BarcodeScanEffect.Saved })
    }

    @Test
    fun aSaveTheRepositoryRefusesIsReported() = runTest {
        pantry.failure = DataProblem.READ_ONLY.toError()
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        vm.onAction(BarcodeScanAction.SaveToPantry)
        assertEquals(BarcodeScanEffect.Failed(DataProblem.READ_ONLY.toError()), effects.last())
        assertEquals(1, vm.uiState.value.rows.size)
    }

    @Test
    fun theSessionSurvivesRotation() = runTest {
        val saved = SavedStateHandle()
        val first = viewModel(saved = saved)
        open(first)
        first.onAction(BarcodeScanAction.CodeScanned(ScanSamples.KNOWN_CODE))
        val second = viewModel(saved = saved)
        open(second)
        assertEquals(1, second.uiState.value.rows.size)
    }
}
