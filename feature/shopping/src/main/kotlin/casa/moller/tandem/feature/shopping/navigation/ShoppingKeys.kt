package casa.moller.tandem.feature.shopping.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

// Phase 2 (grund lane): the shopping list's destinations (plan 6.1/6.2 "Handla"). The tab root is
// the shell's `ShoppingKey` (the whole list); these are pushed on the Handla tab's stack. All keys
// are @Serializable so the back stack survives process death (R8 keeps them, app/proguard-rules.pro).

/**
 * The list of one named Tandoor shopping list, or sorted for one store, opened from elsewhere
 * (Idag's "Handla", a recipe's "Visa listan").
 *
 * @property listId only entries on this named list; null = every entry.
 * @property supermarketId sort by this store's aisle order; null = the device's chosen store.
 */
@Serializable
data class ShoppingListKey(val listId: Long? = null, val supermarketId: Long? = null) : NavKey

/**
 * Store mode (plan 6.2 "Butiksläge", feature #13): large text, the screen stays on, aisle order.
 * Full screen on every width, like cook mode.
 */
@Serializable
data class StoreModeKey(val listId: Long? = null, val supermarketId: Long? = null) :
    NavKey,
    FullScreenDestination {
    override val hidesNavigationOnAllWidths: Boolean
        get() = true
}

/** Choosing the named list and the store (Stitch `butiksl_ge` chips; a sheet on phones, a pane on tablets). */
@Serializable
data object ShoppingListChoiceKey : NavKey

/** The local history of bought items (plan 8.2 "Lokal historik"). */
@Serializable
data object ShoppingHistoryKey : NavKey, FullScreenDestination
