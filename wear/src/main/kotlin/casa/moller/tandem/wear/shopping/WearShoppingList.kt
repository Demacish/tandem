package casa.moller.tandem.wear.shopping

import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.shopping.MergedShoppingRow
import casa.moller.tandem.core.domain.shopping.ShoppingLineFormat
import casa.moller.tandem.core.domain.shopping.ShoppingMerge
import casa.moller.tandem.core.domain.shopping.UnitConverter
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingRecipeLabel
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import casa.moller.tandem.core.model.shopping.UnitConversions
import casa.moller.tandem.wear.data.WearEntry
import casa.moller.tandem.wear.data.WearState
import java.util.Locale

/** One row on the watch: one food, maybe several entries on the server (plan §8.2). */
data class WearRow(
    val ids: List<Long>,
    val food: String,
    val quantity: String?,
    val entryCount: Int,
    val recipe: String?,
    val checked: Boolean,
    val pending: Boolean,
) {
    val key: Long
        get() = ids.first()
}

/** An aisle and its rows; [name] null = no aisle ("Övrigt"). */
data class WearAisle(val categoryId: Long?, val name: String?, val rows: List<WearRow>)

/** The list as the watch shows it: what to buy by aisle, then what was checked off. */
data class WearList(val aisles: List<WearAisle>, val checked: List<WearRow>) {
    /** Rows left to buy. */
    val toBuy: Int
        get() = aisles.sumOf { it.rows.size }

    /** The first rows to buy, in walking order (the tile). */
    fun firstRows(count: Int): List<WearRow> = aisles.asSequence().flatMap { it.rows }.take(count).toList()

    companion object {
        val EMPTY: WearList = WearList(emptyList(), emptyList())
    }
}

/**
 * Turns the stored list into what the watch shows (plan §7 #33, §8.2), pure and tested:
 *
 * - The same food is **one row** (core:domain [ShoppingMerge]) with Tandoor's own spelling and the
 *   web's rounding ([ShoppingLineFormat]); the watch has no unit conversions cached, so only the
 *   same unit adds up ("2 l + 2 dl mjölk" stays two amounts on one row). Checking the row checks
 *   all its entries.
 * - Rows to buy follow the chosen store's aisle order ([WearState.supermarket]); aisles the store
 *   does not list come after it by name, the rows without an aisle last. Without a store: aisles
 *   by name.
 * - "Köp senare" entries are hidden until their time; checked rows come last, so a mistake can be
 *   undone on the watch.
 */
object WearShoppingList {
    private const val PLURAL_AMOUNT = 2.0
    private const val QUANTITY_SEPARATOR = " + "
    private const val IN_STORE = 0
    private const val OTHER_AISLE = 1
    private const val NO_AISLE = 2

    fun of(state: WearState, nowMillis: Long, locale: Locale): WearList {
        val lines = ShoppingLineFormat(locale.toNumberStyle())
        val converter = UnitConverter(UnitConversions.NONE)
        val visible = state.visibleEntries
            .filter { it.checked || (it.delayedUntilMillis ?: 0L) <= nowMillis }
            .sortedWith(compareBy<WearEntry> { it.order }.thenBy { it.id })
        val byAisle = visible.filterNot { it.checked }.groupBy { it.categoryId }
        val walkingOrder = state.supermarket?.categoryOrder.orEmpty()
        val aisles = byAisle.entries
            .sortedWith(
                compareBy<Map.Entry<Long?, List<WearEntry>>> { (id, _) -> aisleRank(id, walkingOrder) }
                    .thenBy { (id, _) -> walkingOrder.indexOf(id ?: -1L) }
                    .thenBy { (_, entries) -> entries.first().categoryName?.lowercase(locale).orEmpty() },
            )
            .map { (id, entries) ->
                WearAisle(id, entries.first().categoryName, rows(entries, state, converter, lines))
            }
        val checked = rows(visible.filter { it.checked }, state, converter, lines)
        return WearList(aisles, checked)
    }

    /** The store's aisles first, then the others, the rows without an aisle last. */
    private fun aisleRank(id: Long?, walkingOrder: List<Long>): Int = when {
        id == null -> NO_AISLE
        id in walkingOrder -> IN_STORE
        else -> OTHER_AISLE
    }

    private fun rows(entries: List<WearEntry>, state: WearState, converter: UnitConverter, lines: ShoppingLineFormat): List<WearRow> =
        ShoppingMerge.merge(entries.map { it.toModel(it.id in state.pending) }, converter).map { row -> row.toWear(lines) }

    private fun MergedShoppingRow.toWear(lines: ShoppingLineFormat): WearRow {
        val quantity = quantities
            .mapNotNull { quantity -> lines.quantity(first.copy(amount = quantity.amount, unit = quantity.unit)) }
            .joinToString(QUANTITY_SEPARATOR)
            .ifEmpty { null }
        val amount = if (quantities.size > 1) PLURAL_AMOUNT else quantities.firstOrNull()?.amount ?: 0.0
        return WearRow(
            ids = ids,
            food = lines.food(first.copy(amount = amount)),
            quantity = quantity,
            entryCount = entries.size,
            recipe = recipeNames.firstOrNull(),
            checked = checked,
            pending = pending,
        )
    }

    private fun WearEntry.toModel(pending: Boolean): ShoppingEntry = ShoppingEntry(
        id = id,
        food = ShoppingFood(id = foodId, name = food, pluralName = foodPlural, categoryId = categoryId),
        unit = unit?.let { ShoppingUnit(id = unitId, name = it, pluralName = unitPlural) },
        amount = amount,
        order = order,
        checked = checked,
        recipe = recipeName?.let { ShoppingRecipeLabel(listRecipeId = 0L, recipeName = it) },
        pending = pending,
    )
}
