package casa.moller.tandem.core.domain.shopping

import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import kotlin.math.roundToLong

/**
 * An amount in one unit ("5 dl"); [unit] null for a count without a unit ("6" ägg).
 */
data class ShoppingQuantity(val amount: Double, val unit: ShoppingUnit?)

/**
 * One row of the shopping list as the screen shows it (plan §8.2 "Sammanslagning i vyn"): one or
 * more entries of the SAME food, in the same state (checked or not). The entries stay separate on
 * the server; checking the row checks all of them.
 *
 * @property entries the rows on the server, at least one, in list order.
 * @property quantities what the row says: the amounts converted to the most used unit first, then
 *   (when something cannot be converted) the rest by unit ("5 dl + 1 st"); empty when no entry has
 *   an amount ("mjölk").
 */
data class MergedShoppingRow(val entries: List<ShoppingEntry>, val quantities: List<ShoppingQuantity>) {
    init {
        require(entries.isNotEmpty()) { "a row needs an entry" }
    }

    /** The first entry: its food, aisle and id stand for the row. */
    val first: ShoppingEntry
        get() = entries.first()

    /** The row's id: its first entry's, stable while that entry is on the list. */
    val id: Long
        get() = entries.first().id

    /** Every entry's id: what checking, "köp senare" and undo act on. */
    val ids: List<Long>
        get() = entries.map { it.id }

    /** More than one entry behind the row. */
    val isMerged: Boolean
        get() = entries.size > 1

    /** Checked: every entry is (the merge never mixes states). */
    val checked: Boolean
        get() = entries.all { it.checked }

    /** A change of one of the entries waits in the outbox. */
    val pending: Boolean
        get() = entries.any { it.pending }

    /** The recipes and meals the entries came from, each name once, in list order. */
    val recipeNames: List<String>
        get() = entries.mapNotNull { it.recipe?.recipeName?.takeIf(String::isNotBlank) }.distinct()

    /** True when this row is for [id] (one of its entries). */
    operator fun contains(id: Long): Boolean = entries.any { it.id == id }

    companion object {
        /** [entry] on a row of its own (nothing merged). */
        fun single(entry: ShoppingEntry): MergedShoppingRow =
            MergedShoppingRow(listOf(entry), if (entry.amount > 0.0) listOf(ShoppingQuantity(entry.amount, entry.unit)) else emptyList())
    }
}

/**
 * Merges list entries into rows for the view (plan §8.2; docs/BYGGE.md "Sammanslagning och
 * rutnät"). Pure, so the list, store mode and the share text agree:
 *
 * - **One row per food and state:** entries of the same food (by name, which Tandoor keeps unique
 *   per space; an entry made offline has no food id yet) and the same checked state. The caller
 *   passes one section at a time ("Köp senare" apart from the aisles).
 * - **Amounts:** the unit used by most entries first (a tie: the first entry's); every amount that
 *   converts to it ([UnitConverter], food-specific conversions for this food) is added. What does
 *   not convert stays beside it on the same row, by the next most used unit, and so on ("5 dl + 1
 *   st"), like Tandoor's web, which shows one line per food with one amount per unit and never
 *   converts (FACTS "Fas 5"). Entries without an amount add nothing.
 * - **Rounding:** sums are kept to 6 decimals (no `0.30000000000000004`); the screen rounds as
 *   Tandoor's web does (`ShoppingLineFormat`).
 */
object ShoppingMerge {

    /** [entries] (in list order) as rows, in the order of each row's first entry. */
    fun merge(entries: List<ShoppingEntry>, converter: UnitConverter): List<MergedShoppingRow> = entries
        .groupBy { RowKey(foodKey(it), it.checked) }
        .values
        .map { group -> MergedShoppingRow(group, quantities(group, converter)) }

    /** The amounts of one food's [entries]: the most used unit first, then what does not convert to it. */
    fun quantities(entries: List<ShoppingEntry>, converter: UnitConverter): List<ShoppingQuantity> {
        val foodId = entries.firstNotNullOfOrNull { it.food.id }
        var left = entries.filter { it.amount > 0.0 }
        val result = mutableListOf<ShoppingQuantity>()
        while (left.isNotEmpty()) {
            val target = mostUsedUnit(left, converter)
            val converted = left.map { entry -> entry to converter.factor(entry.unit, target, foodId) }
            val total = converted.sumOf { (entry, factor) -> if (factor != null) entry.amount * factor else 0.0 }
            result += ShoppingQuantity(round(total), target)
            left = converted.filter { (_, factor) -> factor == null }.map { (entry, _) -> entry }
        }
        return result
    }

    /**
     * The unit most of [entries] are in; a tie goes to the unit of the first of them. Spelled as the
     * server has it (a unit with an id) when one of them has it, not as typed offline ("DL").
     */
    private fun mostUsedUnit(entries: List<ShoppingEntry>, converter: UnitConverter): ShoppingUnit? {
        val counts = mutableListOf<Pair<ShoppingUnit?, Int>>()
        for (entry in entries) {
            val index = counts.indexOfFirst { (unit, _) -> converter.sameUnit(unit, entry.unit) }
            if (index < 0) {
                counts += entry.unit to 1
            } else {
                val (unit, count) = counts[index]
                counts[index] = (if (unit?.id == null && entry.unit?.id != null) entry.unit else unit) to count + 1
            }
        }
        return counts.maxBy { it.second }.first
    }

    /** The food a row is for: its name, as Tandoor matches foods (case-insensitive, trimmed). */
    fun foodKey(entry: ShoppingEntry): String = entry.food.name.trim().lowercase()

    private fun round(value: Double): Double = (value * PRECISION).roundToLong() / PRECISION

    private data class RowKey(val food: String, val checked: Boolean)

    private const val PRECISION = 1_000_000.0
}
