package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.domain.format.FractionStyle
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.scaling.WebAmountFormat
import casa.moller.tandem.core.domain.shopping.MergedShoppingRow
import casa.moller.tandem.core.domain.shopping.ShoppingLineFormat
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
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
    private val lines = ShoppingLineFormat(
        locale.toNumberStyle(if (plainFractions) FractionStyle.PLAIN else FractionStyle.TYPOGRAPHIC),
        style.useFractions,
        style.decimals,
    )

    /** "2" / "2,5" / "1 ½", null when there is no amount (0). */
    fun amount(amount: Double): String? = lines.amount(amount)

    /** Amount and unit ("2 dl", "6 st"), null when the entry has neither. */
    fun quantity(entry: ShoppingEntry): String? = lines.quantity(entry)

    /** The food in singular or plural for the amount, Tandoor's rule like the recipe detail ("3 gula lökar", "mjölk"). */
    fun food(entry: ShoppingEntry): String = lines.food(entry)

    /** "2 dl grädde", "mjölk": how the entry reads in one line (share text, TalkBack). */
    fun line(entry: ShoppingEntry): String = lines.line(entry)

    /**
     * Fas 5 (plan §8.2): a merged row's amounts, "5 dl" or "5 dl + 1 st" (what does not convert stays
     * beside it); null when no entry has an amount. Each unit in singular or plural for its amount.
     */
    fun quantity(row: MergedShoppingRow): String? = row.quantities
        .mapNotNull { quantity -> lines.quantity(row.first.copy(amount = quantity.amount, unit = quantity.unit)) }
        .joinToString(QUANTITY_SEPARATOR)
        .ifEmpty { null }

    /** A merged row's food: plural for several amounts or a plural amount, as Tandoor's web does. */
    fun food(row: MergedShoppingRow): String {
        val amount = if (row.quantities.size > 1) PLURAL_AMOUNT else row.quantities.firstOrNull()?.amount ?: 0.0
        return lines.food(row.first.copy(amount = amount))
    }

    /** "5 dl grädde", "5 dl + 1 st grädde", "mjölk": a merged row in one line (share text, TalkBack). */
    fun line(row: MergedShoppingRow): String = listOfNotNull(quantity(row), food(row)).joinToString(" ")

    /** "2 dl grädde (rumsvarm)" for a parsed line (the add preview). */
    fun parsed(item: ParsedIngredient): String {
        val head = listOfNotNull(amount(item.amount), item.unitName?.takeIf { it.isNotBlank() }, item.foodName).joinToString(" ")
        return if (item.note.isBlank()) head else "$head (${item.note})"
    }

    /** "2 l mjölk" for an archived entry ("Köpt tidigare"). */
    fun history(item: ShoppingHistoryItem): String =
        listOfNotNull(amount(item.amount), item.unitName?.takeIf { it.isNotBlank() }, item.foodName).joinToString(" ")

    /**
     * "Dela som text" (#13): the title, then every aisle in walking order with its entries, plain
     * text for any app ("- 2 dl grädde"). Only what is still to buy (not checked, not delayed).
     */
    fun shareText(title: String, sections: List<ShoppingSection>, otherCategory: String): String = buildString {
        append(title)
        sections.forEach { section ->
            append("\n\n")
            append(section.name ?: otherCategory)
            // Fas 5: the rows as the screen shows them (merged).
            section.rows.forEach { row ->
                append("\n- ")
                append(line(row))
            }
        }
    }
}

/** Between the amounts of a merged row that do not convert ("5 dl + 1 st"). */
private const val QUANTITY_SEPARATOR = " + "

/** An amount that takes the plural (Tandoor's rule: anything but 1). */
private const val PLURAL_AMOUNT = 2.0
