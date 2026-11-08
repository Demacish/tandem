package casa.moller.tandem.feature.recipes.detail

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePanSizeRepository
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.FakeSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fas 5 (plan §8.1 "Pannstorlek"): the recipe view scales by the tin's area and shares the tin with cook mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailPanTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val pasta = SampleRecipes.MushroomPasta
    private val pans = FakePanSizeRepository()

    private fun TestScope.viewModel(diameter: Int, handle: SavedStateHandle = SavedStateHandle()): RecipeDetailViewModel {
        val tin = pasta.copy(diameter = diameter, diameterText = "cm")
        val repository = FakeRecipeRepository(details = listOf(tin), overviews = emptyList())
        val viewModel = RecipeDetailViewModel(
            pasta.id,
            handle,
            repository,
            FakePreferencesRepository(),
            FakeSyncRepository(),
            FakeSessionRepository(),
            pans,
        )
        viewModel.onAction(RecipeDetailAction.SetNumberStyle(NumberStyle.Swedish))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun RecipeDetailUiState.champignons(): String =
        ingredients.filterIsInstance<IngredientRow.Item>().first { it.id == 10111L }.let { "${it.amount} ${it.unit}" }

    @Test
    fun noTinNoPicker() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(diameter = 0)
        assertNull(viewModel.uiState.value.pan)
        viewModel.onAction(RecipeDetailAction.SmallerPan)
        runCurrent()
        assertEquals("400 g", viewModel.uiState.value.champignons())
        assertTrue(pans.chosen.value.isEmpty())
    }

    @Test
    fun aSmallerTinScalesAndGoesToCookMode() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(diameter = 24)
        assertEquals(24, viewModel.uiState.value.pan?.original)
        assertEquals("cm", viewModel.uiState.value.pan?.unit)
        assertFalse(viewModel.uiState.value.pan!!.isChanged)
        repeat(4) { viewModel.onAction(RecipeDetailAction.SmallerPan) }
        runCurrent()
        val pan = viewModel.uiState.value.pan!!
        assertEquals(20, pan.current)
        assertEquals("0,69", pan.factorText)
        assertTrue(pan.isChanged)
        assertEquals("276 g", viewModel.uiState.value.champignons())
        assertEquals(mapOf(pasta.id to 20), pans.chosen.value)

        viewModel.onAction(RecipeDetailAction.ResetPan)
        runCurrent()
        assertEquals("400 g", viewModel.uiState.value.champignons())
        assertTrue(pans.chosen.value.isEmpty())
    }

    @Test
    fun aTinChosenInCookModeShowsHere() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(diameter = 24)
        pans.setPanSize(pasta.id, 28)
        runCurrent()
        assertEquals(28, viewModel.uiState.value.pan?.current)
        // (28/24)² = 1.36: 400 g × 1.36.
        assertEquals("544 g", viewModel.uiState.value.champignons())
    }
}
