package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import casa.moller.tandem.core.model.shopping.ShoppingList
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.model.sync.SyncPause
import kotlin.time.Instant

/**
 * Why the list may be out of date (plan 5.4, 6.2 "Offline: fullt fungerande, med en liten
 * indikator"). Changes are always kept on the device and sent later.
 */
enum class ShoppingSyncNotice {
    /** No network: changes wait ("3 ändringar väntar"). */
    OFFLINE,

    /** The server or something in front of it holds requests back (403 without detail, 429). */
    PAUSED,

    /**
     * Tandem itself paused the sync after many refused requests in a short time (the circuit
     * breaker, plan 5.4; fix-ui: its own text, not "the server holds requests back").
     */
    BREAKER,

    /** The login expired: nothing is sent until the user logs in again (the shell's "Logga in igen" banner). */
    LOGIN,

    /** Untested server version: the list is read, changes wait (plan 3.6). */
    SAFE_MODE,
    ;

    companion object {
        fun of(pause: SyncPause?): ShoppingSyncNotice? = when (pause) {
            SyncPause.OFFLINE -> OFFLINE
            SyncPause.BLOCKED -> PAUSED
            SyncPause.CIRCUIT_BREAKER -> BREAKER
            SyncPause.UNAUTHORIZED -> LOGIN
            SyncPause.SAFE_MODE -> SAFE_MODE
            SyncPause.IMPORT_RUNNING, null -> null
        }
    }
}

/**
 * The "Lägg till" sheet (phones) or pane (tablets), feature #11.
 *
 * @property text what the user typed or pasted; several items by line or comma.
 * @property preview how each item will be added, by the local parser while typing (Tandoor's
 *   parser creates foods, so it only runs when the user adds).
 * @property suggestions stored foods and earlier entries matching the last item (one tap adds).
 * @property recent foods bought recently (from the local history), shown while [text] is empty.
 * @property adding the server parser and the repository are busy.
 */
data class AddItemState(
    val text: String = "",
    val preview: List<ParsedIngredient> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val adding: Boolean = false,
)

/**
 * The edit sheet of one entry (long press, plan 6.2 "Lång tryckning → ändra eller flytta kategori").
 *
 * @property categoryId the food's aisle; [canChangeCategory] is false for a food the server does
 *   not know yet (created offline), whose aisle cannot be set until it is sent.
 * @property amountError the amount is not a number.
 * @property recipeRows Phase 2 (koppling lane): rows on the list from the same recipe or meal as
 *   this one ("Ta bort … från listan" removes them all), 0 for a row without a recipe.
 * @property lists Tandoor's named lists the entry can be moved between (fix-ui, plan 3.5 "Flytt
 *   mellan listor"); [listIds] the ones it is on in the sheet ("Spara" sends a change).
 */
data class EditEntryState(
    val entry: ShoppingEntry,
    val food: String,
    val amount: String,
    val unit: String,
    val categoryId: Long?,
    val categories: List<SupermarketCategory> = emptyList(),
    val delayed: Boolean = false,
    val amountError: Boolean = false,
    val saving: Boolean = false,
    val recipeRows: Int = 0,
    val lists: List<ShoppingList> = emptyList(),
    val listIds: Set<Long> = entry.listIds,
) {
    val canChangeCategory: Boolean
        get() = entry.food.id != null
}

/**
 * What the Handla tab shows (plan 6.2 "Handla", Stitch ink_pslista_*).
 *
 * @property loading nothing read from the device yet.
 * @property readOnly guest (#42): no edit controls at all.
 * @property lists Tandoor's named lists; [selectedListId] null = every entry.
 * @property store the store the list is sorted by, null = by category name.
 * @property grouped the list by aisle, "Köp senare" and "Avbockat".
 * @property pendingCount unsent changes ("3 ändringar väntar").
 * @property syncNotice why the list may be out of date, null when it syncs.
 * @property checkedExpanded "Avbockat" is open (collapsed by default).
 * @property laterExpanded "Köp senare" is open.
 * @property add the add sheet, null while it is closed (on tablets the pane is always there).
 * @property edit the edit sheet, null while closed.
 * @property confirmClearChecked "Rensa avbockade?" is asked.
 * @property progressSince only entries checked from this time on count as done in the progress
 *   line (fix-ui: Tandoor keeps showing entries checked on earlier trips for days); null counts
 *   every checked entry.
 */
data class ShoppingUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val lists: List<ShoppingList> = emptyList(),
    val selectedListId: Long? = null,
    val supermarkets: List<Supermarket> = emptyList(),
    val store: Supermarket? = null,
    val categories: List<SupermarketCategory> = emptyList(),
    val grouped: GroupedShopping = GroupedShopping(),
    val amountStyle: AmountStyle = AmountStyle(),
    val pendingCount: Int = 0,
    val syncNotice: ShoppingSyncNotice? = null,
    val refreshing: Boolean = false,
    val checkedExpanded: Boolean = false,
    val laterExpanded: Boolean = true,
    val add: AddItemState = AddItemState(),
    val addSheetOpen: Boolean = false,
    val edit: EditEntryState? = null,
    val confirmClearChecked: Boolean = false,
    val history: List<ShoppingHistoryItem> = emptyList(),
    val progressSince: Instant? = null,
) {
    val canEdit: Boolean
        get() = !readOnly

    /** Entries checked since [progressSince] out of those and all that are to buy now ("2 av 9"). */
    val progressDone: Int
        get() = grouped.checked.count { entry -> progressSince == null || (entry.completedAt ?: progressSince) >= progressSince }

    val progressTotal: Int
        get() = grouped.toBuyCount + progressDone
}

/** What the Handla tab can ask for. */
sealed interface ShoppingAction {
    data object Refresh : ShoppingAction

    /** Tap or swipe on a row: check or uncheck it. */
    data class ToggleChecked(val id: Long) : ShoppingAction

    /** "Bocka av alla" in one aisle (bulk). */
    data class CheckSection(val sectionKey: String) : ShoppingAction

    /** "Köp senare" (#14) or, for a delayed entry, "Köp nu". */
    data class ToggleLater(val id: Long) : ShoppingAction

    data object Undo : ShoppingAction

    data class SelectList(val listId: Long?) : ShoppingAction

    data class SelectStore(val supermarketId: Long?) : ShoppingAction

    data object ToggleCheckedExpanded : ShoppingAction

    data object ToggleLaterExpanded : ShoppingAction

    data object AskClearChecked : ShoppingAction

    data class ConfirmClearChecked(val confirmed: Boolean) : ShoppingAction

    // "Lägg till" (#11)
    data object OpenAdd : ShoppingAction

    data object CloseAdd : ShoppingAction

    data class AddTextChanged(val text: String) : ShoppingAction

    /** Adds everything typed (parsed by Tandoor's parser, or locally offline). */
    data object SubmitAdd : ShoppingAction

    /** One tap on a suggestion or a recent food: adds it at once. */
    data class AddFood(val name: String) : ShoppingAction

    // Edit sheet
    data class OpenEdit(val id: Long) : ShoppingAction

    data object CloseEdit : ShoppingAction

    data class EditFood(val text: String) : ShoppingAction

    data class EditAmount(val text: String) : ShoppingAction

    data class EditUnit(val text: String) : ShoppingAction

    data class EditCategory(val categoryId: Long?) : ShoppingAction

    /** fix-ui: puts the edited entry on, or takes it off, the named list [listId] ("Spara" sends it). */
    data class EditList(val listId: Long) : ShoppingAction

    data object SaveEdit : ShoppingAction

    data object DeleteEdited : ShoppingAction

    /** Phase 2 (koppling lane, #12): removes the edited row's recipe with all its rows. */
    data object RemoveEditedRecipe : ShoppingAction

    // Navigation (handled by the route)
    data object OpenStoreMode : ShoppingAction

    data object OpenStoreChoice : ShoppingAction

    data object OpenHistory : ShoppingAction

    data class OpenRecipe(val recipeId: Long) : ShoppingAction

    /** "Dela som text" (#13); the route builds the text in the app's language and opens the share sheet. */
    data object Share : ShoppingAction
}

/** What a snackbar says after a change. */
sealed interface ShoppingMessage {
    data class Checked(val name: String, val count: Int) : ShoppingMessage

    data class Unchecked(val name: String) : ShoppingMessage

    data class Delayed(val name: String) : ShoppingMessage

    data class Undelayed(val name: String) : ShoppingMessage

    data class Added(val name: String, val count: Int) : ShoppingMessage

    data class Removed(val name: String) : ShoppingMessage

    data class ClearedChecked(val count: Int) : ShoppingMessage

    /** Phase 2 (koppling lane): a recipe and its rows left the list. */
    data class RecipeRemoved(val name: String) : ShoppingMessage

    data object Saved : ShoppingMessage
}

/** One-off effects of the shopping ViewModels (plan 5.3). */
sealed interface ShoppingEffect {
    /** A change was made; [undoable] shows "Ångra" ([ShoppingAction.Undo]). */
    data class Show(val message: ShoppingMessage, val undoable: Boolean = false) : ShoppingEffect

    /** A write was refused (guest, local database) or a refresh the user asked for failed. */
    data class Failed(val error: TandemError) : ShoppingEffect

    /**
     * Other household members changed the list (plan 6.2 "Anna lade till Mjölk"): the changes of
     * one read together (fix-ui), shown as one message and never in place of an "Ångra".
     */
    data class OtherChange(val changes: List<ShoppingChange>) : ShoppingEffect {
        constructor(change: ShoppingChange) : this(listOf(change))
    }
}
