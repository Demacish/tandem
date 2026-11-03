package casa.moller.tandem.feature.recipes.recipeimport

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.testing.data.SampleImports
import casa.moller.tandem.feature.recipes.R

/** Sample import states (Fas 3, import lane; plan §8.9); the pictures never load in tests. */
internal object RecipeImportSamples {
    fun form(aiAvailable: Boolean = true, input: String = "", readOnly: Boolean = false, isDemo: Boolean = false) =
        RecipeImportUiState(input = input, aiAvailable = aiAvailable, readOnly = readOnly, isDemo = isDemo)

    fun result() = RecipeImportUiState(input = SampleImports.pancakes.draft.sourceUrl, aiAvailable = true, result = SampleImports.pancakes)

    fun problem() = RecipeImportUiState(
        input = "https://example.com/annat",
        aiAvailable = false,
        problem = R.string.recipe_import_nothing_found,
    )

    fun offline() = RecipeImportUiState(
        input = "https://example.com/recept",
        aiAvailable = true,
        problem = R.string.recipe_import_failed,
        failure = TandemError.NetworkUnavailable,
    )
}

/** Every import state, for the screenshot and accessibility tests (one list, so both cover the same). */
internal enum class RecipeImportShot(val content: @Composable () -> Unit) {
    FORM({ Import(RecipeImportSamples.form()) }),
    FORM_NO_AI({ Import(RecipeImportSamples.form(aiAvailable = false, input = "https://example.com/recept/pannkakor")) }),
    FORM_DEMO({ Import(RecipeImportSamples.form(aiAvailable = false, isDemo = true)) }),
    FORM_GUEST({ Import(RecipeImportSamples.form(readOnly = true)) }),
    WORKING({ Import(RecipeImportSamples.form(input = "https://example.com/recept/pannkakor").copy(working = true)) }),
    RESULT({ Import(RecipeImportSamples.result()) }),
    PROBLEM({ Import(RecipeImportSamples.problem()) }),
    OFFLINE({ Import(RecipeImportSamples.offline()) }),
}

@Composable
private fun Import(state: RecipeImportUiState) =
    RecipeImportScreen(state = state, onAction = {}, onTakePhoto = {}, onPickFile = {}, onBack = {})
