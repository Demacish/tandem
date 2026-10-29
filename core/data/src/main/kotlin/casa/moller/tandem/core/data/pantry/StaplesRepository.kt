package casa.moller.tandem.core.data.pantry

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.pantry.Staple
import kotlinx.coroutines.flow.Flow

/**
 * Staples ("Basvaror", plan §8.8): salt, pepper, oil, water and the like, marked "har hemma" so
 * recipes can "have all". The first time, the app offers a list of common staples (the suggestions
 * are the UI's own translated strings). Implemented by the hemma-data lane; same contract as
 * [PantryRepository]. Many foods at once go through `food/batch_update/` (outbox `BATCH_ON_HAND`).
 */
interface StaplesRepository {
    /** The chosen staples with their on-hand state, by name. */
    fun observeStaples(): Flow<List<Staple>>

    /** Whether the first-time suggestion list was shown (or dismissed) on this device. */
    fun observeSuggestionsShown(): Flow<Boolean>

    suspend fun markSuggestionsShown(): Outcome<Unit>

    /** Adds staples by name and marks them on hand (Tandoor finds or creates the foods). */
    suspend fun addStaples(names: List<String>): Outcome<Unit>

    suspend fun removeStaple(name: String): Outcome<Unit>

    /** Marks the staples on hand or not, in one batch. */
    suspend fun setOnHand(names: List<String>, onHand: Boolean): Outcome<Unit>
}
