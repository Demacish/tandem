package casa.moller.tandem.core.domain.scaling

import casa.moller.tandem.core.model.recipe.Recipe
import kotlin.math.floor

/**
 * Fas 5 (kok lane, plan §8.1 "Pannstorlek"): a recipe baked in a round tin (`Recipe.diameter`,
 * e.g. 24 with `diameter_text` "cm") scaled to another tin by AREA: `(chosen / original)²`.
 *
 * Tandoor's web does the same in its scaling dialog (vue3 `RecipeScalingDialog.changeDiameter`,
 * 2.6.13 to 2.7.x, see FACTS "Fas 5: pannstorlek"): it rounds the area ratio to two decimals
 * (`Math.round(ratio² × 100) / 100`, half up) and sets the servings to `servings × ratio`, so
 * 24 → 20 cm is 0.69. The app rounds the same way, so an amount matches the web's; unlike the web
 * the tin and the servings are kept apart and multiplied (half the servings in a smaller tin).
 *
 * A diameter of 0 (Tandoor's default) or below means the recipe has no tin.
 */
object PanScaling {
    /** The smallest and largest tin the stepper offers (in the recipe's own unit). */
    const val MIN_DIAMETER: Int = 1
    const val MAX_DIAMETER: Int = 200

    /** The recipe names a tin to scale by. */
    fun hasTin(recipe: Recipe): Boolean = recipe.diameter > 0

    /** The tin in use: [chosen] when it is a valid size, else the recipe's own. */
    fun current(recipe: Recipe, chosen: Int?): Int =
        chosen?.takeIf { hasTin(recipe) && it in MIN_DIAMETER..MAX_DIAMETER } ?: recipe.diameter

    /**
     * `(chosen / original)²` rounded half up to two decimals, like the web; 1.0 when either is not a
     * tin (the recipe as written).
     */
    fun areaFactor(original: Int, chosen: Int): Double {
        if (original <= 0 || chosen <= 0 || original == chosen) return 1.0
        val ratio = chosen.toDouble() / original.toDouble()
        val rounded = floor(ratio * ratio * PERCENT + HALF) / PERCENT
        // 1 → 13 cm would round to 0.01; never scale everything away.
        return rounded.coerceAtLeast(MIN_FACTOR)
    }

    /** [areaFactor] for [recipe] baked in [chosen] (null: the recipe's own tin). */
    fun factor(recipe: Recipe, chosen: Int?): Double = areaFactor(recipe.diameter, current(recipe, chosen))

    /**
     * The servings to send when the recipe goes on the shopping list in another tin: `servings ×
     * factor`, as Tandoor's web does (its scaling dialog sets the servings to `servings × factor`,
     * and "Lägg på inköpslistan" then sends those; FACTS "Fas 5: pannstorlek" d). Rounded half up
     * to four decimals, so 4 × 0.69 is 2.76 and not 2.7600000000000002; Tandoor scales the list
     * recipe's entries by `servings / recipe servings`. A factor that is not positive and finite
     * (or 1.0) leaves [servings] as they are.
     */
    fun listServings(servings: Double, factor: Double): Double {
        if (!factor.isFinite() || factor <= 0.0 || factor == 1.0) return servings
        return floor(servings * factor * SERVINGS_PRECISION + HALF) / SERVINGS_PRECISION
    }

    /** One size up (the stepper's +), at most [MAX_DIAMETER]. */
    fun larger(current: Int): Int = (current + 1).coerceIn(MIN_DIAMETER, MAX_DIAMETER)

    /** One size down (the stepper's −), at least [MIN_DIAMETER]. */
    fun smaller(current: Int): Int = (current - 1).coerceIn(MIN_DIAMETER, MAX_DIAMETER)

    /** The unit to show beside the size: Tandoor's `diameter_text`, or "cm" when the recipe left it empty. */
    fun unit(recipe: Recipe): String = recipe.diameterText.trim().ifEmpty { DEFAULT_UNIT }

    const val DEFAULT_UNIT: String = "cm"

    private const val PERCENT = 100.0
    private const val HALF = 0.5
    private const val SERVINGS_PRECISION = 10_000.0
    private const val MIN_FACTOR = 0.01
}
