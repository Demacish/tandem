package casa.moller.tandem.feature.recipes.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.testing.data.FakeRecipeEditRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.TestFrame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas 4's completeness check: the editor's rows are keyed by the draft's own row id, so a moved or
 * removed row leaves the others' nodes alone. TalkBack's focus sits on a semantics node, so the same
 * node id before and after is what "the focus follows the step" means; without the keys the rows
 * stay in place and only their contents change. The form and the ViewModel are the real ones.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeEditorRowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val soup = RecipeDraft(
        name = "Soppa",
        steps = listOf(
            DraftStep(
                name = CHOP,
                ingredients = listOf(DraftIngredient(food = "lök", note = ONION_NOTE), DraftIngredient(food = "morot", note = CARROT_NOTE)),
            ),
            DraftStep(name = BOIL),
            DraftStep(name = SERVE),
        ),
    )

    private val viewModel = RecipeEditorViewModel(
        recipeId = null,
        importedDraft = SavedDraft.encode(soup),
        sourcePath = null,
        handle = SavedStateHandle(),
        edits = FakeRecipeEditRepository(),
        recipes = FakeRecipeRepository(),
        permissions = FakeSpacePermissionsRepository(),
    )

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun show() {
        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            TestFrame {
                RecipeEditorLayout(state, viewModel::onAction, onTakePhoto = {}, onPickPhoto = {}, onBack = {}, keyboardOpen = false)
            }
        }
        composeRule.waitForIdle()
    }

    private fun field(text: String): SemanticsNodeInteraction = composeRule.onNode(hasSetTextAction() and hasText(text))

    private fun SemanticsNodeInteraction.id(): Int = fetchSemanticsNode().id

    private fun described(description: String): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(description)

    /** Through the action, as TalkBack does it: the form is taller than the window. */
    private fun press(description: String) {
        described(description).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun steps(): List<String> = viewModel.uiState.value.draft.steps.map { it.name }

    @Test
    fun aMovedStepKeepsItsNodes() {
        show()
        val name = field(BOIL).id()
        val button = described(string(R.string.recipe_editor_step_move_up, 2)).id()

        press(string(R.string.recipe_editor_step_move_up, 2))

        assertEquals(listOf(BOIL, CHOP, SERVE), steps())
        assertEquals("the step's field", name, field(BOIL).id())
        assertEquals("the button TalkBack stands on", button, described(string(R.string.recipe_editor_step_move_up, 1)).id())
    }

    @Test
    fun aRemovedStepLeavesTheOthersNodesAlone() {
        show()
        val boil = field(BOIL).id()
        val serve = field(SERVE).id()

        press(string(R.string.recipe_editor_step_remove, 1))

        assertEquals(listOf(BOIL, SERVE), steps())
        assertEquals(boil, field(BOIL).id())
        assertEquals(serve, field(SERVE).id())
    }

    @Test
    fun aMovedLineKeepsItsNodes() {
        show()
        val carrot = field(CARROT_NOTE).id()
        val button = described(string(R.string.recipe_editor_ingredient_move_up, 2, 1)).id()

        press(string(R.string.recipe_editor_ingredient_move_up, 2, 1))

        assertEquals(listOf(CARROT_NOTE, ONION_NOTE), viewModel.uiState.value.draft.steps[0].ingredients.map { it.note })
        assertEquals(carrot, field(CARROT_NOTE).id())
        assertEquals(button, described(string(R.string.recipe_editor_ingredient_move_up, 1, 1)).id())
    }

    @Test
    fun aRemovedLineLeavesTheOthersNodesAlone() {
        show()
        val carrot = field(CARROT_NOTE).id()

        press(string(R.string.recipe_editor_ingredient_remove, 1, 1))

        assertEquals(carrot, field(CARROT_NOTE).id())
    }

    /** "Ny ingrediens" keeps what is half-written with its step, not with the place the step had. */
    @Test
    fun halfWrittenTextMovesWithItsStep() {
        show()
        composeRule.onAllNodes(hasSetTextAction() and hasText(string(R.string.recipe_editor_ingredient_quick)))[1]
            .performTextReplacement(HALF_WRITTEN)
        composeRule.waitForIdle()
        val written = field(HALF_WRITTEN).id()

        press(string(R.string.recipe_editor_step_move_up, 2))

        assertEquals(written, field(HALF_WRITTEN).id())
    }

    private companion object {
        const val CHOP = "Hacka"
        const val BOIL = "Koka"
        const val SERVE = "Servera"
        const val ONION_NOTE = "grovt hackad"
        const val CARROT_NOTE = "i slantar"
        const val HALF_WRITTEN = "2 dl grädde"
    }
}
