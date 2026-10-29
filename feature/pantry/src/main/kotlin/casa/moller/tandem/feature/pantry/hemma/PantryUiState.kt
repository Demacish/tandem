package casa.moller.tandem.feature.pantry.hemma

import androidx.compose.runtime.getValue
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.feature.pantry.PantrySyncNotice
import casa.moller.tandem.feature.pantry.navigation.PantryScanner
import kotlinx.datetime.LocalDate

/**
 * One stock entry on Hemma: the entry, the name of its place and how close its day is (computed
 * against the device's day, core:data `CalendarDays`, so it changes at midnight).
 */
data class PantryRow(val entry: InventoryEntry, val locationName: String, val urgency: ExpiryUrgency)

/** A storage place with its entries (amount > 0), soonest day first; an empty place is kept (so it can be filled). */
data class PantrySection(val location: InventoryLocation, val rows: List<PantryRow>)

/**
 * A food that is only marked "har hemma" (Tandoor `food_onhand`), without a stock entry: Hemma's
 * "Har hemma" list.
 *
 * @property foodId null for a staple whose food the server has not created yet.
 * @property staple it is one of the staples ("Basvaror"), so removing it goes through them.
 */
data class OnHandFood(val name: String, val foodId: Long?, val staple: Boolean)

/** Adding ([id] null) or renaming a storage place. */
data class LocationEditor(val id: Long?, val name: String, val isFreezer: Boolean) {
    val canSave: Boolean
        get() = name.isNotBlank()
}

/**
 * What Hemma shows (plan §8.8 "Skafferiet", features #26, #28, #46).
 *
 * @property today the device's day the expiry texts are counted from; null while loading.
 * @property location the one place shown (`PantryLocationKey`), null for every place.
 * @property onHand foods only marked "har hemma" (not shown on a single place's page).
 * @property unpackCount items checked on the shopping list today and not unpacked yet ("Packa upp", #46).
 * @property pendingCount unsent pantry changes ("3 ändringar väntar").
 * @property fridgePhoto the experimental fridge photo is set up (an AI service and key, B22) and not in demo mode.
 * @property moving the entry whose new place is being picked, null when none.
 */
data class PantryUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val householdMissing: Boolean = false,
    val today: LocalDate? = null,
    val location: InventoryLocation? = null,
    val locations: List<InventoryLocation> = emptyList(),
    val sections: List<PantrySection> = emptyList(),
    val onHand: List<OnHandFood> = emptyList(),
    val unpackCount: Int = 0,
    val pendingCount: Int = 0,
    val syncNotice: PantrySyncNotice? = null,
    val refreshing: Boolean = false,
    val fridgePhoto: Boolean = false,
    val moving: PantryRow? = null,
    val locationEditor: LocationEditor? = null,
    val confirmDelete: InventoryLocation? = null,
) {
    val canEdit: Boolean
        get() = !readOnly

    /** Nothing in the pantry yet: the empty state explains how to fill it. */
    val isEmpty: Boolean
        get() = sections.all { it.rows.isEmpty() } && onHand.isEmpty()

    /** The household has no storage places at all (a new Tandoor): "Lägg till Kyl, Frys och Skafferi". */
    val noLocations: Boolean
        get() = locations.isEmpty()

    /** Entries within three days of their day or past it. */
    val expiringCount: Int
        get() = sections.sumOf { section -> section.rows.count { it.urgency.isUrgent } }
}

/** What Hemma can ask for. */
sealed interface PantryAction {
    data object Refresh : PantryAction

    /** "Slut": amount 0 (with "Ångra"). */
    data class MarkEmpty(val entryId: Long) : PantryAction

    /** "−1" (with "Ångra"). */
    data class Decrement(val entryId: Long) : PantryAction

    /** "Flytta": asks for the new place. */
    data class StartMove(val entryId: Long) : PantryAction

    data class MoveTo(val locationId: Long) : PantryAction

    data object CancelMove : PantryAction

    /** Takes a food off "Har hemma" (with "Ångra"). */
    data class RemoveOnHand(val food: OnHandFood) : PantryAction

    data object Undo : PantryAction

    /** A new Tandoor without places: creates the given ones ("Kyl", "Frys" (freezer), "Skafferi" in the app's language). */
    data class CreateDefaultLocations(val places: List<Pair<String, Boolean>>) : PantryAction

    /** Opens the place editor: [locationId] null adds a new place. */
    data class EditLocation(val locationId: Long?) : PantryAction

    data class LocationName(val name: String) : PantryAction

    data class LocationFreezer(val isFreezer: Boolean) : PantryAction

    data object SaveLocation : PantryAction

    data object CloseLocationEditor : PantryAction

    /** "Ta bort platsen" asks first: Tandoor removes the place's entries with it. */
    data class AskDeleteLocation(val locationId: Long) : PantryAction

    data class ConfirmDeleteLocation(val confirmed: Boolean) : PantryAction

    // Navigation (handled by the route).

    data class OpenEntry(val entryId: Long) : PantryAction

    /** "Lägg till" by hand, in [locationId] when given. */
    data class AddEntry(val locationId: Long?) : PantryAction

    data class OpenLocation(val locationId: Long) : PantryAction

    data class OpenScanner(val scanner: PantryScanner, val locationId: Long?) : PantryAction

    data object OpenUnpack : PantryAction

    data object OpenMakeNow : PantryAction

    data object OpenStaples : PantryAction
}

/** What a snackbar says after a change on Hemma. */
sealed interface PantryMessage {
    data class MarkedEmpty(val name: String) : PantryMessage

    /** "−1": [amount] left (0 = it is "Slut" now). */
    data class Decremented(val name: String, val amount: Double, val unit: String?) : PantryMessage

    data class Moved(val name: String, val location: String) : PantryMessage

    data class OnHandRemoved(val name: String) : PantryMessage

    data class LocationsCreated(val count: Int) : PantryMessage

    data class LocationSaved(val name: String) : PantryMessage

    data class LocationDeleted(val name: String) : PantryMessage
}

/** One-off effects of the pantry ViewModels. */
sealed interface PantryEffect {
    /** A change was made; [undoable] shows "Ångra". */
    data class Show(val message: PantryMessage, val undoable: Boolean = false) : PantryEffect

    /** A write was refused (guest, local database) or a refresh the user asked for failed. */
    data class Failed(val error: TandemError) : PantryEffect
}
