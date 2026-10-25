package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.domain.format.FractionStyle
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.scaling.PluralRule
import casa.moller.tandem.core.domain.scaling.WebAmountFormat
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import java.util.Locale

/**
 * How amounts on the list are written (plan 6.7 "Format per språk"): the user's Tandoor
 * preferences `use_fractions` / `ingredient_decimals` (the same as the recipe detail, core:domain
 * [WebAmountFormat]) and the app language's decimal separator ("2,5 dl").
 */
data class AmountStyle(val useFractions: Boolean = false, val decimals: Int = WebAmountFormat.DEFAULT_DECIMALS) {
    companion object {
        fun of(preferences: UserPreferences): AmountStyle = AmountStyle(preferences.useFractions, preferences.ingredientDecimals)
    }
}

/** Texts of list entries for one language; pure, so the share text and TalkBack labels are tested. */
class ShoppingFormat(private val locale: Locale, private val style: AmountStyle = AmountStyle(), plainFractions: Boolean = false) {
    private val numberStyle = locale.toNumberStyle(if (plainFractions) FractionStyle.PLAIN else FractionStyle.TYPOGRAPHIC)

    /** "2" / "2,5" / "1 ½", null when there is no amount (0). */
    fun amount(amount: Double): String? =
        if (amount > 0.0) WebAmountFormat.format(amount, style.useFractions, style.decimals).text(numberStyle) else null

    /** Amount and unit ("2 dl", "6 st"), null when the entry has neither. */
    fun quantity(entry: ShoppingEntry): String? {
        val unit = entry.unit?.let { PluralRule.name(it.name, it.pluralName, entry.amount, noAmount = entry.amount <= 0.0) }
        return listOfNotNull(amount(entry.amount), unit?.takeIf { it.isNotBlank() }).joinToString(" ").ifEmpty { null }
    }

    /** The food in singular or plural for the amount, Tandoor's rule like the recipe detail ("3 gula lökar", "mjölk"). */
    fun food(entry: ShoppingEntry): String =
        PluralRule.name(entry.food.name, entry.food.pluralName, entry.amount, noAmount = entry.amount <= 0.0)

    /** "2 dl grädde", "mjölk": how the entry reads in one line (share text, TalkBack). */
    fun line(entry: ShoppingEntry): String = listOfNotNull(quantity(entry), food(entry)).joinToString(" ")

    /** "2 dl grädde (rumsvarm)" for a parsed line (the add preview). */
    fun parsed(item: ParsedIngredient): String {
        val head = listOfNotNull(amount(item.amount), item.unitName?.takeIf { it.isNotBlank() }, item.foodName).joinToString(" ")
        return if (item.note.isBlank()) head else "$head (${item.note})"
    }

    /**
     * "Dela som text" (#13): the title, then every aisle in walking order with its entries, plain
     * text for any app ("- 2 dl grädde"). Only what is still to buy (not checked, not delayed).
     */
    fun shareText(title: String, sections: List<ShoppingSection>, otherCategory: String): String = buildString {
        append(title)
        sections.forEach { section ->
            append("\n\n")
            append(section.name ?: otherCategory)
            section.entries.forEach { entry ->
                append("\n- ")
                append(line(entry))
            }
        }
    }
}
