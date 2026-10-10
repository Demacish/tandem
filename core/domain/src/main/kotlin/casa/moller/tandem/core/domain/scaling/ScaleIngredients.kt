package casa.moller.tandem.core.domain.scaling

/**
 * Scales ingredient amounts by a servings change or a plain factor, like Tandoor's web UI
 * (`amount * servings / recipe.servings`).
 *
 * Guarantees: results are always finite and `>= 0`. Invalid input never throws:
 * - a non-finite or negative amount scales to `0.0`;
 * - a non-finite or negative factor is ignored (factor `1.0`);
 * - an overflowing product is clamped to [Double.MAX_VALUE].
 * Rows with `is_header` or `no_amount` are returned unchanged.
 */
object ScaleIngredients {

    /** Factor for going from [originalServings] to [targetServings]; `1.0` if either is not a positive number. */
    fun factor(originalServings: Double, targetServings: Double): Double {
        if (!originalServings.isPositiveFinite() || !targetServings.isPositiveFinite()) return 1.0
        return targetServings / originalServings
    }

    /** Scales a single amount. See the class documentation for how invalid input is handled. */
    fun amount(amount: Double, factor: Double): Double {
        if (!amount.isFinite() || amount <= 0.0) return 0.0
        val safeFactor = if (factor.isFinite() && factor >= 0.0) factor else 1.0
        val scaled = amount * safeFactor
        return when {
            scaled.isNaN() -> 0.0
            scaled.isInfinite() -> Double.MAX_VALUE
            else -> scaled
        }
    }

    operator fun invoke(ingredients: List<ScalableIngredient>, factor: Double): List<ScalableIngredient> = ingredients.map { ingredient ->
        if (ingredient.isScalable) ingredient.copy(amount = amount(ingredient.amount, factor)) else ingredient
    }

    operator fun invoke(
        ingredients: List<ScalableIngredient>,
        originalServings: Double,
        targetServings: Double,
    ): List<ScalableIngredient> = invoke(ingredients, factor(originalServings, targetServings))

    private fun Double.isPositiveFinite(): Boolean = isFinite() && this > 0.0
}
