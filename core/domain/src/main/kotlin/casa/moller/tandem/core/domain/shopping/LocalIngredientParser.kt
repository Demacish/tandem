package casa.moller.tandem.core.domain.shopping

import casa.moller.tandem.core.model.shopping.ParsedIngredient

/**
 * The offline fallback of the ingredient parser (feature #11, plan 6.2 "Lägg till manuellt"):
 * "2 dl grädde" → 2 dl grädde, "mjölk" → 0, no unit, "ca 200 g smör, rumsvarmt" → 200 g "ca smör"
 * with the note "rumsvarmt" (the same split as Tandoor's parser, FACTS "Fas 2 e"). Pure: no
 * network, no database; the unit list is the built-in one plus the server's units.
 *
 * Rules: the text after the first comma is the note; the first number (`2`, `1,5`, `1.5`, `1/2`,
 * `½`, `1½`, `1 1/2`, a range `2-3` takes its first number) is the amount wherever it stands, a
 * known unit right after it is the unit, and the rest is the food.
 */
object LocalIngredientParser {
    /** Common Swedish and English units (case-insensitive), used together with the server's units. */
    val BUILT_IN_UNITS: Set<String> by lazy { UNIT_NAMES.split(' ').toSet() }

    private const val UNIT_NAMES: String =
        "g hg kg mg ml cl dl l krm tsk msk st pkt paket förp burk burkar flaska flaskor påse påsar klyfta klyftor " +
            "knippe knippen skiva skivor nypa kruka tsp tbsp cup cups oz lb lbs pcs pc pack packs can cans bag bags bunch " +
            "clove cloves slice slices pinch bottle bottles jar jars"

    private val UNICODE_FRACTIONS = mapOf(
        '½' to 0.5,
        '⅓' to 1.0 / 3,
        '⅔' to 2.0 / 3,
        '¼' to 0.25,
        '¾' to 0.75,
        '⅕' to 0.2,
        '⅛' to 0.125,
    )
    private val NUMBER = Regex("^(\\d+(?:[.,]\\d+)?)?([½⅓⅔¼¾⅕⅛])?$")
    private val FRACTION = Regex("^(\\d+)/(\\d+)$")
    private val NOTE_COMMA = Regex("(?<!\\d),|,(?!\\d)")
    private val RANGE = Regex("^([^-–]+)[-–].+$")

    /**
     * Parses one line; null for blank text or a line that is only a number and a unit.
     *
     * @param knownUnits the server's unit names (`unit` table), recognised as well as [BUILT_IN_UNITS].
     */
    fun parse(line: String, knownUnits: Collection<String> = emptyList()): ParsedIngredient? {
        val original = line.trim()
        if (original.isEmpty()) return null
        // A comma between digits is a decimal comma ("1,5 kg"); any other comma starts the note.
        val noteAt = NOTE_COMMA.find(original)?.range?.first
        val main = (if (noteAt == null) original else original.substring(0, noteAt)).trim()
        val note = noteAt?.let { original.substring(it + 1).trim() }.orEmpty()
        val tokens = main.split(WHITESPACE).filter { it.isNotEmpty() }.toMutableList()
        val units = (BUILT_IN_UNITS + knownUnits).mapTo(HashSet()) { it.lowercase() }

        var amount = 0.0
        var unitName: String? = null
        val numberAt = tokens.indexOfFirst { numberOf(it) != null }
        if (numberAt >= 0) {
            amount = numberOf(tokens[numberAt]) ?: 0.0
            tokens.removeAt(numberAt)
            // "1 1/2": a fraction right after a whole number adds to it.
            val fraction = tokens.getOrNull(numberAt)?.let { fractionOf(it) }
            if (fraction != null && amount == Math.floor(amount)) {
                amount += fraction
                tokens.removeAt(numberAt)
            }
            val unit = tokens.getOrNull(numberAt)
            if (unit != null && unit.lowercase().trimEnd('.') in units) {
                unitName = unit.trimEnd('.')
                tokens.removeAt(numberAt)
            }
        }
        val food = tokens.joinToString(" ").trim()
        if (food.isEmpty()) return null
        return ParsedIngredient(
            originalText = original,
            amount = amount,
            unitName = unitName,
            foodName = food,
            note = note,
            fromServer = false,
        )
    }

    /** The amount a token stands for (`2`, `1,5`, `½`, `1½`, `3/4`, `2-3` → 2), or null when it is no number. */
    internal fun numberOf(token: String): Double? {
        val text = RANGE.find(token)?.groupValues?.get(1) ?: token
        fractionOf(text)?.let { return it }
        val match = NUMBER.find(text) ?: return null
        val whole = match.groupValues[1].takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()
        val fraction = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { UNICODE_FRACTIONS[it.first()] }
        if (whole == null && fraction == null) return null
        return (whole ?: 0.0) + (fraction ?: 0.0)
    }

    private fun fractionOf(token: String): Double? {
        val match = FRACTION.find(token) ?: return UNICODE_FRACTIONS[token.singleOrNull() ?: return null]
        val denominator = match.groupValues[2].toDouble()
        if (denominator == 0.0) return null
        return match.groupValues[1].toDouble() / denominator
    }

    private val WHITESPACE = Regex("\\s+")
}
