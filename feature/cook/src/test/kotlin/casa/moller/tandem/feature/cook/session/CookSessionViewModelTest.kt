package casa.moller.tandem.feature.cook.session

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fas 4 (two recipes at once, plan 8.1): "Laga ett recept till", the tabs and process death. */
@OptIn(ExperimentalCoroutinesApi::class)
class CookSessionViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val recipes = FakeRecipeRepository()
    private val pasta = SampleRecipes.MushroomPasta
    private val buns = SampleRecipes.CinnamonBuns

    private fun TestScope.viewModel(key: CookKey = CookKey(pasta.id), handle: SavedStateHandle = SavedStateHandle()): CookSessionViewModel {
        val viewModel = CookSessionViewModel(key, handle, recipes)
        backgroundScope.launch { viewModel.session.collect {} }
        backgroundScope.launch { viewModel.picker.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun thePickerListsTheOtherStoredRecipes() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        assertFalse(viewModel.picker.value.open)

        viewModel.onAction(CookSessionAction.OpenPicker)
        runCurrent()

        val picker = viewModel.picker.value
        assertTrue(picker.open)
        assertFalse(picker.loading)
        assertTrue(picker.results.isNotEmpty())
        assertFalse("the open recipe is left out", picker.results.any { it.id == pasta.id })
    }

    @Test
    fun theSearchNarrowsThePicker() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(CookSessionAction.OpenPicker)
        viewModel.onAction(CookSessionAction.SetQuery("kanel"))
        runCurrent()

        assertEquals(listOf(buns.id), viewModel.picker.value.results.map { it.id })
    }

    @Test
    fun pickingARecipeOpensItBesideTheFirstAndClosesThePicker() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(CookSessionAction.OpenPicker)
        viewModel.onAction(CookSessionAction.AddRecipe(buns.id))
        runCurrent()

        val session = viewModel.session.value
        assertEquals(listOf(pasta.id, buns.id), session.recipeIds)
        assertEquals(buns.id, session.activeRecipeId)
        assertFalse(viewModel.picker.value.open)
        // Full: the picker does not open again.
        viewModel.onAction(CookSessionAction.OpenPicker)
        runCurrent()
        assertFalse(viewModel.picker.value.open)
    }

    @Test
    fun tabsSwitchAndClosingOneReturnsToSingleMode() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(CookSessionAction.AddRecipe(buns.id))
        viewModel.onAction(CookSessionAction.SelectRecipe(pasta.id))
        runCurrent()
        assertEquals(pasta.id, viewModel.session.value.activeRecipeId)

        viewModel.onAction(CookSessionAction.OpenTimer(buns.id, 2))
        runCurrent()
        assertEquals(buns.id, viewModel.session.value.activeRecipeId)

        viewModel.onAction(CookSessionAction.CloseRecipe(buns.id))
        runCurrent()
        assertEquals(listOf(pasta.id), viewModel.session.value.recipeIds)
        assertTrue(viewModel.session.value.canAdd)
        // The only recipe stays: closing cook mode is the route's.
        viewModel.onAction(CookSessionAction.CloseRecipe(pasta.id))
        runCurrent()
        assertEquals(listOf(pasta.id), viewModel.session.value.recipeIds)
    }

    @Test
    fun bothRecipesAndTheTabSurviveProcessDeath() = runTest(mainDispatcher.dispatcher) {
        val handle = SavedStateHandle()
        val first = viewModel(handle = handle)
        first.onAction(CookSessionAction.AddRecipe(buns.id))
        first.onAction(CookSessionAction.SelectRecipe(pasta.id))
        first.onAction(CookSessionAction.OpenPicker)
        first.onAction(CookSessionAction.SetQuery("a"))
        runCurrent()

        // A new process restores the SavedStateHandle's values into a new ViewModel.
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        val again = viewModel(handle = restored)

        assertEquals(listOf(pasta.id, buns.id), again.session.value.recipeIds)
        assertEquals(pasta.id, again.session.value.activeRecipeId)
        assertFalse("the session is full, so no picker", again.picker.value.open)
    }

    @Test
    fun aKeyWithARecipeAlongsideStartsWithBoth() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(CookKey(buns.id, alongsideRecipeId = pasta.id, alongsideStep = 1))

        assertEquals(listOf(pasta.id, buns.id), viewModel.session.value.recipeIds)
        assertEquals(buns.id, viewModel.session.value.activeRecipeId)
        assertEquals(1, viewModel.session.value.recipes.first().startStep)
    }
}
