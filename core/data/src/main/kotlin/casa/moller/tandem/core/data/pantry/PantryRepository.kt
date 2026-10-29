package casa.moller.tandem.core.data.pantry

import androidx.compose.runtime.getValue
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryEntryDraft
import casa.moller.tandem.core.model.pantry.InventoryEntryUpdate
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryGroup
import casa.moller.tandem.core.model.pantry.UnpackChoice
import casa.moller.tandem.core.model.pantry.UnpackItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate

// Fas S (grund lane): the contracts of the pantry ("Hemma", plan §8.8, #26, #28, #46). Implemented by
// the hemma-data lane in :core:sync (docs/BYGGE.md "Fas S – kontrakt"). Same rules as Phase 2: main-
// safe, follow the active account, work offline (writes are stored at once and queued), a demo path
// without network, and guests (`SpacePermissions.readOnly`) get `DataProblem.READ_ONLY` before
// anything is stored. Tandoor lists locations and entries of the WHOLE space (FACTS "Fas S b"): the
// repository shows only the account's household.

/** Locations and stock entries ("Hemma"). */
interface PantryRepository {
    /** The household's locations, in the device's order (then by name). */
    fun observeLocations(): Flow<List<InventoryLocation>>

    /** The pantry by location: entries with amount > 0, soonest expiry first, no date last. */
    fun observePantry(): Flow<List<PantryGroup>>

    /** One entry (also with amount 0), null when it is gone. A temporary id resolves to the server id. */
    fun observeEntry(id: Long): Flow<InventoryEntry?>

    /**
     * Entries with amount > 0 that expire within [days] days of [today] (also expired ones),
     * soonest first: the input of "Använd upp" (core:domain `UseUpRanking`) and Idag's "Går ut snart".
     */
    fun observeExpiring(today: LocalDate, days: Int = InventoryEntry.EXPIRING_SOON_DAYS): Flow<List<InventoryEntry>>

    /**
     * Ids of the foods the household has (amount > 0 in any location, or `food_onhand`), for
     * core:domain `MissingIngredients`.
     */
    fun observeAvailableFoodIds(): Flow<Set<Long>>

    /**
     * hemma-ui lane: the foods marked "har hemma" (`food_onhand`, per household) that have NO
     * entry with amount > 0, by name: Hemma's "Har hemma" list (salt, a jar of honey ...). The
     * default is empty, so an implementation that does not read on-hand foods yet shows only the
     * staples there (StaplesRepository); hemma-data's repository overrides it.
     */
    fun observeOnHandFoods(): Flow<List<PantryFood>> = flowOf(emptyList())

    suspend fun createLocation(name: String, isFreezer: Boolean): Outcome<InventoryLocation>

    suspend fun renameLocation(id: Long, name: String, isFreezer: Boolean): Outcome<Unit>

    /** Removes a location AND its entries (the server cascades, FACTS "Fas S b"). */
    suspend fun deleteLocation(id: Long): Outcome<Unit>

    /** The device's order of the locations. */
    suspend fun reorderLocations(idsInOrder: List<Long>): Outcome<Unit>

    suspend fun addEntry(draft: InventoryEntryDraft): Outcome<InventoryEntry>

    suspend fun updateEntry(id: Long, update: InventoryEntryUpdate): Outcome<Unit>

    /** "Slut": amount 0 (the row stays on the server and leaves the pantry). */
    suspend fun markEmpty(id: Long): Outcome<Unit>

    /** "−1": one less; at 0 the entry is "Slut". */
    suspend fun decrement(id: Long): Outcome<Unit>

    suspend fun deleteEntry(id: Long): Outcome<Unit>

    /** "Packa upp" (#46): what was checked on the shopping list today and is not unpacked yet. */
    fun observeUnpackable(today: LocalDate): Flow<List<UnpackItem>>

    /** Puts the chosen items in the pantry (one entry each); skipped ones are remembered as done for today. */
    suspend fun unpack(choices: List<UnpackChoice>): Outcome<Int>

    /** Reads locations and entries again (pull to refresh). */
    suspend fun refresh(): Outcome<Unit>
}
