package casa.moller.tandem.feature.scan.receipt

import casa.moller.tandem.core.domain.scan.TextFragment
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeFoodAliasRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.feature.scan.ScanMatcher
import casa.moller.tandem.feature.scan.ScanSamples
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.session.ScanSessionStore
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

/** The receipt scanner (#44): photos to rows, the matching, problems, and closing after the review saved. */
class ReceiptScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = ScanSessionStore()
    private val aliases = FakeFoodAliasRepository()
    private val recipes = FakeRecipeRepository(foods = ScanSamples.foods)
    private val permissions = FakeSpacePermissionsRepository()

    private fun viewModel(key: ReceiptScanKey = ReceiptScanKey(locationId = 3)) =
        ReceiptScanViewModel(key = key, store = store, matcher = ScanMatcher(aliases, recipes), permissions = permissions)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: ReceiptScanViewModel): MutableList<ReceiptScanEffect> {
        val effects = mutableListOf<ReceiptScanEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    /** A SYNTHETIC receipt photo: each line's name on the left and its price on the right, 30 px apart. */
    private fun photo(vararg lines: Pair<String, String?>): List<TextFragment> = lines.flatMapIndexed { index, (name, price) ->
        val top = 100 + index * 30
        listOfNotNull(TextFragment(name, 20, top, 400, top + 20), price?.let { TextFragment(it, 600, top + 2, 680, top + 22) })
    }

    @Test
    fun aReceiptBecomesReviewRowsMatchedByAliasSearchOrNothing() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ReceiptScanAction.Reading)
        assertTrue(vm.uiState.value.reading)
        vm.onAction(
            ReceiptScanAction.PageRead(
                photo(
                    "EXEMPELBUTIKEN" to null,
                    "GRÄDDE 36% 5DL" to "24,90",
                    "Bananer Eko" to "30,73",
                    "1,234kg*24,90kr/kg" to null,
                ),
            ),
        )
        assertEquals(1, vm.uiState.value.pages)
        vm.onAction(
            ReceiptScanAction.PageRead(
                photo(
                    "Bananer Eko" to "30,73",
                    "1,234kg*24,90kr/kg" to null,
                    "TVÄTTMEDEL COLOR 1,5L" to "59,90",
                    "TOTALT" to "115,53",
                    "Kort ************0000" to null,
                ),
            ),
        )
        assertEquals(2, vm.uiState.value.pages)
        vm.onAction(ReceiptScanAction.Done)
        val sessionId = effects.filterIsInstance<ReceiptScanEffect.OpenReview>().single().sessionId
        val session = store.get(sessionId)!!
        assertEquals(ScanSource.RECEIPT, session.source)
        assertEquals(3L, session.locationId)
        val rows = session.items
        assertEquals(listOf("GRÄDDE 36% 5DL", "Bananer Eko", "TVÄTTMEDEL COLOR 1,5L"), rows.map { it.text })
        // Learned receipt name: the household's alias, nothing to learn again.
        assertEquals(FoodMatchVia.ALIAS, rows[0].food?.via)
        assertEquals(101L, rows[0].food?.id)
        assertEquals(false, rows[0].learn)
        assertEquals(5.0, rows[0].amount!!, 0.0)
        assertEquals("dl", rows[0].unit)
        // A suggestion from the stored foods, learned on save.
        assertEquals("banan", rows[1].food?.name)
        assertEquals(FoodMatchVia.SEARCH, rows[1].food?.via)
        assertTrue(rows[1].learn)
        assertEquals(1.234, rows[1].amount!!, 0.0)
        assertEquals("kg", rows[1].unit)
        // Unknown: the user picks or skips it in the review.
        assertNull(rows[2].food)
        // Nothing from the total or the card line.
        assertTrue(rows.none { "0000" in it.text || "TOTALT" in it.text })
    }

    @Test
    fun theSecondTimeTheSameStoreIsUsedTheLearnedNamesMatch() = runTest {
        // The first receipt's lines were learned (the review saves them as FOOD_ALIAS).
        aliases.learn("Bananer Eko", "banan", FoodAliasKind.RECEIPT, 111)
        aliases.learn("TVÄTTMEDEL COLOR 1,5L", "tvättmedel", FoodAliasKind.RECEIPT, null)
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(
            ReceiptScanAction.PageRead(
                photo("GRÄDDE 36% 5DL" to "24,90", "Bananer Eko" to "30,73", "TVÄTTMEDEL COLOR 1,5L" to "59,90"),
            ),
        )
        vm.onAction(ReceiptScanAction.Done)
        val rows = store.get(effects.filterIsInstance<ReceiptScanEffect.OpenReview>().single().sessionId)!!.items
        assertTrue(rows.all { it.food?.via == FoodMatchVia.ALIAS })
        assertEquals(listOf("grädde", "banan", "tvättmedel"), rows.map { it.food?.name })
    }

    @Test
    fun aPhotoWithoutTextIsNotCounted() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ReceiptScanAction.PageRead(emptyList()))
        assertEquals(0, vm.uiState.value.pages)
        assertEquals(ReceiptProblem.NO_TEXT, vm.uiState.value.problem)
        vm.onAction(ReceiptScanAction.DismissProblem)
        assertNull(vm.uiState.value.problem)
        vm.onAction(ReceiptScanAction.PageFailed)
        assertEquals(ReceiptProblem.UNREADABLE, vm.uiState.value.problem)
    }

    @Test
    fun textWithoutItemLinesGivesNoReview() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ReceiptScanAction.PageRead(photo("TOTALT" to "115,53", "Tack för besöket!" to null)))
        vm.onAction(ReceiptScanAction.Done)
        assertEquals(ReceiptProblem.NO_ITEMS, vm.uiState.value.problem)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun theLastPhotoCanBeTakenAgain() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(ReceiptScanAction.PageRead(photo("MJÖLK" to "17,90")))
        vm.onAction(ReceiptScanAction.PageRead(photo("SUDDIGT" to null)))
        vm.onAction(ReceiptScanAction.RemoveLastPage)
        assertEquals(1, vm.uiState.value.pages)
    }

    @Test
    fun backFromTheReviewTheSameReviewOpensAgainAndClosesAfterSaving() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(ReceiptScanAction.PageRead(photo("MJÖLK" to "17,90")))
        vm.onAction(ReceiptScanAction.Done)
        vm.onAction(ReceiptScanAction.Done)
        val opened = effects.filterIsInstance<ReceiptScanEffect.OpenReview>()
        assertEquals(2, opened.size)
        assertEquals(opened[0], opened[1])
        store.markSaved(opened[0].sessionId, 1)
        assertEquals(ReceiptScanEffect.Finished, effects.last())
        assertNull(store.get(opened[0].sessionId))
        assertEquals(0, vm.uiState.value.pages)
    }

    @Test
    fun aGuestSeesTheReadOnlyState() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        open(vm)
        assertTrue(vm.uiState.value.readOnly)
    }
}
