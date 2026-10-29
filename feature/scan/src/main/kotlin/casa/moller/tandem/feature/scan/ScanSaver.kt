package casa.moller.tandem.feature.scan

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.scan.FoodAliasRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.InventoryEntryDraft
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import javax.inject.Inject

/** What saving the review did: the rows now in the pantry, and the error that stopped the rest. */
data class ScanSaveResult(val savedKeys: Set<String>, val error: TandemError? = null)

/**
 * Saves confirmed review rows (only after the user confirmed, plan §8.8): each ready row becomes a
 * pantry entry, and a row the user matched (not already learned) teaches its barcode or printed
 * receipt line to the household as a `FOOD_ALIAS`, so the other phone recognises it next time. The
 * repositories store at once and queue the server calls, so this works offline. A guest gets
 * `DataProblem.READ_ONLY` before anything is stored.
 */
class ScanSaver @Inject constructor(
    private val pantry: PantryRepository,
    private val aliases: FoodAliasRepository,
    private val shopping: ShoppingRepository,
) {
    /** Puts the ready rows of [items] in the pantry; a row without a place goes to [defaultLocationId]. */
    suspend fun toPantry(items: List<ScanReviewItem>, defaultLocationId: Long?): ScanSaveResult {
        val saved = mutableSetOf<String>()
        for (item in items.filter { it.ready }) {
            val food = item.food ?: continue
            val locationId = item.locationId ?: defaultLocationId ?: return ScanSaveResult(saved, TandemError.Unknown(NO_LOCATION))
            learn(item)?.let { return ScanSaveResult(saved, it) }
            val draft = InventoryEntryDraft(
                locationId = locationId,
                foodName = food.name,
                foodId = food.id,
                amount = item.amount?.takeIf { it > 0.0 } ?: 1.0,
                unitName = item.unit?.takeIf { it.isNotBlank() },
                expires = item.expires,
            )
            when (val outcome = pantry.addEntry(draft)) {
                is Outcome.Success -> saved += item.key
                is Outcome.Failure -> return ScanSaveResult(saved, outcome.error)
            }
        }
        return ScanSaveResult(saved)
    }

    /** Puts the ready rows of [items] on the shopping list (a scanned package that is running out). */
    suspend fun toShoppingList(items: List<ScanReviewItem>): Outcome<Int> {
        val ready = items.filter { it.ready }
        for (item in ready) learn(item)?.let { return Outcome.Failure(it) }
        val drafts = ready.mapNotNull { item -> item.food?.let { ShoppingEntryDraft(foodName = it.name, foodId = it.id) } }
        return when (val outcome = shopping.addAll(drafts)) {
            is Outcome.Success -> Outcome.Success(drafts.size)
            is Outcome.Failure -> outcome
        }
    }

    /**
     * Learns the row's barcode or receipt line when the user asked for it; null = fine. Only a
     * guest's refusal stops the save: anything else (the alias is queued anyway) must not cost the
     * user the entry.
     */
    private suspend fun learn(item: ScanReviewItem): TandemError? {
        val food = item.food ?: return null
        if (!item.learn || food.via == FoodMatchVia.ALIAS || item.source == ScanSource.FRIDGE) return null
        val text = item.barcode ?: item.text.takeIf { it.isNotBlank() } ?: return null
        val kind = if (item.barcode != null) FoodAliasKind.BARCODE else FoodAliasKind.RECEIPT
        val outcome = aliases.learn(text, food.name, kind, food.id)
        val error = (outcome as? Outcome.Failure)?.error
        return error?.takeIf { it.dataProblem() == DataProblem.READ_ONLY }
    }

    companion object {
        /** The household has no pantry location yet (Hemma creates Kyl, Frys and Skafferi when it is first opened online). */
        const val NO_LOCATION: String = "tandem.scan.no-location"
    }
}
