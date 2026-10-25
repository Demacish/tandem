package casa.moller.tandem.feature.mealplan

import android.content.res.Resources
import android.text.format.DateFormat
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate

/**
 * Dates and amounts of the meal plan in the user's language (plan 6.7: "mån 6 okt", "Mon, Oct 6",
 * ISO weeks). Days are calendar days without a time, so no time zone is involved.
 */
internal class MealPlanFormat(private val locale: Locale) {
    private val fullDay = formatter("EEEEdMMMM")
    private val shortDay = formatter("EEEdMMM")
    private val weekday = formatter("EEEE")
    private val shortWeekday = formatter("EEE")
    private val dayOfMonth = formatter("d")
    private val dayMonth = formatter("dMMMM")
    private val shortDayMonth = formatter("dMMM")
    private val month = formatter("LLLL")

    /** "Måndag 21 oktober" / "Monday, October 21": day headings and TalkBack. */
    fun fullDay(date: LocalDate): String = fullDay.format(date.toJavaLocalDate()).capitalized()

    /** The same inside a sentence: "flyttades till torsdag 22 oktober", "Thursday, October 22". */
    fun fullDayInText(date: LocalDate): String = fullDay.format(date.toJavaLocalDate())

    /** "mån 21 okt." / "Mon, Oct 21": compact chips. */
    fun shortDay(date: LocalDate): String = shortDay.format(date.toJavaLocalDate())

    /** "Måndag" / "Monday". */
    fun weekday(date: LocalDate): String = weekday.format(date.toJavaLocalDate()).capitalized()

    /** The weekday as written inside a sentence: "lördag" in "Lägg till i matsedeln (lördag)", "Saturday". */
    fun weekdayInText(date: LocalDate): String = weekday.format(date.toJavaLocalDate())

    /** "mån" / "Mon": the tablet grid's column heads. */
    fun shortWeekday(date: LocalDate): String = shortWeekday.format(date.toJavaLocalDate()).capitalized()

    /** "21". */
    fun dayOfMonth(date: LocalDate): String = dayOfMonth.format(date.toJavaLocalDate())

    /** "21 oktober". */
    fun dayMonth(date: LocalDate): String = dayMonth.format(date.toJavaLocalDate())

    /** The ISO week number ("Vecka 43"). */
    fun weekNumber(date: LocalDate): Int = date.toJavaLocalDate().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** "21–27 oktober", "28 sep. – 4 okt.": the shown week. */
    fun range(start: LocalDate, end: LocalDate, resources: Resources): String = if (start.month == end.month) {
        resources.getString(R.string.mealplan_range_same_month, dayOfMonth(start), dayOfMonth(end), month.format(end.toJavaLocalDate()))
    } else {
        val first = shortDayMonth.format(start.toJavaLocalDate())
        resources.getString(R.string.mealplan_range, first, shortDayMonth.format(end.toJavaLocalDate()))
    }

    /** "4", "1,5": servings in the user's locale. */
    fun number(value: Double): String = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(value)
    }

    /** "4 portioner", "1,5 portioner". */
    fun servings(value: Double, resources: Resources): String {
        val quantity = if (value % 1.0 == 0.0 && value < Int.MAX_VALUE) value.toInt() else Int.MAX_VALUE
        return resources.getQuantityString(R.plurals.mealplan_servings, quantity, number(value))
    }

    private fun formatter(skeleton: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private fun String.capitalized(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
