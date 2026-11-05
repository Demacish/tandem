package casa.moller.tandem.feature.cook.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

/**
 * Cook mode for one recipe (plan 6.2 "Kokläge", features #6–#8). Full screen on every width (no
 * tab bar, no rail). @Serializable so the back stack survives process death; the current page
 * after that lives in the cook screen's `SavedStateHandle`, [startStep] is only where it opens.
 *
 * Pushed by the recipe detail's and Today's `onStartCooking`, and by the app when a timer
 * notification is tapped (recipe and step of the timer).
 *
 * @property recipeId the recipe to cook.
 * @property servings the servings chosen on the detail screen (amounts are scaled to it); null
 *   = the recipe's remembered choice (`RecipeRepository.observeServingsChoice`), else its own.
 * @property startStep the 0-based step page to open; the "Klart!" page comes after the last step.
 * @property alongsideRecipeId Fas 4 (two recipes at once, plan 8.1): a recipe that is already
 *   being cooked (it has running timers, [cookKeyAlongside]); cook mode opens with it as the first
 *   tab and [recipeId] as the second, selected one. Null: one recipe.
 * @property alongsideStep the page the [alongsideRecipeId] tab opens at (its newest timer's step).
 */
@Serializable
data class CookKey(
    val recipeId: Long,
    val servings: Double? = null,
    val startStep: Int = 0,
    val alongsideRecipeId: Long? = null,
    val alongsideStep: Int = 0,
) : NavKey,
    FullScreenDestination {
    init {
        require(startStep >= 0) { "startStep is 0-based" }
        require(servings == null || servings > 0.0) { "servings are positive" }
        require(alongsideStep >= 0) { "alongsideStep is 0-based" }
        require(alongsideRecipeId != recipeId) { "the alongside recipe is another recipe" }
    }

    /** Every recipe this cook mode opens with, in tab order. */
    val recipeIds: List<Long>
        get() = listOfNotNull(alongsideRecipeId, recipeId)

    override val hidesNavigationOnAllWidths: Boolean
        get() = true
}

/**
 * A page ([step], 0-based) the open cook mode should show for [recipeId], one of its recipes: a
 * tapped notification or timer of it (the shell's `openCook`). Cook mode selects the recipe's tab
 * (Fas 4, two recipes at once) and turns to the page, keeping servings and the "Klart!" draft.
 */
data class CookPageRequest(val recipeId: Long, val step: Int)

/**
 * All running timers (plan 8.1 "Löpande timers syns i alla skärmar"): the expanded timer HUD,
 * opened from cook mode's timer bar or from a notification of a timer without a recipe. Not the
 * ringing alarm itself: that is its own activity shown over the lock screen (core:timer/:app).
 */
@Serializable
data object TimersKey : NavKey
