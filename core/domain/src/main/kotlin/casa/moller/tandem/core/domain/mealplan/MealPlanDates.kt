package casa.moller.tandem.core.domain.mealplan

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * Date-only semantics of the meal plan (plan 3.4, 6.2 "Veckan"; FACTS "Fas 1 g" and "Fas 2 f"),
 * pure so that every platform reads and writes days the same way.
 *
 * Tandoor stores `from_date`/`to_date` as date-times and answers in the SERVER's offset
 * (`2026-10-25T00:30:00+02:00`). The day a meal belongs to is the date written in that answer,
 * whatever zone the phone is in: Tandoor lists a meal under the server-local date of its `to_date`,
 * and the web shows it on that day too.
 *
 * Writing, the app sends a NAIVE server-local date-time (`2026-10-26T00:30:00`, no offset): Django
 * reads a naive value in the server's own time zone, so the meal lands on that day even across a
 * daylight saving change (an offset taken from another day would move a meal near midnight to the
 * neighbouring day). A midnight value gets the meal type's time on create (FACTS "Fas 1 g").
 */
object MealPlanDates {
    private const val DATE_LENGTH = 10
    private const val TIME_START = 11
    private const val TIME_END = 19

    /** The server-local day of a Tandoor date-time or date (`2000-01-15T18:00:00+01:00` → 2000-01-15); null when unreadable. */
    fun serverDay(value: String?): LocalDate? {
        val text = value?.trim() ?: return null
        if (text.length < DATE_LENGTH) return null
        return runCatching { LocalDate.parse(text.substring(0, DATE_LENGTH)) }.getOrNull()
    }

    /** The server-local time of day of a Tandoor date-time (`...T18:00:00+01:00` → 18:00); null for a date only or when unreadable. */
    fun serverTime(value: String?): LocalTime? {
        val text = value?.trim() ?: return null
        if (text.length < TIME_END || text[DATE_LENGTH] != 'T') return null
        return runCatching { LocalTime.parse(text.substring(TIME_START, TIME_END)) }.getOrNull()
    }

    /**
     * What to send for [date]: a naive server-local date-time, keeping [time] (the meal's time of day
     * in the server's zone, from its last answer) or midnight, which Tandoor replaces with the meal
     * type's time when it creates a meal.
     */
    fun toServer(date: LocalDate, time: LocalTime? = null): String {
        val clock = time ?: LocalTime(0, 0)
        return "${date}T${two(clock.hour)}:${two(clock.minute)}:${two(clock.second)}"
    }

    /**
     * The days of a meal moved to start on [newFrom]: the last day keeps the meal's length (Tandoor
     * keeps `to_date` when only `from_date` changes, FACTS "Fas 2 f", so a move always sends both).
     */
    fun moved(from: LocalDate, to: LocalDate, newFrom: LocalDate): Pair<LocalDate, LocalDate> {
        val length = from.daysUntil(to).coerceAtLeast(0)
        return newFrom to newFrom.plus(length, DateTimeUnit.DAY)
    }

    /** True when a meal of [from]..[to] is on [day] (a `to_date` before `from_date` counts as one day). */
    fun covers(from: LocalDate, to: LocalDate, day: LocalDate): Boolean = day >= from && day <= maxOf(from, to)

    private fun two(value: Int): String = value.toString().padStart(2, '0')
}
