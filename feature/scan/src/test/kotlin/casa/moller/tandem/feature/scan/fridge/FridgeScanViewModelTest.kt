package casa.moller.tandem.feature.scan.fridge

import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.model.ai.RecognizedItem
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeAiSettingsRepository
import casa.moller.tandem.core.testing.data.FakeFoodAliasRepository
import casa.moller.tandem.core.testing.data.FakeFridgeVision
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.feature.scan.ScanMatcher
import casa.moller.tandem.feature.scan.ScanSamples
import casa.moller.tandem.feature.scan.navigation.FridgeScanKey
import casa.moller.tandem.feature.scan.session.ScanSessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

/** The fridge photo (#45, B22): the warning first, the AI's items to suggestion rows, every failure, demo and closing. */
class FridgeScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = ScanSessionStore()
    private val recipes = FakeRecipeRepository(foods = ScanSamples.foods)
    private val permissions = FakeSpacePermissionsRepository()
    private val vision = FakeFridgeVision()
    private val ready = AiSettings(provider = AiProvider.CLAUDE, hasKey = true, warningAcknowledged = true)

    private fun viewModel(
        settings: AiSettings = ready,
        ai: FakeAiSettingsRepository = FakeAiSettingsRepository(settings),
    ) = FridgeScanViewModel(
        key = FridgeScanKey(locationId = 1),
        store = store,
        matcher = ScanMatcher(FakeFoodAliasRepository(), recipes),
        vision = vision,
        settings = ai,
        permissions = permissions,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: FridgeScanViewModel): MutableList<FridgeScanEffect> {
        val effects = mutableListOf<FridgeScanEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    private val photo = ByteArray(1_000)

    @Test
    fun theItemsBecomeSuggestionRowsAndUnsureOnesStartSkipped() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        assertTrue(vm.uiState.value.canScan)

        vm.onAction(FridgeScanAction.Preparing)
        assertEquals(FridgePhase.PREPARING, vm.uiState.value.phase)
        vm.onAction(FridgeScanAction.PhotoReady(photo))

        assertEquals(listOf(photo.size), vision.calls)
        val id = effects.filterIsInstance<FridgeScanEffect.OpenReview>().single().sessionId
        val session = store.get(id)!!
        assertEquals(ScanSource.FRIDGE, session.source)
        assertEquals(1L, session.locationId)
        val rows = session.items
        assertEquals(listOf("mjölk", "ägg", "gurka"), rows.map { it.text })
        // Every match is a suggestion, never "known", and nothing is learned from the AI.
        assertEquals(FoodMatchVia.SEARCH, rows[0].food?.via)
        assertEquals("mjölk", rows[0].food?.name)
        assertTrue(rows.none { it.learn })
        assertEquals(listOf(true, true, false), rows.map { it.include })
        assertTrue(rows[2].lowConfidence)
        assertEquals("delvis skymd", rows[2].note)
        assertEquals(FridgePhase.IDLE, vm.uiState.value.phase)
    }

    @Test
    fun theWarningMustBeAcknowledgedBeforeTheFirstPhoto() = runTest {
        val ai = FakeAiSettingsRepository(ready.copy(warningAcknowledged = false))
        val vm = viewModel(ai = ai)
        open(vm)
        assertTrue(vm.uiState.value.showWarning)
        assertFalse(vm.uiState.value.canScan)

        vm.onAction(FridgeScanAction.PhotoReady(photo))
        assertTrue(vision.calls.isEmpty())

        vm.onAction(FridgeScanAction.AcknowledgeWarning)
        assertTrue(ai.settings.first().warningAcknowledged)
        assertFalse(vm.uiState.value.showWarning)
        assertTrue(vm.uiState.value.canScan)
    }

    @Test
    fun withoutAKeyOrInDemoModeNothingIsSent() = runTest {
        val noKey = viewModel(AiSettings(provider = AiProvider.GEMINI, warningAcknowledged = true))
        open(noKey)
        assertFalse(noKey.uiState.value.available)
        noKey.onAction(FridgeScanAction.PhotoReady(photo))

        permissions.state.value = SpacePermissions(SpaceRole.USER, isDemo = true)
        val demo = viewModel()
        open(demo)
        assertTrue(demo.uiState.value.demo)
        assertFalse(demo.uiState.value.showWarning)
        demo.onAction(FridgeScanAction.PhotoReady(photo))

        assertTrue(vision.calls.isEmpty())
    }

    @Test
    fun aGuestCanLookButTheStateSaysReadOnly() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        open(vm)
        assertTrue(vm.uiState.value.readOnly)
        assertTrue(vm.uiState.value.canScan)
    }

    @Test
    fun everyFailureHasItsOwnProblemAndNoRetry() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        val cases = listOf(
            FridgeVisionError.InvalidKey to FridgeProblem.INVALID_KEY,
            FridgeVisionError.ModelUnavailable to FridgeProblem.MODEL_UNAVAILABLE,
            FridgeVisionError.QuotaOrRateLimit(30) to FridgeProblem.RATE_LIMITED,
            FridgeVisionError.Refusal to FridgeProblem.REFUSAL,
            FridgeVisionError.Network("timeout") to FridgeProblem.NETWORK,
            FridgeVisionError.Unparseable("x") to FridgeProblem.UNPARSEABLE,
        )
        cases.forEachIndexed { index, (error, problem) ->
            vision.result = FridgeVisionResult.Failed(error)
            vm.onAction(FridgeScanAction.PhotoReady(photo))
            assertEquals(problem, vm.uiState.value.problem)
            assertEquals(index + 1, vision.calls.size)
            vm.onAction(FridgeScanAction.DismissProblem)
            assertNull(vm.uiState.value.problem)
        }
        vision.result = FridgeVisionResult.Items(emptyList())
        vm.onAction(FridgeScanAction.PhotoReady(photo))
        assertEquals(FridgeProblem.NOTHING_FOUND, vm.uiState.value.problem)
        vm.onAction(FridgeScanAction.PhotoFailed)
        assertEquals(FridgeProblem.UNREADABLE, vm.uiState.value.problem)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun aCutOffAnswerTellsTheReviewAndTheSavedReviewClosesThePage() = runTest {
        vision.result = FridgeVisionResult.Items(listOf(RecognizedItem("ost", confidence = 0.8)), truncated = true)
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(FridgeScanAction.PhotoReady(photo))
        val id = effects.filterIsInstance<FridgeScanEffect.OpenReview>().single().sessionId
        assertTrue(store.get(id)!!.truncated)

        store.markSaved(id, 1)
        assertTrue(FridgeScanEffect.Finished in effects)
        assertNull(store.get(id))
    }

    @Test
    fun thePhotoNeverShowsInTheStateOrTheAction() {
        val action = FridgeScanAction.PhotoReady(ByteArray(42))
        assertEquals("PhotoReady(42 bytes)", action.toString())
        assertFalse(FridgeScanUiState().toString().contains("jpeg", ignoreCase = true))
    }
}
