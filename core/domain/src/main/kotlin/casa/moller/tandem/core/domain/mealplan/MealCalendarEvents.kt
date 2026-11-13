package casa.moller.tandem.core.domain.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

// Fas 5 (kalender lane, plan §7 #36): "Visa i telefonens kalender" writes the meal plan into a local
// calendar of the phone. This file is the pure half: which events the stored plan gives, and what
// has to change in the calendar so that it shows exactly those (idempotent, no duplicates).

/**
 * One event of the local calendar "Tandem – matsedel", in the calendar provider's terms.
 *
 * @property key the meal's stable key in the calendar (`SYNC_DATA1`): the account and the meal id.
 * @property title "Middag: Kycklinggryta", like Tandoor's own iCal feed (`<meal type>: <label>`).
 * @property description the meal's note, empty when none.
 * @property startMillis `DTSTART` in epoch milliseconds; for an all-day event midnight UTC of the first day.
 * @property endMillis `DTEND`; for an all-day event midnight UTC of the day after the last one.
 * @property allDay true when the meal type has no time.
 * @property timeZone `EVENT_TIMEZONE`: `UTC` for all-day events (the provider requires it), else the phone's zone.
 * @property fingerprint a hash of everything the calendar shows (`SYNC_DATA2`): a changed one is rewritten.
 */
data class MealCalendarEvent(
    val key: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
) {
    val fingerprint: String = fingerprintOf(listOf(key, title, description, startMillis, endMillis, allDay, timeZone))
}

/**
 * An event already in the local calendar.
 *
 * @property rowId the provider's `_ID`.
 * @property key its `SYNC_DATA1` (null for a row Tandem did not write; it is removed).
 * @property fingerprint its `SYNC_DATA2`.
 */
data class StoredCalendarEvent(val rowId: Long, val key: String?, val fingerprint: String?)

/**
 * What to do so that the calendar holds exactly the wanted events: [insert] new ones, [update]
 * changed ones (row id and the new event) and [delete] rows that are gone, moved out of the window,
 * duplicated or unknown. Empty when the calendar is already right.
 */
data class MealCalendarChanges(
    val insert: List<MealCalendarEvent> = emptyList(),
    val update: List<Pair<Long, MealCalendarEvent>> = emptyList(),
    val delete: List<Long> = emptyList(),
) {
    val isEmpty: Boolean
        get() = insert.isEmpty() && update.isEmpty() && delete.isEmpty()
}

/** The meal plan as calendar events (plan §7 #36, docs/BYGGE.md "Matsedeln i kalendern"). */
object MealCalendarEvents {
    /** How long a timed meal lasts in the calendar: Tandoor's own feed gives a meal one hour. */
    val MEAL_LENGTH = 60.minutes

    /** Days before today the calendar keeps (the sync's plan window, plan 3.5). */
    const val DAYS_BACK: Int = 14

    /** Days after today the calendar shows (the sync's plan window, plan 3.5). */
    const val DAYS_AHEAD: Int = 42

    /** The days [today] − [DAYS_BACK] .. [today] + [DAYS_AHEAD] whose meals are written. */
    fun window(today: LocalDate): ClosedRange<LocalDate> =
        today.minus(DAYS_BACK, DateTimeUnit.DAY)..today.plus(DAYS_AHEAD, DateTimeUnit.DAY)

    /** The calendar key of meal [mealId] of account [accountId] (ids of two servers never collide). */
    fun keyOf(accountId: String, mealId: Long): String = "$accountId/$mealId"

    /**
     * The events of [meals] that touch [window], one per meal (the same id twice gives one), in the
     * phone's [zone]: a meal whose type has a time starts then on its first day and ends an hour
     * after that time on its last day; otherwise it is an all-day event over its days. A meal
     * created offline (negative id) is written too; it gets its server id with the next sync and
     * its event is replaced then.
     */
    fun of(accountId: String, meals: List<MealPlanEntry>, window: ClosedRange<LocalDate>, zone: TimeZone): List<MealCalendarEvent> = meals
        .filter { it.toDate >= window.start && it.fromDate <= window.endInclusive }
        .distinctBy { it.id }
        .sortedWith(compareBy<MealPlanEntry> { it.fromDate }.thenBy { it.mealType.order }.thenBy { it.id })
        .map { eventOf(accountId, it, zone) }

    /** One meal as an event (see [of]). */
    fun eventOf(accountId: String, meal: MealPlanEntry, zone: TimeZone): MealCalendarEvent {
        val label = meal.displayTitle.trim()
        val type = meal.mealType.name.trim()
        val title = when {
            label.isEmpty() -> type
            type.isEmpty() -> label
            else -> "$type: $label"
        }
        val last = maxOf(meal.toDate, meal.fromDate)
        val time = meal.mealType.time
        return if (time == null) {
            MealCalendarEvent(
                key = keyOf(accountId, meal.id),
                title = title,
                description = meal.note.trim(),
                startMillis = meal.fromDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
                endMillis = last.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
                allDay = true,
                timeZone = TimeZone.UTC.id,
            )
        } else {
            // A time in the spring gap becomes the first moment after it (kotlinx-datetime's rule).
            val start = LocalDateTime(meal.fromDate, time).toInstant(zone)
            val end = LocalDateTime(last, time).toInstant(zone) + MEAL_LENGTH
            MealCalendarEvent(
                key = keyOf(accountId, meal.id),
                title = title,
                description = meal.note.trim(),
                startMillis = start.toEpochMilliseconds(),
                endMillis = end.toEpochMilliseconds(),
                allDay = false,
                timeZone = zone.id,
            )
        }
    }

    /**
     * What turns [stored] into [wanted]: unchanged rows stay, a changed event is updated in its
     * row, extra rows with the same key (duplicates) and rows whose key is no longer wanted (or
     * that have none) are deleted, and new keys are inserted. Running it again on the result gives
     * nothing to do.
     */
    fun changes(wanted: List<MealCalendarEvent>, stored: List<StoredCalendarEvent>): MealCalendarChanges {
        val wantedByKey = wanted.associateBy { it.key }
        val kept = mutableMapOf<String, StoredCalendarEvent>()
        val delete = mutableListOf<Long>()
        for (row in stored.sortedBy { it.rowId }) {
            val key = row.key
            when {
                key == null || key !in wantedByKey -> delete += row.rowId
                key in kept -> delete += row.rowId
                else -> kept[key] = row
            }
        }
        val insert = mutableListOf<MealCalendarEvent>()
        val update = mutableListOf<Pair<Long, MealCalendarEvent>>()
        for (event in wantedByKey.values) {
            val row = kept[event.key]
            when {
                row == null -> insert += event
                row.fingerprint != event.fingerprint -> update += row.rowId to event
            }
        }
        return MealCalendarChanges(insert = insert, update = update, delete = delete)
    }
}

/** 64-bit FNV-1a of the fields, as hex: stable across runs and devices (unlike `hashCode` of a data class). */
private fun fingerprintOf(fields: List<Any>): String {
    var hash = FNV_OFFSET
    for (byte in fields.joinToString(separator = "\u001f").encodeToByteArray()) {
        hash = hash xor (byte.toLong() and BYTE_MASK)
        hash *= FNV_PRIME
    }
    return hash.toULong().toString(HEX)
}

private const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL
private const val FNV_PRIME: Long = 0x100000001b3L
private const val BYTE_MASK: Long = 0xff
private const val HEX: Int = 16
