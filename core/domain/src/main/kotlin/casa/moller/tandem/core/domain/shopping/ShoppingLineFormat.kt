package casa.moller.tandem.core.domain.shopping

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.PluralRule
import casa.moller.tandem.core.domain.scaling.WebAmountFormat
import casa.moller.tandem.core.model.shopping.ShoppingEntry

/**
 * How one shopping entry reads, the same on Handla and in the widget: the amount rounded as
 * Tandoor's web does ([WebAmountFormat], the user's `use_fractions`/`ingredient_decimals`) in
 * [numberStyle] (plan 6.7), and Tandoor's plural rule for the unit and the food.
 */
class ShoppingLineFormat(
    private val numberStyle: NumberStyle,
    private val useFractions: Boolean = false,
    private val decimals: Int = WebAmountFormat.DEFAULT_DECIMALS,
) {
    /** "2" / "2,5" / "1 ½", null when there is no amount (0). */
    fun amount(amount: Double): String? =
        if (amount > 0.0) WebAmountFormat.format(amount, useFractions, decimals).text(numberStyle) else null

    /** Amount and unit ("2 dl", "6 st"), null when the entry has neither. */
    fun quantity(entry: ShoppingEntry): String? {
        val unit = entry.unit?.let { PluralRule.name(it.name, it.pluralName, entry.amount, noAmount = entry.amount <= 0.0) }
        return listOfNotNull(amount(entry.amount), unit?.takeIf { it.isNotBlank() }).joinToString(" ").ifEmpty { null }
    }

    /** The food in singular or plural for the amount ("3 gula lökar", "mjölk"). */
    fun food(entry: ShoppingEntry): String =
        PluralRule.name(entry.food.name, entry.food.pluralName, entry.amount, noAmount = entry.amount <= 0.0)

    /** "2 dl grädde", "mjölk". */
    fun line(entry: ShoppingEntry): String = listOfNotNull(quantity(entry), food(entry)).joinToString(" ")
}
