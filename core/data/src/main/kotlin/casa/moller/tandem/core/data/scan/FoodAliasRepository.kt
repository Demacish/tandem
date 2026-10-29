package casa.moller.tandem.core.data.scan

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.scan.FoodAlias
import casa.moller.tandem.core.model.scan.FoodAliasKind
import kotlinx.coroutines.flow.Flow

/**
 * Learned barcodes and receipt names (plan §8.8): Tandoor automations of type `FOOD_ALIAS`
 * (`param_1` = the code or receipt text, `param_2` = the food's name, `name` = "Tandem: streckkod" /
 * "Tandem: kvitto"), so every phone of the space learns them (FACTS "Fas S d": space-wide, matched
 * without case, at most 128 characters, a guest gets 403 and the ingredient parser applies them).
 * Implemented by the skanning lane in :core:sync; offline creates are queued (outbox `food_alias`).
 */
interface FoodAliasRepository {
    fun observeAliases(): Flow<List<FoodAlias>>

    /** The learned alias for [text] (a barcode or a receipt line), matched without case; null when none. */
    suspend fun find(text: String): FoodAlias?

    /** Learns (or re-points) [text] -> the food [foodName]. Text longer than [FoodAlias.MAX_LENGTH] is cut. */
    suspend fun learn(text: String, foodName: String, kind: FoodAliasKind, foodId: Long? = null): Outcome<FoodAlias>

    suspend fun forget(id: Long): Outcome<Unit>

    /** Reads `automation?type=FOOD_ALIAS` again. */
    suspend fun refresh(): Outcome<Unit>
}
