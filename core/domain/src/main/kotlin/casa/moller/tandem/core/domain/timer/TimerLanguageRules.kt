package casa.moller.tandem.core.domain.timer

/**
 * Timer detection rules for one language, as plain data (plan 6.7: "Fler språk läggs till som
 * data, inte kod"). All entries are matched case-insensitively against whole words. Entries may
 * span several words ("en halv", "half an"); they are split with the same tokenizer as the text,
 * so punctuation inside an entry ("c:a") is matched literally. Every dash variant (- ‐ – — −) is
 * written as "-".
 *
 * @property units unit word -> seconds per unit ("min" -> 60, "kvart" -> 900).
 * @property numberWords quantities written as words ("två" -> 2, "en halv" -> 0.5).
 * @property quantityTails added to the quantity before or after the unit
 *   ("en och en halv timme", "an hour and a half" -> +0.5).
 * @property rangeConnectors between two quantities or durations of a range ("-", "till", "to").
 * @property compoundConnectors between parts of a compound duration ("1 tim och 15 min").
 *   Parts may also follow each other directly ("1 h 15 min"); each part needs a smaller unit.
 * @property betweenPairs "between X and Y" ranges: opening word -> closing word.
 * @property approximations words before a duration that make it approximate ("ca", "about").
 *   A dot right after one is included ("ca.", "approx.").
 * @property hintWords common words of the language, used to guess the language of a text.
 */
data class TimerLanguageRules(
    val languageTag: String,
    val units: Map<String, Long>,
    val numberWords: Map<String, Double> = emptyMap(),
    val quantityTails: Map<String, Double> = emptyMap(),
    val rangeConnectors: Set<String> = setOf("-"),
    val compoundConnectors: Set<String> = emptySet(),
    val betweenPairs: Map<String, String> = emptyMap(),
    val approximations: Set<String> = emptySet(),
    val hintWords: Set<String> = emptySet(),
) {
    companion object {
        private const val SECOND = 1L
        private const val MINUTE = 60L
        private const val HOUR = 3600L

        // Declared before the rule sets below, which use them while the companion is initialized.
        private val SWEDISH_ONES = mapOf(
            "en" to 1.0, "ett" to 1.0, "två" to 2.0, "tre" to 3.0, "fyra" to 4.0, "fem" to 5.0,
            "sex" to 6.0, "sju" to 7.0, "åtta" to 8.0, "nio" to 9.0,
        )
        private val SWEDISH_TENS = mapOf(
            "tjugo" to 20.0,
            "trettio" to 30.0,
            "fyrtio" to 40.0,
            "femtio" to 50.0,
            "sextio" to 60.0,
            "sjuttio" to 70.0,
            "åttio" to 80.0,
            "nittio" to 90.0,
        )
        private val ENGLISH_ONES = mapOf(
            "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
            "seven" to 7.0, "eight" to 8.0, "nine" to 9.0,
        )
        private val ENGLISH_TENS = mapOf(
            "twenty" to 20.0,
            "thirty" to 30.0,
            "forty" to 40.0,
            "fifty" to 50.0,
            "sixty" to 60.0,
            "seventy" to 70.0,
            "eighty" to 80.0,
            "ninety" to 90.0,
        )

        val SWEDISH = TimerLanguageRules(
            languageTag = "sv",
            units = mapOf(
                "sek" to SECOND, "sekund" to SECOND, "sekunder" to SECOND, "sekunders" to SECOND,
                "min" to MINUTE, "minut" to MINUTE, "minuter" to MINUTE, "minuters" to MINUTE,
                "kvart" to 15 * MINUTE, "kvarts" to 15 * MINUTE, "kvartstimme" to 15 * MINUTE,
                "halvtimme" to 30 * MINUTE, "halvtimmes" to 30 * MINUTE,
                "h" to HOUR, "tim" to HOUR, "timme" to HOUR, "timmar" to HOUR, "timmes" to HOUR, "timmars" to HOUR,
            ),
            numberWords = mapOf(
                "tio" to 10.0, "elva" to 11.0, "tolv" to 12.0, "tretton" to 13.0, "fjorton" to 14.0,
                "femton" to 15.0, "sexton" to 16.0, "sjutton" to 17.0, "arton" to 18.0, "nitton" to 19.0,
                "halv" to 0.5, "en halv" to 0.5, "halvannan" to 1.5,
            ) + SWEDISH_ONES + SWEDISH_TENS +
                // tjugoen, tjugoett, tjugotvå ... nittionio: written as one word.
                compoundNumbers(SWEDISH_TENS, SWEDISH_ONES, separators = listOf("")),
            quantityTails = mapOf("och en halv" to 0.5),
            rangeConnectors = setOf("-", "till", "à", "eller"),
            compoundConnectors = setOf("och", ",", "+"),
            betweenPairs = mapOf("mellan" to "och"),
            approximations = setOf("ca", "c:a", "c.a", "cirka", "ungefär", "omkring", "runt", "drygt", "knappt", "~", "≈"),
            hintWords = setOf(
                "och", "i", "att", "med", "på", "det", "som", "till", "av", "för", "är", "tills", "låt", "rör",
                "koka", "stek", "vispa", "tillsätt", "grädda", "ugnen", "grader", "under", "sedan", "efter",
                "ner", "upp", "vatten", "lägg", "blanda", "skär", "minuter", "timme", "timmar", "msk", "tsk",
            ),
        )

        val ENGLISH = TimerLanguageRules(
            languageTag = "en",
            units = mapOf(
                "sec" to SECOND, "secs" to SECOND, "second" to SECOND, "seconds" to SECOND,
                "min" to MINUTE, "mins" to MINUTE, "minute" to MINUTE, "minutes" to MINUTE,
                "h" to HOUR, "hr" to HOUR, "hrs" to HOUR, "hour" to HOUR, "hours" to HOUR,
            ),
            numberWords = mapOf(
                "a" to 1.0, "an" to 1.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0,
                "thirteen" to 13.0, "fourteen" to 14.0, "fifteen" to 15.0, "sixteen" to 16.0,
                "seventeen" to 17.0, "eighteen" to 18.0, "nineteen" to 19.0,
                "half" to 0.5, "half a" to 0.5, "half an" to 0.5, "a half" to 0.5,
            ) + ENGLISH_ONES + ENGLISH_TENS +
                // twenty-one ... ninety-nine, with a hyphen or (often in recipes) a space.
                compoundNumbers(ENGLISH_TENS, ENGLISH_ONES, separators = listOf("-", " ")),
            quantityTails = mapOf("and a half" to 0.5, "-and-a-half" to 0.5),
            rangeConnectors = setOf("-", "to", "or"),
            compoundConnectors = setOf("and", ",", "+"),
            betweenPairs = mapOf("between" to "and"),
            approximations = setOf("about", "approx", "approximately", "around", "roughly", "circa", "ca", "~", "≈"),
            hintWords = setOf(
                "the", "and", "to", "of", "with", "in", "for", "until", "a", "an", "is", "it", "or", "on",
                "then", "into", "over", "about", "add", "stir", "cook", "bake", "boil", "simmer", "heat",
                "oven", "minutes", "hour", "hours", "seconds", "tbsp", "tsp", "cup", "cups",
            ),
        )

        /**
         * Number words made of a tens word and a ones word ("thirty-five", "trettiofem"), for
         * every separator. Generated as data, so the grammar never has to guess whether
         * "thirty-five" is a range from thirty to five.
         */
        fun compoundNumbers(tens: Map<String, Double>, ones: Map<String, Double>, separators: List<String>): Map<String, Double> =
            buildMap {
                for ((tensWord, tensValue) in tens) {
                    for ((onesWord, onesValue) in ones) {
                        for (separator in separators) put("$tensWord$separator$onesWord", tensValue + onesValue)
                    }
                }
            }

        /** Built-in rule sets. Order breaks ties when the language of a text cannot be guessed. */
        val BUILT_IN: List<TimerLanguageRules> = listOf(SWEDISH, ENGLISH)
    }
}
