package casa.moller.tandem.feature.recipes.editor

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeEditRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
 * The recipe editor's ViewModels on core:testing's fakes (#22, plan §8.9): a new recipe from an
 * empty form (a name is required; ingredient lines parsed from text; steps and lines moved and
 * removed), a stored recipe edited in place (its step ids kept, an empty step dropped), the kept
 * edit after a conflict, the detail's "Ta bort", a guest who changes nothing, and a form that
 * survives process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val edits = FakeRecipeEditRepository()
    private val recipes = FakeRecipeRepository()
    private val permissions = FakeSpacePermissionsRepository()
    private val pasta = SampleRecipes.MushroomPasta.id

    private fun editor(recipeId: Long?, handle: SavedStateHandle = SavedStateHandle(), importedDraft: String? = null) =
        RecipeEditorViewModel(recipeId, importedDraft, handle, edits, recipes, permissions)

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun <T> TestScope.effectsOf(flow: Flow<T>): List<T> {
        val effects = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(effects) }
        return effects
    }

    // ------------------------------------------------------------------ a new recipe

    @Test
    fun aNewRecipeNeedsANameAndIsCreatedWithItsStepsAndParsedIngredients() = runTest {
        val viewModel = editor(null)
        collect(viewModel.uiState)
        val effects = effectsOf(viewModel.effects)
        assertTrue(viewModel.uiState.value.loaded && viewModel.uiState.value.isNew)
        assertEquals(listOf("g", "dl", "msk", "tsk", "krm", "st").sorted(), viewModel.uiState.value.units.sorted())

        viewModel.onAction(RecipeEditorAction.Save)
        assertTrue(viewModel.uiState.value.nameMissing)
        assertTrue(edits.created.isEmpty())

        viewModel.onAction(RecipeEditorAction.SetName("Fiskgratäng"))
        assertFalse(viewModel.uiState.value.nameMissing)
        viewModel.onAction(RecipeEditorAction.SetServings(4))
        viewModel.onAction(RecipeEditorAction.SetWorkingMinutes(20))
        viewModel.onAction(RecipeEditorAction.AddKeyword(DraftKeyword(id = 2, name = "Vardag")))
        viewModel.onAction(RecipeEditorAction.AddKeyword(DraftKeyword(name = " Fisk ")))
        viewModel.onAction(RecipeEditorAction.AddKeyword(DraftKeyword(name = "fisk")))
        assertEquals(listOf("Vardag", "Fisk"), viewModel.uiState.value.draft.keywords.map { it.name })
        assertFalse("Vardag" in viewModel.uiState.value.keywordSuggestions.map { it.name })

        viewModel.onAction(RecipeEditorAction.AddStep)
        viewModel.onAction(RecipeEditorAction.AddStep)
        viewModel.onAction(RecipeEditorAction.SetStepInstruction(0, "Skär fisken."))
        viewModel.onAction(RecipeEditorAction.AddIngredient(0, "600 g torskfilé, tinad"))
        viewModel.onAction(RecipeEditorAction.AddIngredient(0, "salt"))
        viewModel.onAction(RecipeEditorAction.AddHeader(0))
        viewModel.onAction(RecipeEditorAction.SetIngredientNote(0, 2, "Sås"))
        viewModel.onAction(RecipeEditorAction.AddIngredient(0))
        viewModel.onAction(RecipeEditorAction.SetIngredientAmount(0, 3, "2,5"))
        viewModel.onAction(RecipeEditorAction.SetIngredientUnit(0, 3, "dl"))
        viewModel.onAction(RecipeEditorAction.SetIngredientFood(0, 3, "vispgrädde"))
        viewModel.onAction(RecipeEditorAction.MoveIngredient(0, 3, up = true))
        val step = viewModel.uiState.value.draft.steps[0]
        assertEquals(listOf("torskfilé", "salt", "vispgrädde", null), step.ingredients.map { it.food })
        assertEquals(600.0, step.ingredients[0].amount, 0.0)
        assertEquals("g", step.ingredients[0].unit)
        assertEquals("tinad", step.ingredients[0].note)
        assertTrue(step.ingredients[1].noAmount)
        assertEquals(2.5, step.ingredients[2].amount, 0.0)
        assertTrue(step.ingredients[3].isHeader)
        assertEquals("2.5", viewModel.uiState.value.amountText(0, 2))

        viewModel.onAction(RecipeEditorAction.Save)
        val created = edits.created.single()
        assertEquals("Fiskgratäng", created.name)
        assertEquals("the empty second step is dropped", 1, created.steps.size)
        assertEquals(listOf("torskfilé", "salt", "vispgrädde", null), created.steps[0].ingredients.map { it.food })
        val saved = effects.filterIsInstance<RecipeEditorEffect.Saved>().single()
        assertEquals(R.string.recipe_editor_saved_offline, saved.text)
        assertTrue(saved.isNew && saved.recipeId < 0)
    }

    @Test
    fun stepsAreMovedAndRemoved() = runTest {
        val viewModel = editor(null)
        collect(viewModel.uiState)
        repeat(3) { viewModel.onAction(RecipeEditorAction.AddStep) }
        viewModel.onAction(RecipeEditorAction.SetStepName(0, "A"))
        viewModel.onAction(RecipeEditorAction.SetStepName(1, "B"))
        viewModel.onAction(RecipeEditorAction.SetStepName(2, "C"))
        viewModel.onAction(RecipeEditorAction.MoveStep(2, up = true))
        viewModel.onAction(RecipeEditorAction.MoveStep(0, up = true))
        assertEquals(listOf("A", "C", "B"), viewModel.uiState.value.draft.steps.map { it.name })
        viewModel.onAction(RecipeEditorAction.RemoveStep(1))
        assertEquals(listOf("A", "B"), viewModel.uiState.value.draft.steps.map { it.name })
    }

    // ------------------------------------------------------------------ a stored recipe

    @Test
    fun aStoredRecipeIsEditedInPlaceWithItsStepIds() = runTest {
        val viewModel = editor(pasta)
        collect(viewModel.uiState)
        val effects = effectsOf(viewModel.effects)
        val state = viewModel.uiState.value
        assertTrue(state.loaded && !state.isNew)
        assertEquals(SampleRecipes.MushroomPasta.name, state.draft.name)
        assertEquals(SampleRecipes.MushroomPasta.steps.map { it.id }, state.draft.steps.map { it.id })

        viewModel.onAction(RecipeEditorAction.SetName("Krämig svamppasta med timjan"))
        viewModel.onAction(RecipeEditorAction.Save)
        val (id, draft) = edits.updated.single()
        assertEquals(pasta, id)
        assertEquals("Krämig svamppasta med timjan", draft.name)
        assertEquals(SampleRecipes.MushroomPasta.steps.map { it.id }, draft.steps.map { it.id })
        val saved = effects.filterIsInstance<RecipeEditorEffect.Saved>().single()
        assertFalse(saved.isNew)
        assertEquals(pasta, saved.recipeId)
    }

    @Test
    fun anUnknownRecipeCannotBeEdited() = runTest {
        val viewModel = editor(4_242L)
        collect(viewModel.uiState)
        assertTrue(viewModel.uiState.value.unknownRecipe)
        assertFalse(viewModel.uiState.value.loaded)
    }

    @Test
    fun aKeptEditIsShownAndCanBeDropped() = runTest {
        edits.statuses.value = mapOf(pasta to RecipeEditStatus.CONFLICT)
        val viewModel = editor(pasta)
        collect(viewModel.uiState)
        val effects = effectsOf(viewModel.effects)
        assertTrue(viewModel.uiState.value.keptEdit)
        viewModel.onAction(RecipeEditorAction.DiscardKeptEdit)
        assertEquals(listOf(pasta), edits.discarded)
        assertFalse(viewModel.uiState.value.keptEdit)
        assertEquals(R.string.recipe_editor_kept_edit_discarded, (effects.single() as RecipeEditorEffect.Message).text)
    }

    @Test
    fun aFailedSaveKeepsTheForm() = runTest {
        edits.failure = RecipeEditProblem.PHOTO_UNREADABLE.toError()
        val viewModel = editor(null)
        collect(viewModel.uiState)
        val effects = effectsOf(viewModel.effects)
        viewModel.onAction(RecipeEditorAction.SetName("Fiskgratäng"))
        viewModel.onAction(RecipeEditorAction.SetPhoto("content://photos/1"))
        viewModel.onAction(RecipeEditorAction.Save)
        assertEquals(R.string.recipe_editor_photo_unreadable, (effects.single() as RecipeEditorEffect.Message).text)
        assertFalse(viewModel.uiState.value.saving)
        assertEquals("Fiskgratäng", viewModel.uiState.value.draft.name)
        assertEquals("content://photos/1", viewModel.currentPhoto)
    }

    @Test
    fun theFormSurvivesProcessDeath() = runTest {
        val handle = SavedStateHandle()
        val first = editor(null, handle)
        first.onAction(RecipeEditorAction.SetName("Fiskgratäng"))
        first.onAction(RecipeEditorAction.AddStep)
        first.onAction(RecipeEditorAction.AddIngredient(0, "2 dl grädde"))
        val second = editor(null, handle)
        collect(second.uiState)
        assertEquals("Fiskgratäng", second.uiState.value.draft.name)
        assertEquals("grädde", second.uiState.value.draft.steps[0].ingredients[0].food)
    }

    // ------------------------------------------------------------------ the detail's entrances

    @Test
    fun theDetailOffersEditAndDeleteToMembersOnly() = runTest {
        val actions = RecipeEditActionsViewModel(pasta, edits, recipes, permissions)
        collect(actions.uiState)
        val effects = effectsOf(actions.effects)
        assertTrue(actions.uiState.value.canEdit)
        assertEquals(RecipeEditStatus.NONE, actions.uiState.value.status)
        actions.delete()
        assertEquals(listOf(pasta), edits.deleted)
        assertEquals(SampleRecipes.MushroomPasta.name, (effects.single() as RecipeEditActionsEffect.Deleted).name)

        permissions.setGuest(true)
        assertFalse(actions.uiState.value.canEdit)
    }

    @Test
    fun aGuestChangesNothing() = runTest {
        permissions.setGuest(true)
        edits.readOnly = true
        val viewModel = editor(null)
        collect(viewModel.uiState)
        val effects = effectsOf(viewModel.effects)
        assertTrue(viewModel.uiState.value.readOnly)
        viewModel.onAction(RecipeEditorAction.SetName("Fiskgratäng"))
        viewModel.onAction(RecipeEditorAction.Save)
        assertTrue(edits.created.isEmpty())
        assertEquals(R.string.recipe_editor_failed, (effects.single() as RecipeEditorEffect.Message).text)
        assertNull(RecipeEditProblem.of(DataProblem.READ_ONLY.toError()))
    }
}
