package casa.moller.tandem.feature.recipes.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

/**
 * Fas 3 (grund lane): the recipe editor (#22, plan §8.9), full screen (a form). [recipeId] null
 * creates a new recipe; a stored recipe (also one created in the app, negative id) is edited in
 * place. The shell shows it through `editor/RecipeEditorRoutes.kt` `RecipeEditorRoute`.
 *
 * @property importedDraft Fas 3 (import lane): a new recipe that starts from an import
 *   (`RecipeImportKey`), as the editor's saved-draft JSON (`SavedDraft`); null for an empty form.
 */
@Serializable
data class RecipeEditorKey(val recipeId: Long? = null, val importedDraft: String? = null) :
    NavKey,
    FullScreenDestination
