package casa.moller.tandem.core.domain.scaling

/**
 * The part of a Tandoor `Ingredient` that scaling needs. Kept local to core:domain until the
 * recipe model lands in core:model; mapping from the API model is then a one-liner.
 *
 * @property amount Tandoor's `amount` (a double, unit-less here).
 * @property isHeader Tandoor's `is_header`: a section title row, never scaled.
 * @property noAmount Tandoor's `no_amount`: "salt to taste" style rows, never scaled.
 */
data class ScalableIngredient(val id: Long, val amount: Double, val isHeader: Boolean = false, val noAmount: Boolean = false) {
    val isScalable: Boolean get() = !isHeader && !noAmount
}
