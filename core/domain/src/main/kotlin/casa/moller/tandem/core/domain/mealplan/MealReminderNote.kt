package casa.moller.tandem.core.domain.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import kotlinx.datetime.LocalDate

/**
 * Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan" ("Tina ut kycklingen"): the reminder
 * of a meal lives in the meal's own note, so it reads naturally on the Tandoor web and needs
 * nothing on the server. One line of the note that starts with [KEYWORDS] ("Påminn:" or the English
 * "Remind:", any case, spaces and a Markdown list mark before it allowed) carries it; the text after
 * the colon is the notification's text:
 *
 * ```
 * Dubbel sats, frys hälften.
 * Påminn: Tina ut kycklingen
 * ```
 *
 * A line without text after the colon is no reminder. With several such lines the first one with
 * text counts.
 */
object MealReminderNote {
    /** The words that start a reminder line (the colon follows); the first is the Swedish one the app writes for Swedish. */
    val KEYWORDS: List<String> = listOf("Påminn", "Remind")

    private val LINE = Regex("""^\s*(?:[-*+]\s+)?(Påminn|Remind)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** The reminder text of [note], or null when it has none. */
    fun reminderOf(note: String): String? = note.lineSequence()
        .mapNotNull { line -> LINE.matchEntire(line)?.groupValues?.get(2)?.trim() }
        .firstOrNull { it.isNotEmpty() }

    /** The word of [note]'s first reminder line as written ("Remind" on the web), or null without one. */
    fun keywordOf(note: String): String? = note.lineSequence().firstNotNullOfOrNull { LINE.matchEntire(it)?.groupValues?.get(1) }

    /** [note] without its reminder lines (what the note field shows next to the reminder field), trimmed. */
    fun withoutReminder(note: String): String = note.lines()
        .filterNot { LINE.matches(it) }
        .joinToString("\n")
        .trim()

    /**
     * [note] with its reminder set to [text]: an existing reminder line keeps its place and its word
     * (written on the web as "Remind:", it stays "Remind:"); without one a line "[keyword]: text"
     * is added at the end. A blank [text] removes the reminder lines. The rest of the note stays as
     * it was (trimmed at the ends).
     */
    fun withReminder(note: String, text: String, keyword: String = KEYWORDS.first()): String {
        val reminder = text.lines().joinToString(" ") { it.trim() }.trim()
        val lines = note.lines()
        val first = lines.indexOfFirst { LINE.matches(it) }
        if (reminder.isEmpty()) return withoutReminder(note)
        if (first < 0) {
            val body = note.trim()
            val line = "${keyword.trim().ifEmpty { KEYWORDS.first() }}: $reminder"
            return if (body.isEmpty()) line else "$body\n$line"
        }
        val word = LINE.matchEntire(lines[first])?.groupValues?.get(1) ?: keyword
        return lines.mapIndexedNotNull { index, line ->
            when {
                index == first -> "$word: $reminder"
                LINE.matches(line) -> null
                else -> line
            }
        }.joinToString("\n").trim()
    }

    /**
     * The note to save from the meal page's two fields (0.7.0 review): [body] is the note field (the
     * [original] note without its reminder lines, as the user edited it) and [text] the reminder
     * field. Nothing the user wrote is lost:
     *
     * - a reminder line typed into the note field by hand stays as typed (it is the user's text);
     *   the reminder field still counts, its line never comes after such a line;
     * - the [original]'s reminder line keeps its place and its word: before the text it stood before
     *   (when that text still starts the note) or after the text it stood after (when that text
     *   still ends it), else at the end; its other reminder lines (hidden by both fields) stay
     *   right after it;
     * - without a reminder line in [original], "[keyword]: text" is added at the end.
     *
     * A blank [text] is the note field as it is (trimmed): the reminder was removed.
     */
    fun merge(original: String?, body: String, text: String, keyword: String = KEYWORDS.first()): String {
        val reminder = text.lines().joinToString(" ") { it.trim() }.trim()
        val bodyText = body.trim()
        if (reminder.isEmpty()) return bodyText
        val bodyLines = if (bodyText.isEmpty()) emptyList() else bodyText.lines()
        val originalLines = original?.lines().orEmpty()
        val own = originalLines.indexOfFirst(::hasReminderText)
        val word = if (own >= 0) LINE.matchEntire(originalLines[own])?.groupValues?.get(1) else null
        val block = buildList {
            add("${(word ?: keyword).trim().ifEmpty { KEYWORDS.first() }}: $reminder")
            if (own >= 0) addAll(originalLines.filterIndexed { index, line -> index != own && LINE.matches(line) })
        }
        var at = if (own < 0) bodyLines.size else placeIn(bodyLines, originalLines, own)
        // The field is the reminder that counts (the first line with text): before any typed one.
        val typed = bodyLines.indexOfFirst(::hasReminderText)
        if (typed in 0 until at) at = typed
        return (bodyLines.take(at) + block + bodyLines.drop(at)).joinToString("\n").trim()
    }

    /** Where the [original]'s reminder line at [own] goes among [body]'s lines (see [merge]). */
    private fun placeIn(body: List<String>, original: List<String>, own: Int): Int {
        val before = textLines(original.take(own))
        val after = textLines(original.drop(own + 1))
        return when {
            before.isEmpty() -> 0
            body.size >= before.size && body.take(before.size) == before -> before.size
            after.isNotEmpty() && body.size >= after.size && body.takeLast(after.size) == after -> body.size - after.size
            else -> body.size
        }
    }

    /** The lines of [lines] that are not reminder lines, trimmed at the ends like the note field. */
    private fun textLines(lines: List<String>): List<String> {
        val text = lines.filterNot { LINE.matches(it) }.joinToString("\n").trim()
        return if (text.isEmpty()) emptyList() else text.lines()
    }

    private fun hasReminderText(line: String): Boolean = LINE.matchEntire(line)?.groupValues?.get(2)?.isNotBlank() == true
}

/**
 * One meal's "kvällen innan" reminder: [text] ("Tina ut kycklingen") the evening before [date].
 *
 * @property key what "the same reminder" means for "once per meal and evening": the day, the meal's
 *   name and the text (a meal created offline changes its id when it is sent, so the id is not part).
 */
data class MealReminder(
    val key: String,
    val mealId: Long,
    val mealTitle: String,
    val mealTypeName: String,
    val text: String,
    val date: LocalDate,
)

/** Fas 5 (planera lane): which meals of the local plan get a reminder the evening before [MealReminders.due]. */
object MealReminders {
    /**
     * The reminders for the meals that START on [date] (a meal over several days is reminded of
     * once, before its first day), by meal type order then name; one per [MealReminder.key].
     */
    fun due(meals: List<MealPlanEntry>, date: LocalDate): List<MealReminder> = meals.asSequence()
        .filter { it.fromDate == date }
        .sortedWith(compareBy<MealPlanEntry> { it.mealType.order }.thenBy { it.mealType.name.lowercase() }.thenBy { it.id })
        .mapNotNull { meal ->
            val text = MealReminderNote.reminderOf(meal.note) ?: return@mapNotNull null
            MealReminder(
                key = keyOf(date, meal.displayTitle, text),
                mealId = meal.id,
                mealTitle = meal.displayTitle.trim(),
                mealTypeName = meal.mealType.name.trim(),
                text = text,
                date = date,
            )
        }
        .distinctBy { it.key }
        .toList()

    fun keyOf(date: LocalDate, title: String, text: String): String = "$date|${title.trim().lowercase()}|${text.trim().lowercase()}"
}
