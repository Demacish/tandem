package casa.moller.tandem.feature.pantry.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

// Fas S (grund lane): the destinations of "Hemma" (plan §8.8, §6.1). feature:shell maps every key
// to [casa.moller.tandem.feature.pantry.PantryRoute]; where they are opened from is in
// docs/BYGGE.md "Fas S – kontrakt" (Mer's first row "Hemma", Idag's "Kan laga nu"/"Går ut snart",
// Handla's "Packa upp"). Ids may be temporary (negative) until the server knows the object.

/** Every destination of feature:pantry (the shell shows them through one route). */
sealed interface PantryDestination : NavKey

/** "Hemma": the pantry by location (two columns on tablets, plan §10 Fas S "Klart när"). */
@Serializable
data object PantryKey : PantryDestination

/** One location (Kyl, Frys, Skafferi) with its entries. */
@Serializable
data class PantryLocationKey(val locationId: Long) : PantryDestination

/**
 * Add or edit a stock entry: [entryId] null = a new entry (in [locationId] when given). A form,
 * so full screen on phones (like the plan-meal sheet).
 */
@Serializable
data class PantryEntryKey(val entryId: Long? = null, val locationId: Long? = null) :
    PantryDestination,
    FullScreenDestination

/** "Packa upp" (#46): today's checked shopping entries into the pantry. */
@Serializable
data object UnpackKey : PantryDestination

/**
 * "Kan laga nu" (#23): Har allt / Saknar 1 / Saknar 2. [useUpFirst] opens it from "Går ut snart"
 * (#28), with the recipes using soon-expiring foods first (they always come first in a group).
 */
@Serializable
data class MakeNowKey(val useUpFirst: Boolean = false) : PantryDestination

/** "Basvaror": the staples marked "har hemma". */
@Serializable
data object StaplesKey : PantryDestination
