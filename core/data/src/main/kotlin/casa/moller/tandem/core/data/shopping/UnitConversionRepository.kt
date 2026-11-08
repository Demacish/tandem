package casa.moller.tandem.core.data.shopping

import casa.moller.tandem.core.model.shopping.UnitConversions
import kotlinx.coroutines.flow.Flow

/**
 * The active account's units and unit conversions, for merged rows on the shopping list (Fas 5,
 * plan §8.2 "Sammanslagning i vyn"; docs/BYGGE.md "Sammanslagning och rutnät"). Offline first:
 * the space's units (`unit`) and the conversions cached with the small tables (per account, in
 * `sync_state`; a guest may not read conversions and gets none). Never throws; a broken cache
 * reads as [UnitConversions.NONE]. Demo mode reads the demo database (its units, no conversions).
 * Implemented in core:sync.
 */
interface UnitConversionRepository {
    /** The conversions of the active account; [UnitConversions.NONE] without an account. */
    fun observeConversions(): Flow<UnitConversions>
}

/**
 * How the active account shows the shopping list (Fas 5, plan §8.2 "Rutnätsvy med stora rutor"):
 * remembered per account on this device (DataStore), off by default. Implemented in core:sync.
 */
interface ShoppingViewPreferences {
    /** The list as a grid of large tiles instead of rows. */
    val gridView: Flow<Boolean>

    /** Turns the grid on or off for the active account (nothing happens without an account). */
    suspend fun setGridView(enabled: Boolean)
}
