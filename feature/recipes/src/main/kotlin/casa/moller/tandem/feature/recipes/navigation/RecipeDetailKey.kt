package casa.moller.tandem.feature.recipes.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

/**
 * The recipe detail (plan 6.1/6.2). @Serializable so the back stack survives process death.
 *
 * - Compact width: a full-screen destination above the tab bar ([FullScreenDestination]).
 * - Medium/expanded width, opened from the Recipes list ([inListDetail] = true): the detail pane
 *   next to the list (Navigation 3 list-detail scene); opening another recipe from the list
 *   replaces it. Opened from elsewhere (Today, a step's sub-recipe outside the list) it fills the
 *   content area beside the navigation rail.
 *
 * @property recipeId the Tandoor recipe id.
 * @property inListDetail shown as the detail pane of the Recipes list on wide windows.
 */
@Serializable
data class RecipeDetailKey(val recipeId: Long, val inListDetail: Boolean = false) :
    NavKey,
    FullScreenDestination
