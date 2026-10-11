package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.WebAmountFormat
import kotlin.math.roundToInt

/**
 * Hours and minutes of a duration, for chips such as "1 h 15 min" (plan 6.2: tider) and for
 * accessibility texts built from plural resources ("1 hour 15 minutes").
 */
data class DurationParts(val hours: Int, val minutes: Int)

/** Recipe times as text: working, waiting and total time (plan 6.2, 6.7). */
object DurationText {
    /** Separates a number from its unit, so "15 min" never wraps between the two. */
    const val NUMBER_UNIT_SEPARATOR: Char = '\u00A0'

    /** [totalMinutes] split into hours and minutes; null for 0 or less ("no time given"). */
    fun parts(totalMinutes: Int): DurationParts? = if (totalMinutes <= 0) null else DurationParts(totalMinutes / 60, totalMinutes % 60)

    /**
     * "45 min", "2 h", "1 h 15 min" (the same short units in Swedish and English; a number and its
     * unit are joined by [NUMBER_UNIT_SEPARATOR]). Null for 0 or less, so the UI hides the chip.
     * Long times stay in hours ("12 h", "26 h").
     *
     * @param hourUnit the hour abbreviation, "h" by default (pass a string resource to change it).
     * @param minuteUnit the minute abbreviation, "min" by default.
     */
    fun format(totalMinutes: Int, hourUnit: String = "h", minuteUnit: String = "min"): String? {
        val parts = parts(totalMinutes) ?: return null
        val hours = if (parts.hours > 0) "${parts.hours}$NUMBER_UNIT_SEPARATOR$hourUnit" else null
        val minutes = if (parts.minutes > 0) "${parts.minutes}$NUMBER_UNIT_SEPARATOR$minuteUnit" else null
        return listOfNotNull(hours, minutes).joinToString(" ")
    }

    /** [format] of working plus waiting time (negative parts count as 0). */
    fun total(workingMinutes: Int, waitingMinutes: Int, hourUnit: String = "h", minuteUnit: String = "min"): String? =
        format(workingMinutes.coerceAtLeast(0) + waitingMinutes.coerceAtLeast(0), hourUnit, minuteUnit)
}

/** The servings line: the number, and Tandoor's free `servings_text` as its label. */
object ServingsText {
    /** At most this many decimals for fractional servings ("2,5"). */
    private const val DECIMALS = 2

    /** [servings] as a number in [style]: "4", "2,5", "0,33". Non-finite or negative input gives "0". */
    fun number(servings: Double, style: NumberStyle): String =
        WebAmountFormat.format(servings, useFractions = false, decimals = DECIMALS).text(style)

    /**
     * "4 portioner", "30 bullar": [number] followed by Tandoor's `servings_text`. Null when the
     * recipe has no `servings_text`; the UI then uses its own plural string ("4 servings",
     * "4 portioner"). A label that already starts with the number is returned as it is.
     */
    fun label(servings: Double, servingsText: String, style: NumberStyle): String? {
        val text = servingsText.trim()
        if (text.isEmpty()) return null
        val number = number(servings, style)
        if (text.startsWith("$number ")) return text
        return "$number $text"
    }
}

/**
 * A rating ready for stars and text (plan 6.2: betyg). Built with [of].
 *
 * @property value the rating clamped to 0..[MAX_STARS].
 * @property fullStars filled stars after rounding to the nearest half star.
 * @property halfStar one half-filled star after [fullStars].
 * @property text the rating with at most one decimal ("4", "4,5", "3,7") for labels and TalkBack.
 */
data class RatingDisplay(val value: Double, val fullStars: Int, val halfStar: Boolean, val text: String) {
    /** Outlined stars after the filled and half-filled ones. */
    val emptyStars: Int
        get() = MAX_STARS - fullStars - (if (halfStar) 1 else 0)

    companion object {
        /** Tandoor's rating scale: 0 to 5 stars. */
        const val MAX_STARS: Int = 5

        /**
         * The display of [rating] (Tandoor's 0..5, may have decimals), or null when the recipe
         * is unrated (null, 0 or less, not a number): the UI then shows no stars.
         */
        fun of(rating: Double?, style: NumberStyle): RatingDisplay? {
            if (rating == null || !rating.isFinite() || rating <= 0.0) return null
            val value = rating.coerceAtMost(MAX_STARS.toDouble())
            val halves = (value * 2.0).roundToInt()
            return RatingDisplay(
                value = value,
                fullStars = halves / 2,
                halfStar = halves % 2 == 1,
                text = WebAmountFormat.format(value, useFractions = false, decimals = 1).text(style),
            )
        }
    }
}
