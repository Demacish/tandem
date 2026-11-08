package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.domain.shopping.MergedShoppingRow
import casa.moller.tandem.core.domain.shopping.ShoppingMerge
import casa.moller.tandem.core.domain.shopping.UnitConverter
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.model.shopping.UnitConversions
import kotlin.time.Instant

// Phase 2 (inköp-ui lane): how the Handla tab orders the list (plan 6.2 "Handla", feature #10,
// #14). Pure functions, so the ViewModel tests and the store mode share one rule.

/**
 * One aisle on the list: the unchecked entries of one category.
 *
 * @property categoryId the Tandoor category, null = no (or an unknown) category ("Övrigt").
 * @property name the category's name as the user named it in Tandoor; null for "Övrigt", which
 *   the screen names in the app's language.
 * @property rows Fas 5 (plan §8.2 "Sammanslagning i vyn"): what the screen shows, the same food in
 *   convertible units merged into one row ([ShoppingMerge]); one row per entry when nothing merges.
 */
data class ShoppingSection(
    val categoryId: Long?,
    val name: String?,
    val entries: List<ShoppingEntry>,
    val rows: List<MergedShoppingRow> = entries.map(MergedShoppingRow::single),
) {
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
 * @property laterRows Fas 5: [later] as the screen shows it (merged rows, [ShoppingMerge]).
 * @property checkedRows Fas 5: [checked] as the screen shows it.
 */
data class GroupedShopping(
    val sections: List<ShoppingSection> = emptyList(),
    val later: List<ShoppingEntry> = emptyList(),
    val checked: List<ShoppingEntry> = emptyList(),
    val laterRows: List<MergedShoppingRow> = later.map(MergedShoppingRow::single),
    val checkedRows: List<MergedShoppingRow> = checked.map(MergedShoppingRow::single),
) {
    /** Rows still to buy now (the sections), as the screen counts them (a merged row is one). */
    val toBuyCount: Int
        get() = sections.sumOf { it.rows.size }

    /** Every row on the screen: the aisles, "Köp senare", "Avbockat". */
    val allRows: List<MergedShoppingRow>
        get() = sections.flatMap { it.rows } + laterRows + checkedRows

    /** The row [id] is on (a row's id or the id of any of its entries), null when none. */
    fun rowOf(id: Long): MergedShoppingRow? = allRows.firstOrNull { it.id == id } ?: allRows.firstOrNull { id in it }

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
    fun group(
        entries: List<ShoppingEntry>,
        categories: List<SupermarketCategory>,
        store: Supermarket?,
        now: Instant,
        converter: UnitConverter = NO_CONVERSIONS,
    ): GroupedShopping {
        val names = categories.associate { it.id to it.name } + store?.aisles.orEmpty().associate { it.category.id to it.category.name }
        val rank = store?.aisles.orEmpty().sortedBy { it.order }.mapIndexed { index, aisle -> aisle.category.id to index }.toMap()
        val (checked, unchecked) = entries.partition { it.checked }
        val (later, toBuy) = unchecked.partition { it.isDelayed(now) }
        val sections = toBuy
            .groupBy { entry -> entry.food.categoryId?.takeIf { it in names } }
            .map { (categoryId, inCategory) ->
                val sorted = inCategory.sortedWith(byFood)
                ShoppingSection(categoryId, categoryId?.let { names.getValue(it) }, sorted, ShoppingMerge.merge(sorted, converter))
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
        val laterSorted = later.sortedWith(compareBy<ShoppingEntry> { it.delayUntil }.then(byFood))
        val checkedSorted = checked.sortedWith(compareByDescending<ShoppingEntry> { it.completedAt }.then(byFood))
        return GroupedShopping(
            sections = sections,
            later = laterSorted,
            checked = checkedSorted,
            laterRows = ShoppingMerge.merge(laterSorted, converter),
            checkedRows = ShoppingMerge.merge(checkedSorted, converter),
        )
    }

    /** Without the space's conversions: only the same unit (and Tandoor's base table by name) merges. */
    val NO_CONVERSIONS: UnitConverter = UnitConverter(UnitConversions.NONE)

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
