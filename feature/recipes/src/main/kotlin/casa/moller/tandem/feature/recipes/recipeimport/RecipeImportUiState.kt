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
) {
    /** The link or text can be sent. */
    val canImport: Boolean
        get() = input.isNotBlank() && !working && !readOnly && !isDemo

    /** A photo or PDF can be chosen. */
    val canImportFile: Boolean
        get() = aiAvailable && !working && !readOnly && !isDemo
}

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
}

sealed interface RecipeImportEffect {
    /** The editor opens with [importedDraft] (the editor's saved-draft JSON) as a new recipe. */
    data class OpenEditor(val importedDraft: String) : RecipeImportEffect

    data class Message(@StringRes val text: Int) : RecipeImportEffect
}
