package casa.moller.tandem.core.data.shopping

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import casa.moller.tandem.core.model.shopping.ShoppingList
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * The shopping list (plan 3.5, 5.7, 6.2 "Handla", features #9, #11-#14), offline first: every
 * change is stored at once and queued in the outbox (`shopping_entry` / `shopping_list_recipe`
 * rows, docs/BYGGE.md "Fas 2 – kontrakt"); the sync sends it. Implemented in core:sync by the
 * shopping lane (until then a temporary binding in :app).
 *
 * Contract for every implementation (the real one, demo mode and core:testing's fake):
 * - **Threading:** main-safe; flows may be collected on the main thread.
 * - **Account:** scoped to the active account; demo mode uses the demo database and never the network.
 * - **Ids:** entries created offline have negative ids; every function also accepts such an id
 *   after the entry reached the server (it is resolved through `id_mapping`).
 * - **Read-only:** for a guest (`SpacePermissions.readOnly`) every write fails with
 *   `DataProblem.READ_ONLY` and nothing is stored or queued (#42).
 * - **Errors:** flows never throw for server or network problems; writes succeed offline. Only a
 *   local database failure is an [Outcome.Failure] (`TandemError.Unknown`).
 */
interface ShoppingRepository {
    /** The entries matching [filter] (checked ones too, the screen groups them), re-emitted on every change. */
    fun observeEntries(filter: ShoppingFilter = ShoppingFilter()): Flow<List<ShoppingEntry>>

    /** The named Tandoor lists for the list chips ("listval", #10). */
    fun observeLists(): Flow<List<ShoppingList>>

    /** Unsent shopping changes in the outbox ("3 ändringar väntar"). */
    fun observePendingCount(): Flow<Int>

    /**
     * Changes made by other household members, noticed by a sync or a poll (plan 6.2 "Anna lade
     * till Mjölk"). Hot: only changes after collection starts; never this device's own changes.
     */
    fun observeChanges(): Flow<ShoppingChange>

    /** Adds one entry ("Lägg till vara…", a suggestion). Returns the new entry's (negative) id. */
    suspend fun add(draft: ShoppingEntryDraft): Outcome<Long>

    /** Adds several entries at once ("mjölk, bröd, 6 ägg"), in order. Returns their ids. */
    suspend fun addAll(drafts: List<ShoppingEntryDraft>): Outcome<List<Long>>

    /** "Lägg på listan" from a recipe or meal (#12, #16): one list recipe with the chosen ingredients, scaled. */
    suspend fun addRecipe(request: RecipeShoppingRequest): Outcome<Unit>

    /**
     * Checks or unchecks entries (tap, swipe). Queued as BULK_CHECK rows batched into one
     * `POST /api/shopping-list-entry/bulk/` per value; check + uncheck before sending cancel out.
     */
    suspend fun setChecked(ids: Collection<Long>, checked: Boolean): Outcome<Unit>

    /**
     * "Köp senare" (#14): delays the entries until [until] (the screen passes now +
     * `UserPreferences.defaultDelayHours`), or undoes it with null (sent as Tandoor's `1970-01-01`).
     */
    suspend fun setDelayed(ids: Collection<Long>, until: Instant?): Outcome<Unit>

    /** Changes an entry's amount, unit or food (long press → edit). Null leaves a field as it is. */
    suspend fun update(id: Long, amount: Double? = null, unitName: String? = null, foodName: String? = null): Outcome<Unit>

    /** Moves entries to the named lists [listIds] (a PATCH of `shopping_lists`, which bumps `updated_at`; bulk does not). */
    suspend fun setLists(ids: Collection<Long>, listIds: Set<Long>): Outcome<Unit>

    /** Removes entries. An entry that never reached the server just disappears with its queued changes. */
    suspend fun remove(ids: Collection<Long>): Outcome<Unit>

    /** Removes a recipe's or meal's whole group ("Ta bort receptet från listan"); Tandoor deletes its entries too. */
    suspend fun removeListRecipe(listRecipeId: Long): Outcome<Unit>

    /**
     * Removes the checked entries among [ids] (on this device and on the server): the ones the
     * screen shows and counts, so a named list's "Rensa" never deletes other lists' checked rows
     * (0.3.0 review, fix-data). Ids that are not checked (any more) are left alone.
     */
    suspend fun clearChecked(ids: Collection<Long>): Outcome<Unit>

    /**
     * Reads the list from the server now (pull to refresh): a delta with `updated_after`, or the
     * whole list when [full] (plan 3.5 window rule decides what a missing entry means).
     */
    suspend fun refresh(full: Boolean = false): Outcome<Unit>

    /** The list as text in the store's aisle order, for "Dela som text" (#13). */
    suspend fun shareText(filter: ShoppingFilter = ShoppingFilter()): Outcome<String>

    /** Food suggestions for the add field: stored foods and earlier entries matching [query] (#11 autocomplete). */
    fun observeSuggestions(query: String, limit: Int = 10): Flow<List<String>>

    /** The local history of archived entries (plan 8.2), newest first. */
    fun observeHistory(): Flow<List<ShoppingHistoryItem>>
}
