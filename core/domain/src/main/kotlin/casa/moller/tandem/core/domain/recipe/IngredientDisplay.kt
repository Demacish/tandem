package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.DisplayAmount
import casa.moller.tandem.core.domain.scaling.PluralRule
import casa.moller.tandem.core.domain.scaling.ScaleIngredients
import casa.moller.tandem.core.domain.scaling.WebAmountFormat
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Ingredient

/**
 * One row of an ingredient list, ready to show (plan 6.2 "Receptdetalj", feature #4). Built by
 * [IngredientDisplay.rows] from the recipe, the scaling factor and the user's Tandoor
 * preferences; every text follows Tandoor's web (FACTS.md "Fas 1 l" 3–4, golden-tested).
 */
sealed interface IngredientRow {
    /** The Tandoor ingredient id (stable key for lists, strike-through and the unit choice). */
    val id: Long

    /**
     * A section title ("Deg", "Fyllning"): Tandoor's `is_header` row, whose text is the `note`.
     * The web shows it in bold; it has no amount.
     */
    data class Header(override val id: Long, val title: String) : IngredientRow

    /**
     * An ingredient: `[amount] [unit] food`, plus a [note].
     *
     * @property units the amount in every unit the row can show, scaled and formatted; index 0
     *   is the unit the recipe uses, then the server's `conversions` in server order (own
     *   conversions first, then base units), without repeats. Empty when the web shows no amount
     *   (`no_amount`, or an amount of 0).
     * @property food the food in singular or plural for the scaled amount ("champinjoner"); for a
     *   row without a food the imported `original_text`, else empty.
     * @property note the note ("riven"); empty when there is none. The web shows it as a tooltip.
     * @property noAmount Tandoor's `no_amount` ("salt till pastavattnet").
     * @property onHand the food is marked "have at home" on the server.
     */
    data class Item(
        override val id: Long,
        val units: List<UnitAmount>,
        val food: String,
        val note: String,
        val noAmount: Boolean = false,
        val onHand: Boolean = false,
    ) : IngredientRow {
        /** The amount in the recipe's own unit, as text; empty when not shown. */
        val amount: String
            get() = units.firstOrNull()?.amount.orEmpty()

        /** The recipe's own unit for the shown amount ("dl", "cups"); empty when not shown or unit-less. */
        val unit: String
            get() = units.firstOrNull()?.unit.orEmpty()

        /** More than one unit to cycle through (tap on the amount, plan 6.2). */
        val canCycleUnits: Boolean
            get() = units.size > 1

        /** The unit picked by [index], wrapping around in both directions; null when no amount is shown. */
        fun unitAt(index: Int): UnitAmount? = if (units.isEmpty()) null else units[((index % units.size) + units.size) % units.size]

        /** `amount unit food` in the unit picked by [unitIndex] ("2,5 dl vetemjöl"), without the note. */
        fun text(unitIndex: Int = 0): String {
            val shown = unitAt(unitIndex)
            return listOfNotNull(shown?.amount, shown?.unit, food).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}

/**
 * The scaled amount of a row in one unit.
 *
 * @property amount the formatted amount ("2,5", "1 ½").
 * @property unit the unit in singular or plural for [value]; empty for a unit-less amount ("3 ägg").
 * @property value the rounding source, kept for accessibility and parity tests.
 */
data class UnitAmount(val amount: String, val unit: String, val value: DisplayAmount)

/**
 * A header with the items below it, for lists that show sections. Items before the first header
 * form a group without [header].
 */
data class IngredientGroup(val header: IngredientRow.Header?, val items: List<IngredientRow.Item>)

/** Builds [IngredientRow]s like the web's ingredient table. */
object IngredientDisplay {
    /**
     * The rows of [ingredients] (in the given order) at [factor] (`ServingsScaling.factor`),
     * formatted with the user's `use_fractions` / `ingredient_decimals` and written in [style].
     */
    fun rows(ingredients: List<Ingredient>, factor: Double, preferences: UserPreferences, style: NumberStyle): List<IngredientRow> =
        ingredients.map { row(it, factor, preferences, style) }

    /** One row; see [rows]. */
    fun row(ingredient: Ingredient, factor: Double, preferences: UserPreferences, style: NumberStyle): IngredientRow {
        if (ingredient.isHeader) return IngredientRow.Header(id = ingredient.id, title = headerTitle(ingredient))
        val scaled = ScaleIngredients.amount(ingredient.amount, factor)
        val shown = !ingredient.noAmount && ingredient.amount != 0.0
        val units = if (shown) unitAmounts(ingredient, factor, preferences, style) else emptyList()
        val food = ingredient.food
        val foodText = if (food != null) {
            PluralRule.name(food.name, food.pluralName, scaled, ingredient.noAmount)
        } else {
            ingredient.originalText?.trim().orEmpty()
        }
        return IngredientRow.Item(
            id = ingredient.id,
            units = units,
            food = foodText,
            note = ingredient.note?.trim().orEmpty(),
            noAmount = ingredient.noAmount,
            onHand = food?.onHand == true,
        )
    }

    /** Groups [rows] under their headers; a header without items stays as an empty group. */
    fun groups(rows: List<IngredientRow>): List<IngredientGroup> {
        val groups = mutableListOf<IngredientGroup>()
        var header: IngredientRow.Header? = null
        var items = mutableListOf<IngredientRow.Item>()
        var started = false
        for (row in rows) {
            when (row) {
                is IngredientRow.Header -> {
                    if (started || items.isNotEmpty()) groups += IngredientGroup(header, items)
                    header = row
                    items = mutableListOf()
                    started = true
                }

                is IngredientRow.Item -> items += row
            }
        }
        if (started || items.isNotEmpty()) groups += IngredientGroup(header, items)
        return groups
    }

    private fun headerTitle(ingredient: Ingredient): String {
        val note = ingredient.note?.trim().orEmpty()
        if (note.isNotEmpty()) return note
        return ingredient.food?.name ?: ingredient.originalText?.trim().orEmpty()
    }

    private fun unitAmounts(ingredient: Ingredient, factor: Double, preferences: UserPreferences, style: NumberStyle): List<UnitAmount> {
        val own = unitAmount(ingredient.amount, ingredient.unit?.name.orEmpty(), ingredient.unit?.pluralName, factor, preferences, style)
        val seen = mutableSetOf(own.unit.lowercase(), ingredient.unit?.name.orEmpty().lowercase())
        val others = ingredient.conversions.mapNotNull { conversion ->
            val name = conversion.unitName.trim()
            if (name.isEmpty() || conversion.amount <= 0.0 || !conversion.amount.isFinite() || !seen.add(name.lowercase())) {
                null
            } else {
                unitAmount(conversion.amount, name, conversion.unitPluralName, factor, preferences, style)
            }
        }
        return listOf(own) + others
    }

    private fun unitAmount(
        amount: Double,
        unitName: String,
        unitPluralName: String?,
        factor: Double,
        preferences: UserPreferences,
        style: NumberStyle,
    ): UnitAmount {
        val scaled = ScaleIngredients.amount(amount, factor)
        val value = WebAmountFormat.format(scaled, preferences.useFractions, preferences.ingredientDecimals)
        val unit = if (unitName.isEmpty()) "" else PluralRule.name(unitName, unitPluralName, scaled)
        return UnitAmount(amount = value.text(style), unit = unit, value = value)
    }
}
