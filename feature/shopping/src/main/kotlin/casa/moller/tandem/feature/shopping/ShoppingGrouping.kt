package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import kotlin.time.Instant

// Phase 2 (inköp-ui lane): how the Handla tab orders the list (plan 6.2 "Handla", feature #10,
// #14). Pure functions, so the ViewModel tests and the store mode share one rule.

/**
 * One aisle on the list: the unchecked entries of one category.
 *
 * @property categoryId the Tandoor category, null = no (or an unknown) category ("Övrigt").
 * @property name the category's name as the user named it in Tandoor; null for "Övrigt", which
 *   the screen names in the app's language.
 */
data class ShoppingSection(val categoryId: Long?, val name: String?, val entries: List<ShoppingEntry>) {
    /** Stable key for lazy lists and the "check all in this category" action. */
    val key: String
        get() = categoryId?.let { "category-$it" } ?: "category-none"
}

/**
 * The list as the screen shows it.
 *
 * @property sections unchecked entries that are not delayed, by aisle.
 * @property later "Köp senare" (#14): unchecked entries delayed past now, soonest first.
 * @property checked checked entries, most recently checked first ("Avbockat").
 */
data class GroupedShopping(
    val sections: List<ShoppingSection> = emptyList(),
    val later: List<ShoppingEntry> = emptyList(),
    val checked: List<ShoppingEntry> = emptyList(),
) {
    /** Entries still to buy now (the sections). */
    val toBuyCount: Int
        get() = sections.sumOf { it.entries.size }

    /** Nothing on the list at all (also nothing checked or delayed). */
    val isEmpty: Boolean
        get() = sections.isEmpty() && later.isEmpty() && checked.isEmpty()

    val all: List<ShoppingEntry>
        get() = sections.flatMap { it.entries } + later + checked
}

internal object ShoppingGrouping {

    /**
     * Groups [entries] for the screen (plan 6.2): unchecked entries by category in [store]'s
     * walking order (its aisles by `order`); categories the store does not have follow by name,
     * and entries without a known category come last ("Övrigt"). Without a store every category
     * is sorted by name. Inside an aisle the entries are sorted by food name. Delayed entries
     * ([ShoppingEntry.isDelayed] at [now]) go to "Köp senare", checked ones to "Avbockat".
     */
    fun group(entries: List<ShoppingEntry>, categories: List<SupermarketCategory>, store: Supermarket?, now: Instant): GroupedShopping {
        val names = categories.associate { it.id to it.name } + store?.aisles.orEmpty().associate { it.category.id to it.category.name }
        val rank = store?.aisles.orEmpty().sortedBy { it.order }.mapIndexed { index, aisle -> aisle.category.id to index }.toMap()
        val (checked, unchecked) = entries.partition { it.checked }
        val (later, toBuy) = unchecked.partition { it.isDelayed(now) }
        val sections = toBuy
            .groupBy { entry -> entry.food.categoryId?.takeIf { it in names } }
            .map { (categoryId, inCategory) ->
                ShoppingSection(categoryId, categoryId?.let { names.getValue(it) }, inCategory.sortedWith(byFood))
            }
            .sortedWith(
                compareBy<ShoppingSection> { section ->
                    when {
                        section.categoryId == null -> 2
                        section.categoryId in rank -> 0
                        else -> 1
                    }
                }
                    .thenBy { section -> section.categoryId?.let { rank[it] } ?: 0 }
                    .thenBy { section -> section.name.orEmpty().lowercase() },
            )
        return GroupedShopping(
            sections = sections,
            later = later.sortedWith(compareBy<ShoppingEntry> { it.delayUntil }.then(byFood)),
            checked = checked.sortedWith(compareByDescending<ShoppingEntry> { it.completedAt }.then(byFood)),
        )
    }

    private val byFood: Comparator<ShoppingEntry> = compareBy<ShoppingEntry> { it.food.name.lowercase() }.thenBy { it.id }

    /**
     * Splits free text into items (#11, plan 6.2 "Flera varor på en gång"): one per line, and
     * commas or semicolons between items ("mjölk, bröd, 6 ägg"); a decimal comma ("1,5 kg") stays.
     */
    fun splitItems(text: String): List<String> = text.split(ITEM_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * An amount typed in the edit sheet: a decimal comma or point ("1,5", "0.25"), empty = no
     * amount (0). Null when it is not a number or negative.
     */
    fun parseAmount(text: String): Double? {
        val clean = text.trim().replace(',', '.')
        if (clean.isEmpty()) return 0.0
        return clean.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private val ITEM_SEPARATOR = Regex("\\r?\\n|;|,(?!\\d)")
}
