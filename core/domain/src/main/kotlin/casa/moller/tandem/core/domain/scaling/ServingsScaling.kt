package casa.moller.tandem.core.domain.scaling

import casa.moller.tandem.core.model.recipe.Recipe

/**
 * The scaling factor of a recipe, as Tandoor's web computes it (FACTS.md "Fas 1 l" 1):
 * `chosen servings / max(recipe.servings, 1)`.
 *
 * Tandoor's `servings_text` ("portioner", "bitar", "4 personer") is only a label: neither the
 * server nor the web reads a number from it, so the factor never does either (a number in the
 * text would make the app scale differently from the web). [ServingsText] writes the label.
 */
object ServingsScaling {
    /** The servings the recipe's amounts are written for: `max(servings, 1)`. */
    fun baseServings(recipeServings: Int): Int = maxOf(recipeServings, 1)

    /** The servings the screen starts with: the user's remembered choice, else the recipe's. */
    fun initialServings(recipe: Recipe, remembered: Double?): Double =
        remembered?.takeIf { it.isFinite() && it > 0.0 } ?: baseServings(recipe.servings).toDouble()

    /**
     * `servings / max(recipeServings, 1)`. A non-finite, zero or negative [servings] gives `1.0`
     * (the recipe as written) instead of hiding every amount.
     */
    fun factor(recipeServings: Int, servings: Double): Double {
        if (!servings.isFinite() || servings <= 0.0) return 1.0
        return servings / baseServings(recipeServings).toDouble()
    }

    /** [factor] for [recipe]. */
    fun factor(recipe: Recipe, servings: Double): Double = factor(recipe.servings, servings)
}
