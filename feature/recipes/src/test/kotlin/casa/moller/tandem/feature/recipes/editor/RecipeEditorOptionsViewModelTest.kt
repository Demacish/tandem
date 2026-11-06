package casa.moller.tandem.feature.recipes.editor

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.PropertyType
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeProperty
import casa.moller.tandem.core.model.recipe.StepFile
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeEditRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 4 (#22, decision B9): the editor's controls beyond the plain form: the three flags, the
 * sub-recipe sheet (which recipes it offers), a step's file and the recipe's own nutrition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditorOptionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val edits = FakeRecipeEditRepository()
    private val recipes = FakeRecipeRepository()
    private val permissions = FakeSpacePermissionsRepository()
    private val pasta = SampleRecipes.MushroomPasta.id
    private val energy = PropertyType(id = 1, name = "Energi", unit = "kcal")
    private val protein = PropertyType(id = 2, name = "Protein", unit = "g")

    private fun editor(recipeId: Long?, handle: SavedStateHandle = SavedStateHandle()) =
        RecipeEditorViewModel(recipeId, null, null, handle, edits, recipes, permissions)

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun <T> TestScope.effectsOf(flow: Flow<T>): List<T> {
        val effects = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(effects) }
        return effects
    }

    private fun TestScope.newForm(): RecipeEditorViewModel = editor(null).also { collect(it.uiState) }

    @Test
    fun theFlagsAreSaved() = runTest {
        val viewModel = editor(pasta)
        collect(viewModel.uiState)
        viewModel.onAction(RecipeEditorAction.SetPrivate(true))
        viewModel.onAction(RecipeEditorAction.SetStepShowAsHeader(0, true))
        viewModel.onAction(RecipeEditorAction.SetStepShowIngredients(0, false))
        assertTrue(viewModel.uiState.value.hasChanges)
        viewModel.onAction(RecipeEditorAction.Save)
        val saved = edits.updated.single().second
        assertTrue(saved.isPrivate)
        assertTrue(saved.steps[0].showAsHeader)
        assertFalse(saved.steps[0].showIngredientsTable)
    }

    @Test
    fun theSheetOffersTheOtherRecipesThatAreOnTheServer() = runTest {
        val viewModel = editor(pasta)
        collect(viewModel.uiState)
        assertNull(viewModel.uiState.value.linkPicker)
        viewModel.onAction(RecipeEditorAction.OpenStepLink(1))
        val picker = assertNotNullPicker(viewModel)
        assertEquals(1, picker.step)
        assertFalse("not itself", picker.results.any { it.id == pasta })
        assertTrue(picker.results.isNotEmpty())
        assertTrue("never a temporary id", picker.results.all { it.id > 0 })

        viewModel.onAction(RecipeEditorAction.CloseStepLink)
        assertNull(viewModel.uiState.value.linkPicker)
    }

    @Test
    fun aRecipeThatUsesThisOneIsNotOffered() = runTest {
        val viewModel = editor(pasta)
        collect(viewModel.uiState)
        viewModel.onAction(RecipeEditorAction.OpenStepLink(0))
        val other = assertNotNullPicker(viewModel).results.first().id
        edits.recipesUsing.value = mapOf(pasta to setOf(other))
        assertFalse(assertNotNullPicker(viewModel).results.any { it.id == other })
    }

    @Test
    fun theSheetSearchesAndAChoiceClosesIt() = runTest {
        val viewModel = editor(pasta)
        collect(viewModel.uiState)
        viewModel.onAction(RecipeEditorAction.OpenStepLink(0))
        val target = assertNotNullPicker(viewModel).results.first()
        viewModel.onAction(RecipeEditorAction.SearchStepLink(target.name))
        assertEquals(target.name, assertNotNullPicker(viewModel).query)
        assertTrue(assertNotNullPicker(viewModel).results.any { it.id == target.id })

        viewModel.onAction(RecipeEditorAction.SetStepRecipe(0, StepRecipeRef(target.id, target.name)))
        assertNull(viewModel.uiState.value.linkPicker)
        assertEquals(target.id, viewModel.uiState.value.draft.steps[0].stepRecipe?.recipeId)

        viewModel.onAction(RecipeEditorAction.SetStepRecipe(0, null))
        assertNull(viewModel.uiState.value.draft.steps[0].stepRecipe)
    }

    @Test
    fun theSheetAndTheLinkSurviveProcessDeath() = runTest {
        val handle = SavedStateHandle()
        val viewModel = editor(pasta, handle)
        collect(viewModel.uiState)
        viewModel.onAction(RecipeEditorAction.SetStepRecipe(0, StepRecipeRef(7, "Deg")))
        viewModel.onAction(RecipeEditorAction.SetStepFile(0, StepFile(name = "blad.pdf", uri = "content://example/1")))
        viewModel.onAction(RecipeEditorAction.OpenStepLink(1))

        val restored = editor(pasta, handle)
        collect(restored.uiState)
        val step = restored.uiState.value.draft.steps[0]
        assertEquals(StepRecipeRef(7, "Deg"), step.stepRecipe)
        assertEquals(StepFile(name = "blad.pdf", uri = "content://example/1"), step.file)
        assertEquals(1, restored.uiState.value.linkPicker?.step)
        assertEquals(listOf("content://example/1"), restored.chosenFiles)
    }

    @Test
    fun aStepWithOnlyASubRecipeStaysAndGoesWithAnUndo() = runTest {
        val viewModel = newForm()
        viewModel.onAction(RecipeEditorAction.SetName("Middag"))
        viewModel.onAction(RecipeEditorAction.AddStep)
        viewModel.onAction(RecipeEditorAction.SetStepRecipe(0, StepRecipeRef(7, "Deg")))
        val effects = effectsOf(viewModel.effects)
        viewModel.onAction(RecipeEditorAction.RemoveStep(0))
        assertTrue(effects.single() is RecipeEditorEffect.Removed)
        viewModel.onAction(RecipeEditorAction.UndoRemove)
        viewModel.onAction(RecipeEditorAction.Save)
        assertEquals(StepRecipeRef(7, "Deg"), edits.created.single().steps.single().stepRecipe)
    }

    @Test
    fun theFormAsksForThePropertyTypesWhenItOpens() = runTest {
        newForm()
        assertEquals(1, edits.propertyTypeRefreshes)
    }

    @Test
    fun aTypeInUseIsNoLongerOfferedAndComesBackWhenRemoved() = runTest {
        edits.propertyTypes.value = listOf(energy, protein)
        val viewModel = newForm()
        assertEquals(listOf(energy, protein), viewModel.uiState.value.propertyTypes)
        viewModel.onAction(RecipeEditorAction.AddProperty(energy))
        assertEquals(listOf(protein), viewModel.uiState.value.propertyTypes)
        assertEquals("", viewModel.uiState.value.propertyText(energy.id))
        assertTrue(viewModel.uiState.value.hasChanges)
        viewModel.onAction(RecipeEditorAction.RemoveProperty(energy.id))
        assertEquals(listOf(energy, protein), viewModel.uiState.value.propertyTypes)
        assertFalse("nothing is left of it", viewModel.uiState.value.hasChanges)
    }

    @Test
    fun aValueIsTypedWithADecimalCommaAndABlankOneIsLeftOut() = runTest {
        val viewModel = newForm()
        viewModel.onAction(RecipeEditorAction.SetName("Soppa"))
        viewModel.onAction(RecipeEditorAction.AddProperty(energy))
        viewModel.onAction(RecipeEditorAction.AddProperty(protein))
        viewModel.onAction(RecipeEditorAction.SetPropertyAmount(protein.id, "12,5"))
        assertEquals("12,5", viewModel.uiState.value.propertyText(protein.id))
        viewModel.onAction(RecipeEditorAction.Save)
        val expected = RecipeProperty(typeId = protein.id, typeName = "Protein", unit = "g", amount = 12.5)
        assertEquals(listOf(expected), edits.created.single().properties)
    }

    @Test
    fun aStoredZeroStaysAndTheTextSurvivesAMovedStep() = runTest {
        edits.drafts.value = mapOf(
            pasta to RecipeDraft(
                name = "Pasta",
                steps = listOf(DraftStep(id = 1, name = "A"), DraftStep(id = 2, name = "B")),
                properties = listOf(RecipeProperty(id = 1, typeId = 1, typeName = "Energi", amount = 0.0)),
            ),
        )
        val viewModel = editor(pasta)
        collect(viewModel.uiState)
        assertEquals("0", viewModel.uiState.value.propertyText(1))
        viewModel.onAction(RecipeEditorAction.SetPropertyAmount(1, ""))
        viewModel.onAction(RecipeEditorAction.MoveStep(0, up = false))
        assertEquals("", viewModel.uiState.value.propertyText(1))
        viewModel.onAction(RecipeEditorAction.Save)
        assertEquals("blank means no value", emptyList<RecipeProperty>(), edits.updated.single().second.properties)
    }

    @Test
    fun theDraftKeepsEverythingThroughItsSavedForm() {
        val draft = RecipeDraft(
            name = "Pasta",
            steps = listOf(
                DraftStep(
                    id = 1,
                    showAsHeader = true,
                    showIngredientsTable = false,
                    stepRecipe = StepRecipeRef(7, null),
                    file = StepFile(id = 3, name = "a.pdf"),
                ),
            ),
            isPrivate = true,
            properties = listOf(RecipeProperty(4, 1, "Energi", "kcal", 1.5)),
        )
        assertEquals(draft, SavedDraft.decode(SavedDraft.encode(draft)))
        assertNull(SavedDraft.decode(SavedDraft.encode(RecipeDraft()))?.properties)
        assertEquals(emptyList<RecipeProperty>(), SavedDraft.decode(SavedDraft.encode(RecipeDraft(properties = emptyList())))?.properties)
    }

    private fun assertNotNullPicker(viewModel: RecipeEditorViewModel): StepLinkPicker {
        val picker = viewModel.uiState.value.linkPicker
        assertNotNull(picker)
        return picker!!
    }
}
