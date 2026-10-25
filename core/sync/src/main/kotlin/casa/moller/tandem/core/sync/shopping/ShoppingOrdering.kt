package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import java.text.Collator
import java.text.NumberFormat
import java.util.Locale
import kotlin.time.Instant

/**
 * How the list is ordered and shared (plan 6.2 "Handla", #10, #13). Pure: the repository feeds it
 * the stored rows.
 *
 * - With a store ([ShoppingFilter.supermarketId]): its aisles in walking order (`relation_order`),
 *   then entries whose aisle the store does not have, then those without an aisle ("Övrigt").
 * - Without one: by aisle name, entries without an aisle last.
 * - Unchecked entries first; inside an aisle by the food's name in the language's alphabet
 *   (Swedish: å, ä, ö after z), then the server's order.
 */
internal class ShoppingOrdering(
    /** Category id → position in the chosen store's walking order. */
    private val aislePositions: Map<Long, Int>,
    /** Category id → its name, for sorting without a store and for the shared text. */
    private val categoryNames: Map<Long, String>,
    locale: Locale = Locale.getDefault(),
) {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    private val storeChosen = aislePositions.isNotEmpty()

    fun sort(entries: List<ShoppingEntry>): List<ShoppingEntry> = entries.sortedWith(
        compareBy<ShoppingEntry> { it.checked }
            .thenComparator { a, b -> compareAisles(a.food.categoryId, b.food.categoryId) }
            .thenComparator { a, b -> collator.compare(a.food.name, b.food.name) }
            .thenBy { it.order }
            .thenBy { it.id },
    )

    private fun compareAisles(a: Long?, b: Long?): Int {
        if (storeChosen) {
            val first = a?.let { aislePositions[it] } ?: if (a == null) NO_AISLE else NOT_IN_STORE
            val second = b?.let { aislePositions[it] } ?: if (b == null) NO_AISLE else NOT_IN_STORE
            if (first != second) return first.compareTo(second)
        }
        val nameA = a?.let { categoryNames[it] }
        val nameB = b?.let { categoryNames[it] }
        return when {
            nameA == nameB -> 0
            nameA == null -> 1
            nameB == null -> -1
            else -> collator.compare(nameA, nameB)
        }
    }

    /**
     * "Dela som text" (#13): what is still to buy (unchecked, not delayed), one line per entry
     * ("2 l mjölk", "tandkräm"), a heading line per aisle that has a name; entries without an aisle
     * come last without a heading.
     */
    fun shareText(entries: List<ShoppingEntry>, now: Instant, locale: Locale = Locale.getDefault()): String {
        val toBuy = sort(entries.filter { !it.checked && !it.isDelayed(now) })
        val numbers = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
        val lines = mutableListOf<String>()
        var heading: String? = null
        for (entry in toBuy) {
            val aisle = entry.food.categoryId?.let { categoryNames[it] }
            if (aisle != null && aisle != heading) {
                if (lines.isNotEmpty()) lines += ""
                lines += aisle
            } else if (aisle == null && heading != null) {
                lines += ""
            }
            heading = aisle
            lines += lineOf(entry, numbers)
        }
        return lines.joinToString("\n")
    }

    private fun lineOf(entry: ShoppingEntry, numbers: NumberFormat): String {
        val parts = mutableListOf<String>()
        if (entry.amount > 0) parts += numbers.format(entry.amount)
        val plural = entry.amount > 1
        entry.unit?.let { unit -> parts += unit.pluralName?.takeIf { plural && it.isNotBlank() } ?: unit.name }
        parts += entry.food.pluralName?.takeIf { plural && it.isNotBlank() } ?: entry.food.name
        return parts.joinToString(" ")
    }

    companion object {
        private const val NOT_IN_STORE = Int.MAX_VALUE - 1
        private const val NO_AISLE = Int.MAX_VALUE

        /** Only the entries [filter] asks for (a named list); the store only orders them. */
        fun filter(entries: List<ShoppingEntry>, filter: ShoppingFilter): List<ShoppingEntry> =
            filter.listId?.let { listId -> entries.filter { listId in it.listIds } } ?: entries
    }
}
