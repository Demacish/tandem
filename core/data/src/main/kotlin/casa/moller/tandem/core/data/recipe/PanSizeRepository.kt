package casa.moller.tandem.core.data.recipe

import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (kok lane, plan §8.1 "Pannstorlek"): the tin a recipe is baked in this time, when it is not
 * the recipe's own (`Recipe.diameter`). Shared by the recipe view and cook mode, so "20 cm" chosen
 * in the recipe stays in cook mode and back. Per account (and demo), on this device, for as long
 * as the app runs: like Tandoor's web, the next visit starts from the recipe's own tin. Never sent.
 *
 * Main-safe; the flow never throws.
 */
interface PanSizeRepository {
    /** The chosen tin of [recipeId] in the recipe's own unit, or null for the recipe's own. */
    fun observePanSize(recipeId: Long): Flow<Int?>

    /** Chooses [diameter] for [recipeId]; null goes back to the recipe's own tin. */
    suspend fun setPanSize(recipeId: Long, diameter: Int?)
}
