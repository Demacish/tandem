package casa.moller.tandem.feature.recipes.recipeimport

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.testing.data.SampleImports
import casa.moller.tandem.feature.recipes.R

/** Sample import states (Fas 3, import lane; plan §8.9); the pictures never load in tests. */
internal object RecipeImportSamples {
    fun form(aiAvailable: Boolean = true, input: String = "", readOnly: Boolean = false, isDemo: Boolean = false) =
        RecipeImportUiState(input = input, aiAvailable = aiAvailable, readOnly = readOnly, isDemo = isDemo)

    /** A link's result on a server without an AI provider (the reference images before #40). */
    fun result() = RecipeImportUiState(input = SampleImports.pancakes.draft.sourceUrl, aiAvailable = false, result = SampleImports.pancakes)

    /** #40: with an AI provider, "Fördela ingredienserna på stegen" is offered. */
    fun resultWithAi(sorting: Boolean = false, sorted: Boolean = false) =
        result().copy(aiAvailable = true, sortingSteps = sorting, stepsSorted = sorted)

    /** #38: the page read in the app. */
    fun page(sending: Boolean = false, nothingFound: Boolean = false) = RecipeImportUiState(
        input = "https://example.com/recept/kanelbullar",
        pageReading = PageReading("https://example.com/recept/kanelbullar", sending = sending, nothingFound = nothingFound),
    )

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

    // Fas 3's leftovers (lane fas3-rester): the page read in the app (#38) and the AI's step sort (#40).
    PAGE({ Import(RecipeImportSamples.page()) }),
    PAGE_SENDING({ Import(RecipeImportSamples.page(sending = true)) }),
    PAGE_NOTHING_FOUND({ Import(RecipeImportSamples.page(nothingFound = true)) }),
    RESULT_SORT({ Import(RecipeImportSamples.resultWithAi()) }),
    RESULT_SORTING({ Import(RecipeImportSamples.resultWithAi(sorting = true)) }),
    RESULT_SORTED({ Import(RecipeImportSamples.resultWithAi(sorted = true)) }),
}

/** Fas 4: the form offers "Fota recept" (read on the phone) when the shell wires it. */
@Composable
private fun Import(state: RecipeImportUiState) =
    RecipeImportScreen(state = state, onAction = {}, onTakePhoto = {}, onPickFile = {}, onBack = {}, onScanText = {})
