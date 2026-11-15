package casa.moller.tandem.core.data.shopping

import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft

/**
 * Free text typed into Handla's "Lägg till" (#11, plan 6.2 "Lägg till manuellt") → entries. Moved
 * out of feature:shopping in Fas 5 (funktioner lane) so that the App Function "lägg till mjölk på
 * listan" (plan §8.7) parses EXACTLY like the add field: the same split into items, Tandoor's
 * parser online and the local parser per item when it fails or the device is offline.
 */
object ShoppingInput {
    /**
     * Splits free text into items (#11, plan 6.2 "Flera varor på en gång"): one per line, and
     * commas or semicolons between items ("mjölk, bröd, 6 ägg"); a decimal comma ("1,5 kg") stays.
     */
    fun splitItems(text: String): List<String> = text.split(ITEM_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Parses [items] (from [splitItems]) in order: Tandoor's parser when it answers for every item,
     * otherwise [parseLocally] per item. Never fails; an item the parsers cannot read becomes a food
     * named like the item.
     */
    suspend fun parse(parser: IngredientParser, items: List<String>): List<ParsedIngredient> =
        parser.parse(items).getOrNull()?.takeIf { it.size == items.size } ?: items.map { parseLocally(parser, it) }

    /** The local parser, or the whole text as the food when it finds nothing. */
    fun parseLocally(parser: IngredientParser, item: String): ParsedIngredient =
        parser.parseLocally(item)?.takeIf { it.foodName.isNotBlank() } ?: ParsedIngredient(item, 0.0, null, item)

    /** The entry to add for a parsed item, on [listIds]. */
    fun draft(parsed: ParsedIngredient, listIds: Set<Long>): ShoppingEntryDraft = ShoppingEntryDraft(
        foodName = parsed.foodName.ifBlank { parsed.originalText },
        amount = parsed.amount,
        unitName = parsed.unitName,
        unitId = parsed.unitId,
        foodId = parsed.foodId,
        note = parsed.note,
        listIds = listIds,
    )

    private val ITEM_SEPARATOR = Regex("\\r?\\n|;|,(?!\\d)")
}
