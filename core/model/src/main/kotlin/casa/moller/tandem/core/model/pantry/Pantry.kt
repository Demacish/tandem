package casa.moller.tandem.core.model.pantry

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

// Fas S (grund lane): the pantry as the screens see it (plan §8.8 "Skafferiet", features #26, #28,
// #46). Same id rule as the shopping list: an object created offline has a NEGATIVE id until the
// server knows it; a repository resolves an old negative id once it has one.

/**
 * A storage place (Tandoor `InventoryLocation`): Kyl, Frys, Skafferi.
 *
 * @property isFreezer the freezer offers longer expiry choices ([ExpiryChoice.forLocation]).
 * @property pending a change of it waits in the outbox.
 */
data class InventoryLocation(val id: Long, val name: String, val isFreezer: Boolean = false, val pending: Boolean = false) {
    val isLocalOnly: Boolean
        get() = id < 0
}

/** A food in the pantry; [id] null for a food the server has not created yet. */
data class PantryFood(val id: Long?, val name: String, val pluralName: String? = null)

/** A unit in the pantry (Tandoor requires one on every entry, FACTS "Fas S b"). */
data class PantryUnit(val id: Long?, val name: String)

/**
 * One stock entry (Tandoor `InventoryEntry`).
 *
 * @property amount 0 = "Slut" (kept on the server, hidden in the pantry).
 * @property expires the best-before day, null = no date.
 * @property subLocation free text inside the location ("Översta hyllan").
 */
data class InventoryEntry(
    val id: Long,
    val locationId: Long,
    val food: PantryFood,
    val unit: PantryUnit? = null,
    val amount: Double = 1.0,
    val expires: LocalDate? = null,
    val note: String? = null,
    val subLocation: String? = null,
    val pending: Boolean = false,
) {
    val isLocalOnly: Boolean
        get() = id < 0

    /** "Slut": nothing left. */
    val isEmpty: Boolean
        get() = amount <= 0.0

    /** Days from [today] to [expires] (negative = expired), null without a date. */
    fun daysLeft(today: LocalDate): Int? = expires?.let { today.daysUntil(it) }

    /** Expires within [days] days of [today] (also when already expired); the pantry colours these. */
    fun expiresSoon(today: LocalDate, days: Int = EXPIRING_SOON_DAYS): Boolean = daysLeft(today)?.let { it <= days } == true

    companion object {
        /** "Går ut snart" and "Använd upp" (#28): within 3 days (plan §8.8). */
        const val EXPIRING_SOON_DAYS: Int = 3
    }
}

/** A location with its entries (amount > 0), soonest expiry first, as the pantry lists them. */
data class PantryGroup(val location: InventoryLocation, val entries: List<InventoryEntry>)

/**
 * A new stock entry (from "Packa upp", a scan or by hand).
 *
 * @property foodId the food's server id when known; otherwise the server finds or creates it by [foodName].
 * @property unitName required by the server: the repository falls back to a default unit ("st") when null.
 */
data class InventoryEntryDraft(
    val locationId: Long,
    val foodName: String,
    val foodId: Long? = null,
    val amount: Double = 1.0,
    val unitName: String? = null,
    val expires: LocalDate? = null,
    val note: String? = null,
    val subLocation: String? = null,
)

/** A change of a stock entry; null fields stay as they are, [clearExpires] removes the date. */
data class InventoryEntryUpdate(
    val amount: Double? = null,
    val locationId: Long? = null,
    val expires: LocalDate? = null,
    val clearExpires: Boolean = false,
    val note: String? = null,
    val subLocation: String? = null,
)

/** The quick expiry choices (plan §8.8 "Packa upp": +3 dagar, +1 vecka, +1 månad eller inget). */
enum class ExpiryChoice(private val period: DatePeriod?) {
    NONE(null),
    PLUS_3_DAYS(DatePeriod(days = 3)),
    PLUS_1_WEEK(DatePeriod(days = 7)),
    PLUS_1_MONTH(DatePeriod(months = 1)),
    PLUS_3_MONTHS(DatePeriod(months = 3)),
    ;

    /** The day this choice means, counted from [today]; null for [NONE]. */
    fun dateFrom(today: LocalDate): LocalDate? = period?.let { today.plus(it) }

    companion object {
        /** The choices offered for a location: the freezer gets +3 months instead of +3 days. */
        fun forLocation(location: InventoryLocation?): List<ExpiryChoice> = if (location?.isFreezer == true) {
            listOf(PLUS_1_MONTH, PLUS_3_MONTHS, NONE)
        } else {
            listOf(PLUS_3_DAYS, PLUS_1_WEEK, PLUS_1_MONTH, NONE)
        }
    }
}

/**
 * "Packa upp" (#46): an item checked on the shopping list today, to be put in the pantry.
 *
 * @property shoppingEntryId the shopping list entry it came from.
 */
data class UnpackItem(val shoppingEntryId: Long, val food: PantryFood, val amount: Double, val unit: PantryUnit? = null)

/** The user's choice for one [UnpackItem]: where it goes and until when; [include] false skips it. */
data class UnpackChoice(val item: UnpackItem, val locationId: Long, val expires: LocalDate? = null, val include: Boolean = true)

/**
 * A staple ("Basvaror": salt, peppar, olja, vatten ...): without them almost no recipe "has all"
 * (plan §8.8). The on-hand flag is Tandoor's `food_onhand`.
 *
 * @property foodId the food's server id, null until Tandoor has a food of that name.
 */
data class Staple(val name: String, val foodId: Long? = null, val onHand: Boolean = false)
