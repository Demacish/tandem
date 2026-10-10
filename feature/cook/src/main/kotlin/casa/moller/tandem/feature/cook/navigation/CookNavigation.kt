package casa.moller.tandem.feature.cook.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

/**
 * Phase 1b placeholder: cook mode for one recipe (plan 6.2 "Kokläge"). Full screen on every width
 * (no tab bar, no rail). Not wired into the shell yet; Phase 1b adds `CookRoute` and the shell
 * entry, and the recipe detail's `onStartCooking` pushes this key.
 *
 * @property recipeId the recipe to cook.
 * @property servings the servings chosen on the detail screen (amounts are scaled to it).
 */
@Serializable
data class CookKey(val recipeId: Long, val servings: Double) : NavKey, FullScreenDestination {
    override val hidesNavigationOnAllWidths: Boolean
        get() = true
}
