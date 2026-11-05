package casa.moller.tandem.feature.recipes.recipeimport

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeImportResult

/**
 * What "Importera recept" shows (Fas 3, import lane; plan §8.9), built by [RecipeImportViewModel].
 *
 * @property input the link or text to import (what was shared, or typed).
 * @property working the server is reading the source (one request at a time; an AI import costs
 *   the space credits, so never two).
 * @property result what the server found: shown as a card before the editor opens; the user can
 *   pick another of its pictures ([RecipeImportResult.imageUrls]).
 * @property problem why the last import gave nothing, as a message; [failure] the server or
 *   network error behind it when there is one (shown with core:ui's error text).
 * @property aiAvailable the space has an AI provider: photos and PDFs can be imported.
 * @property sharedFile a photo or PDF another app shared, not read yet (the route reads it).
 * @property readOnly a guest: nothing can be imported (the recipe could not be saved).
 * @property isDemo demo mode: there is no server to parse with.
 * @property pageReading #38: the server could not read the shared link, so the page is being read
 *   in a WebView in the app (shown with "Läser sidan…" and Avbryt); null otherwise.
 * @property sortingSteps #40: `ai-step-sort` is running for [result].
 * @property stepsSorted #40: [result]'s lines were moved to their steps (Ångra puts them back).
 */
@Immutable
data class RecipeImportUiState(
    val input: String = "",
    val working: Boolean = false,
    val result: RecipeImportResult? = null,
    @StringRes val problem: Int? = null,
    val failure: TandemError? = null,
    val aiAvailable: Boolean = false,
    val sharedFile: SharedFile? = null,
    val readOnly: Boolean = false,
    val isDemo: Boolean = false,
    val pageReading: PageReading? = null,
    val sortingSteps: Boolean = false,
    val stepsSorted: Boolean = false,
) {
    /** The link or text can be read (a text is read on the device, so demo mode too; a link needs the server). */
    val canImport: Boolean
        get() = input.isNotBlank() && !working && !readOnly

    /**
     * #40: "Fördela ingredienserna på stegen" is offered: the space has an AI provider and the
     * result has at least two steps and an ingredient line.
     */
    val canSortSteps: Boolean
        get() = aiAvailable && !readOnly && !isDemo && !working && result != null &&
            result.draft.steps.size >= 2 && result.draft.ingredients.any { !it.isHeader }

    /** A photo or PDF can be chosen. */
    val canImportFile: Boolean
        get() = aiAvailable && !working && !readOnly && !isDemo
}

/**
 * #38 (plan §8.4 step 2): the page behind [url] is read in the app because the server could not.
 *
 * @property sending the page's HTML is with the server.
 * @property nothingFound the last HTML had no recipe (a page that first asks the visitor to
 *   confirm something): the user can confirm in the page and read it again.
 * @property readRequest bumped by "Läs sidan igen": the WebView hands over its HTML once more.
 */
@Immutable
data class PageReading(val url: String, val sending: Boolean = false, val nothingFound: Boolean = false, val readRequest: Int = 0)

/** A photo or PDF another app shared to Tandem (`ACTION_SEND`), by its `content://` uri. */
@Immutable
data class SharedFile(val uri: String, val mimeType: String?)

sealed interface RecipeImportAction {
    data class SetInput(val text: String) : RecipeImportAction

    /** "Hämta receptet": the input as a link when it is one, else as text. */
    data object Import : RecipeImportAction

    /** A photo (already scaled to a JPEG) or a PDF, read by the route. */
    class ImportFile(val bytes: ByteArray, val mimeType: String, val fileName: String) : RecipeImportAction

    /** The route could not read the chosen or shared file. */
    data object FileUnreadable : RecipeImportAction

    /** The shared file was handed to the route (read or failed): not again after a recreation. */
    data object SharedFileTaken : RecipeImportAction

    /** Another of the source's pictures for the recipe. */
    data class ChooseImage(val url: String) : RecipeImportAction

    /** Back to the form (the result is dropped). */
    data object Clear : RecipeImportAction

    /** "Öppna i redigeraren". */
    data object OpenEditor : RecipeImportAction

    /** #38: the WebView finished loading the page; [html] is its markup. */
    class PageLoaded(val html: String) : RecipeImportAction {
        override fun toString(): String = "PageLoaded(${html.length} chars)"
    }

    /** #38: "Läs sidan igen" after the user confirmed something in the page. */
    data object ReadPageAgain : RecipeImportAction

    /** #38: "Avbryt": stop reading the page; the server's answer stands. */
    data object CancelPage : RecipeImportAction

    /** #40: "Fördela ingredienserna på stegen" (one more AI call). */
    data object SortSteps : RecipeImportAction

    /** #40: "Ångra" after the lines were moved. */
    data object UndoSortSteps : RecipeImportAction
}

sealed interface RecipeImportEffect {
    /** The editor opens with [importedDraft] (the editor's saved-draft JSON) as a new recipe. */
    data class OpenEditor(val importedDraft: String) : RecipeImportEffect

    data class Message(@StringRes val text: Int) : RecipeImportEffect
}
