package casa.moller.tandem.feature.recipes.recipeimport

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.feature.recipes.R

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
 * @property sharedPages several images another app shared, not read yet: pages of one recipe.
 * @property readOnly a guest: nothing can be imported (the recipe could not be saved).
 * @property isDemo demo mode: there is no server to parse with.
 * @property pageReading #38: the server could not read the shared link, so the page is being read
 *   in a WebView in the app (shown with "Läser sidan…" and Avbryt); null otherwise.
 * @property sortingSteps #40: `ai-step-sort` is running for [result].
 * @property stepsSorted #40: [result]'s lines were moved to their steps (Ångra puts them back).
 * @property pages photographed pages waiting to be sent as one recipe (plan §8.4: several pages
 *   become one PDF), as cache files; empty when none.
 * @property sourcePath the sent photo or PDF, kept in the cache for this session: the review shows
 *   it beside the AI's reading (plan §8.4); null for a link or a text.
 * @property readingText the working message is about a text read on the phone, not the server.
 * @property scanned the text came from "Fota recept": it stays on the phone (plan §8.4 #39).
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
    val sharedPages: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val isDemo: Boolean = false,
    val pageReading: PageReading? = null,
    val sortingSteps: Boolean = false,
    val stepsSorted: Boolean = false,
    val pages: List<String> = emptyList(),
    val sourcePath: String? = null,
    val readingText: Boolean = false,
    val scanned: Boolean = false,
) {
    /** What to do about [problem]; null when there is nothing to add. */
    @get:StringRes
    val problemHint: Int?
        get() = when (problem) {
            null, R.string.recipe_import_read_only -> null
            R.string.recipe_import_file_too_large -> R.string.recipe_import_hint_file_too_large
            R.string.recipe_import_file_unreadable -> R.string.recipe_import_hint_file_unreadable
            R.string.recipe_import_nothing_in_file -> R.string.recipe_import_hint_nothing_in_file
            R.string.recipe_import_ai_unavailable -> R.string.recipe_import_hint_ai_unavailable
            R.string.recipe_import_demo_problem -> R.string.recipe_import_hint_demo
            R.string.recipe_import_page_unavailable -> R.string.recipe_import_hint_page_unavailable
            else -> R.string.recipe_import_problem_hint
        }

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

    /**
     * Fas 4 review (fix-data; plan §8.4 step 3): the link and the page gave nothing, and there is a
     * text left to try: the shared caption, or with an AI provider also the page's visible text.
     */
    val canInterpretText: Boolean
        get() = pageReading?.takeIf { it.nothingFound && !it.sending }
            ?.let { it.caption.isNotBlank() || (aiAvailable && it.pageText.isNotBlank()) } == true
}

/**
 * #38 (plan §8.4 step 2): the page behind [url] is read in the app because the server could not.
 *
 * @property sending the page's HTML is with the server.
 * @property nothingFound the last HTML had no recipe (a page that first asks the visitor to
 *   confirm something): the user can confirm in the page and read it again.
 * @property readRequest bumped by "Läs sidan igen": the WebView hands over its HTML once more.
 * @property caption what was shared together with the link (an Instagram caption often IS the recipe).
 * @property pageText the page's visible text as last read.
 */
@Immutable
data class PageReading(
    val url: String,
    val sending: Boolean = false,
    val nothingFound: Boolean = false,
    val readRequest: Int = 0,
    val caption: String = "",
    val pageText: String = "",
) {
    /**
     * What the WebView loads: the app allows no cleartext, so an `http://` link is tried as
     * `https://` (nearly every recipe site has it); [url] stays what the server is told.
     */
    val loadUrl: String
        get() = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring("http://".length) else url
}

/** A photo or PDF another app shared to Tandem (`ACTION_SEND`), by its `content://` uri. */
@Immutable
data class SharedFile(val uri: String, val mimeType: String?)

sealed interface RecipeImportAction {
    data class SetInput(val text: String) : RecipeImportAction

    /** "Hämta receptet": the input as a link when it is one, else as text. */
    data object Import : RecipeImportAction

    /**
     * A photo (already scaled to a JPEG) or a PDF, read by the route.
     *
     * @property sourcePath the copy kept in the cache for the review ([RecipeImportUiState.sourcePath]).
     */
    class ImportFile(val bytes: ByteArray, val mimeType: String, val fileName: String, val sourcePath: String? = null) : RecipeImportAction

    /** A photographed page, scaled to a JPEG in the cache at [path] (plan §8.4: several pages, one recipe). */
    data class AddPage(val path: String) : RecipeImportAction

    /** "Ta bort sista": the last page is dropped. */
    data object RemoveLastPage : RecipeImportAction

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

    /** #38: the WebView finished loading the page; [html] is its markup, [text] what it shows. */
    class PageLoaded(val html: String, val text: String = "") : RecipeImportAction {
        override fun toString(): String = "PageLoaded(${html.length} chars)"
    }

    /** #38: "Läs sidan igen" after the user confirmed something in the page. */
    data object ReadPageAgain : RecipeImportAction

    /** #38: "Avbryt": back to the form, no problem to report: the user chose it. */
    data object CancelPage : RecipeImportAction

    /** Plan §8.4 step 3: "Tolka texten med AI" (one AI call), without a provider "Läs den delade texten". */
    data object InterpretText : RecipeImportAction

    /** #38: the page could not be loaded at all, or the WebView went away: nothing was read. */
    data object PageUnavailable : RecipeImportAction

    /** #40: "Fördela ingredienserna på stegen" (one more AI call). */
    data object SortSteps : RecipeImportAction

    /** #40: "Ångra" after the lines were moved. */
    data object UndoSortSteps : RecipeImportAction
}

sealed interface RecipeImportEffect {
    /** The editor opens with [importedDraft] (the editor's saved-draft JSON) as a new recipe, and the sent file at [sourcePath]. */
    data class OpenEditor(val importedDraft: String, val sourcePath: String? = null) : RecipeImportEffect

    data class Message(@StringRes val text: Int) : RecipeImportEffect
}
