package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.pantry.InventoryEntry
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Which day the foods of one expiry notification expire, relative to the day it is posted. */
enum class ExpiryDay {
    TODAY,
    TOMORROW,

    /** Some today, some tomorrow. */
    MIXED,
}

/**
 * One pantry entry in the expiry notification.
 *
 * @property key what "the same item" means for "never twice the same day": the food's name and the
 *   expiry date (entry and location ids may still be temporary and change when they are sent).
 */
data class ExpiryReminderItem(
    val key: String,
    val foodId: Long?,
    val name: String,
    val locationId: Long,
    val expires: LocalDate,
)

/**
 * What one notification says: "Grädde går ut i morgon – 3 recept", or "Grädde och 2 till går ut i
 * morgon – 5 recept" ([first] and [others]); [recipeCount] 0 leaves the recipes out.
 */
data class ExpiryReminderSummary(val items: List<ExpiryReminderItem>, val day: ExpiryDay, val recipeCount: Int) {
    init {
        require(items.isNotEmpty()) { "at least one item" }
    }

    val first: ExpiryReminderItem
        get() = items.first()

    val others: Int
        get() = items.size - 1
}

/**
 * Fas 4 (skafferinotis lane), plan §8.8 "Använd upp" and §8.7 "Måltidspåminnelser": the pure rules
 * of the optional morning notification about pantry entries that expire today or tomorrow. Times
 * are the device's wall clock in [TimeZone] (a phone that travels follows its new zone; a DST
 * change moves the next run to 08:00 of the new offset).
 */
object ExpiryReminder {
    /** The check runs around this hour of the morning (WorkManager may run it later, never at night). */
    const val MORNING_HOUR: Int = 8

    /** Nothing is posted from this hour ... */
    const val QUIET_FROM_HOUR: Int = 21

    /** ... until this hour: a run deferred into the night waits for the next morning's. */
    const val QUIET_UNTIL_HOUR: Int = 7

    /** Entries expiring today (0) or tomorrow (1); already expired ones were announced before. */
    const val DAYS_AHEAD: Int = 1

    /** The calendar day of [now] in [zone]. */
    fun today(now: Instant, zone: TimeZone): LocalDate = now.toLocalDateTime(zone).date

    /** Between [QUIET_FROM_HOUR] and [QUIET_UNTIL_HOUR] local time nothing is posted. */
    fun isQuiet(now: Instant, zone: TimeZone): Boolean {
        val hour = now.toLocalDateTime(zone).hour
        return hour >= QUIET_FROM_HOUR || hour < QUIET_UNTIL_HOUR
    }

    /**
     * How long from [now] until the next [hour]:00 in [zone] (today's when it is still ahead,
     * otherwise tomorrow's). A wall-clock time that does not exist on a DST day (a gap) resolves
     * to the instant just after it; one that exists twice to the earlier one.
     */
    fun delayUntilNext(now: Instant, zone: TimeZone, hour: Int = MORNING_HOUR): Duration {
        val date = today(now, zone)
        val todays = LocalDateTime(date, LocalTime(hour, 0)).toInstant(zone)
        val target = if (todays > now) todays else LocalDateTime(date.plus(1, DateTimeUnit.DAY), LocalTime(hour, 0)).toInstant(zone)
        return target - now
    }

    /** The entries with stock that expire on [today] or the day after, soonest first, then by name. */
    fun due(entries: List<InventoryEntry>, today: LocalDate): List<ExpiryReminderItem> = entries.asSequence()
        .filter { entry -> !entry.isEmpty && entry.daysLeft(today)?.let { it in 0..DAYS_AHEAD } == true }
        .mapNotNull { entry ->
            val expires = entry.expires ?: return@mapNotNull null
            val name = entry.food.name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ExpiryReminderItem(key = keyOf(name, expires), foodId = entry.food.id, name = name, locationId = entry.locationId, expires = expires)
        }
        .distinctBy { it.key }
        .sortedWith(compareBy<ExpiryReminderItem> { it.expires }.thenBy { it.name.lowercase() })
        .toList()

    /** "Same item": the food's name (any case) and its day. */
    fun keyOf(name: String, expires: LocalDate): String = "${name.trim().lowercase()}@$expires"

    /**
     * The notification for [due] on [today], or null when every item was already in a notification
     * today ([notifiedToday], keys of [ExpiryReminderItem.key]); a new item brings the whole list
     * again (one grouped notification, replacing the morning's).
     */
    fun summary(due: List<ExpiryReminderItem>, notifiedToday: Set<String>, today: LocalDate, recipeCount: Int): ExpiryReminderSummary? {
        if (due.isEmpty() || due.all { it.key in notifiedToday }) return null
        val days = due.map { it.expires }.toSet()
        val day = when {
            days.size > 1 -> ExpiryDay.MIXED
            days.single() == today -> ExpiryDay.TODAY
            else -> ExpiryDay.TOMORROW
        }
        return ExpiryReminderSummary(items = due, day = day, recipeCount = recipeCount)
    }

    /**
     * How many "Kan laga nu" recipes use at least one of [foodIds] (from `MakeNowRecipe.expiring`, the
     * same count as Idag's "Går ut snart"); drinks do not count.
     */
    fun recipeCount(recipes: List<MakeNowRecipe>, foodIds: Set<Long>): Int = recipes.asSequence()
        .filterNot { recipe -> recipe.recipe.keywords.any { it.name.equals(DrinkKeywords.DRINK, ignoreCase = true) } }
        .filter { recipe -> recipe.expiring.any { it.foodId in foodIds } }
        .map { it.recipe.id }
        .toSet()
        .size
}
