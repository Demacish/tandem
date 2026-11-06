package casa.moller.tandem.feature.recipes.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

/**
 * Fas 3 (import lane): "Importera recept" (plan §8.9), full screen. Opened empty from the recipe
 * list, or with what another app shared to Tandem (`ACTION_SEND`): [sharedText] (a link or a
 * text) or [sharedFileUri] (a photo or a PDF, `content://`, with its [sharedMimeType]). The
 * result opens the editor through `RecipeEditorKey.importedDraft`.
 *
 * @property sharedPageUris several images shared at once (`ACTION_SEND_MULTIPLE`): the pages of one recipe.
 * @property scanned [sharedText] comes from "Fota recept": it is read on the phone, even with a link in it.
 */
@Serializable
data class RecipeImportKey(
    val sharedText: String? = null,
    val sharedFileUri: String? = null,
    val sharedMimeType: String? = null,
    val sharedPageUris: List<String> = emptyList(),
    val scanned: Boolean = false,
) : NavKey,
    FullScreenDestination
