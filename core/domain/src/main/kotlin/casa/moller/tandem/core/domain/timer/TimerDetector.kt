package casa.moller.tandem.core.domain.timer

import kotlin.math.roundToLong

/**
 * Finds durations in recipe step text ("10 min", "1 tim och 15 minuter", "ca 20–25 min",
 * "about 1 1/2 hours") so they can be shown as timer chips (plan 6.7 "Språkberoende logik").
 *
 * The language comes from the text, not from the app: every rule set runs, and when matches
 * overlap the longest wins, then the language that [guessLanguage] ranks highest. A duration
 * always needs a quantity in front of a unit, so "min mamma", "minst", "180 grader" or
 * "kl. 18" never match. Durations shorter than [minSeconds] ("a second egg") or longer than
 * [maxSeconds] are dropped, and a number that is part of a clock time ("3:30") is never read
 * as a duration.
 */
class TimerDetector(
    rules: List<TimerLanguageRules> = TimerLanguageRules.BUILT_IN,
    private val minSeconds: Long = DEFAULT_MIN_SECONDS,
    private val maxSeconds: Long = DEFAULT_MAX_SECONDS,
) {
    private val matchers = rules.map(::RuleMatcher)

    /**
     * Every duration in [text], in text order, without overlaps.
     *
     * @param preferredLanguage the app's language ("sv", "en"); only breaks a tie when the text's
     *   own words do not decide the language. It never decides whether something is a duration.
     */
    fun detect(text: String, preferredLanguage: String? = null): List<DetectedTimer> {
        if (text.isBlank()) return emptyList()
        val tokens = TimerTokenizer.tokenize(text)
        val ranking = rankLanguages(tokens, preferredLanguage)
        val candidates = matchers.flatMap { matcher -> matcher.findAll(tokens).map { matcher to it } }
            .mapNotNull { (matcher, match) -> toTimer(text, tokens, matcher, match) }
            .sortedWith(
                compareByDescending<DetectedTimer> { it.end - it.start }
                    .thenBy { ranking.indexOf(it.languageTag) }
                    .thenBy { it.start },
            )
        val chosen = ArrayList<DetectedTimer>()
        for (candidate in candidates) {
            if (chosen.none { it.start < candidate.end && candidate.start < it.end }) chosen += candidate
        }
        return chosen.sortedBy { it.start }
    }

    /** The language tag whose hint words occur most often in [text], or null if none occur (or there is a tie). */
    fun guessLanguage(text: String): String? {
        val scores = scores(TimerTokenizer.tokenize(text))
        val best = scores.maxOfOrNull { it.second } ?: return null
        if (best == 0) return null
        return scores.filter { it.second == best }.singleOrNull()?.first
    }

    private fun scores(tokens: List<Token>): List<Pair<String, Int>> = matchers.map { matcher ->
        matcher.rules.languageTag to tokens.count { it.kind == TokenKind.WORD && it.text in matcher.rules.hintWords }
    }

    /** Language tags, best guess first; ties go to [preferred], then keep the configured order. */
    private fun rankLanguages(tokens: List<Token>, preferred: String?): List<String> {
        val preferredBase = preferred?.substringBefore('-')?.substringBefore('_')?.lowercase()
        return scores(tokens).withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }
                    .thenByDescending { it.value.first == preferredBase }
                    .thenBy { it.index },
            )
            .map { it.value.first }
    }

    private fun toTimer(text: String, tokens: List<Token>, matcher: RuleMatcher, match: RuleMatcher.Match): DetectedTimer? {
        if (match.minSeconds.isNaN() || match.maxSeconds.isNaN()) return null
        var low = match.minSeconds.roundToLong()
        var high = match.maxSeconds.roundToLong()
        if (low > high) low = high.also { high = low }
        if (low <= 0L || high < minSeconds || high > maxSeconds) return null
        val start = tokens[match.firstToken].start
        val end = tokens[match.endToken - 1].end
        return DetectedTimer(
            start = start,
            end = end,
            minSeconds = low,
            maxSeconds = high,
            label = text.substring(start, end),
            isApproximate = match.approximate,
            languageTag = matcher.rules.languageTag,
        )
    }

    companion object {
        const val DEFAULT_MIN_SECONDS = 5L
        const val DEFAULT_MAX_SECONDS = 7L * 24 * 3600
    }
}

/** Grammar for one [TimerLanguageRules] over a token list. Token indexes are half-open. */
internal class RuleMatcher(val rules: TimerLanguageRules) {

    class Match(val firstToken: Int, val endToken: Int, val minSeconds: Double, val maxSeconds: Double, val approximate: Boolean)

    private class Phrase<V>(val words: List<String>, val value: V)

    private class Span(val min: Double, val max: Double, val end: Int)

    private class Quantity(val value: Double, val end: Int)

    private class UnitMatch(val seconds: Long, val end: Int)

    private val units = rules.units.mapKeys { it.key.lowercase() }
    private val numericUnits = rules.numericUnits.mapKeys { it.key.lowercase() }
    private val genitiveUnits = rules.genitiveUnits.mapTo(HashSet()) { it.lowercase() }
    private val measureWords = rules.measureWords.mapTo(HashSet()) { it.lowercase() }
    private val numberWords = phrases(rules.numberWords)
    private val quantityTails = phrases(rules.quantityTails)
    private val rangeConnectors = phrases(rules.rangeConnectors.associateWith { true })
    private val compoundConnectors = phrases(rules.compoundConnectors.associateWith { true })
    private val approximations = phrases(rules.approximations.associateWith { true })
    private val betweenPairs = phrases(rules.betweenPairs.mapValues { phrases(mapOf(it.value to true)) })

    fun findAll(tokens: List<Token>): List<Match> {
        val matches = ArrayList<Match>()
        var i = 0
        while (i < tokens.size) {
            val match = if (isClockTime(tokens, i)) null else matchAt(tokens, i)
            if (match != null) {
                matches += match
                i = match.endToken
            } else {
                i++
            }
        }
        return matches
    }

    private fun matchAt(tokens: List<Token>, first: Int): Match? {
        var i = first
        var approximate = false
        longest(tokens, i, approximations)?.let { (end, _) ->
            approximate = true
            i = if (tokens.getOrNull(end)?.text == "." && tokens[end].start == tokens[end - 1].end) end + 1 else end
        }
        val span = between(tokens, i) ?: rangeOrDuration(tokens, i) ?: return null
        return Match(first, span.end, span.min, span.max, approximate)
    }

    /** "mellan 10 och 12 minuter", "between 1 hour and 1 hour 30 minutes". */
    private fun between(tokens: List<Token>, i: Int): Span? {
        val (afterOpen, closers) = longest(tokens, i, betweenPairs) ?: return null
        duration(tokens, afterOpen)?.let { first ->
            val (afterClose, _) = longest(tokens, first.end, closers) ?: return null
            val second = duration(tokens, afterClose) ?: return null
            return Span(first.min, second.max, second.end)
        }
        val low = quantity(tokens, afterOpen) ?: return null
        val (afterClose, _) = longest(tokens, low.end, closers) ?: return null
        val high = quantity(tokens, afterClose) ?: return null
        val highUnit = unit(tokens, high.end) ?: return null
        return Span(low.value * highUnit.seconds, high.value * highUnit.seconds, highUnit.end)
    }

    /** "20–25 min", "45 min–1 tim", or a single (compound) duration. */
    private fun rangeOrDuration(tokens: List<Token>, i: Int): Span? {
        val low = quantity(tokens, i) ?: return null
        if (unit(tokens, low.end) == null) {
            val (afterConnector, _) = longest(tokens, low.end, rangeConnectors) ?: return null
            // "thirty-five": a hyphen glued between two number WORDS joins one number; it is never
            // the range "30 to 5". (Unknown compounds give no timer rather than a wrong one.)
            if (isGluedHyphenBetweenWords(tokens, low.end, afterConnector)) return null
            val high = quantity(tokens, afterConnector) ?: return null
            val highUnit = unit(tokens, high.end) ?: return null
            return Span(low.value * highUnit.seconds, high.value * highUnit.seconds, highUnit.end)
        }
        val first = duration(tokens, i) ?: return null
        val (afterConnector, _) = longest(tokens, first.end, rangeConnectors) ?: return first
        val second = duration(tokens, afterConnector) ?: return first
        return Span(first.min, second.max, second.end)
    }

    /** "10 min", "1 tim och 15 minuter", "1 h 2 min 30 sek", "an hour and a half". */
    private fun duration(tokens: List<Token>, i: Int): Span? {
        val firstQuantity = quantity(tokens, i) ?: return null
        val firstUnit = unit(tokens, firstQuantity.end) ?: return null
        var total = firstQuantity.value * firstUnit.seconds
        var lastUnit = firstUnit.seconds
        var end = firstUnit.end
        while (true) {
            val partStart = longest(tokens, end, compoundConnectors)?.first ?: end
            val partQuantity = quantity(tokens, partStart)
            val partUnit = partQuantity?.let { unit(tokens, it.end) }
            if (partQuantity != null && partUnit != null && partUnit.seconds < lastUnit) {
                total += partQuantity.value * partUnit.seconds
                lastUnit = partUnit.seconds
                end = partUnit.end
                continue
            }
            // "1h30": minutes glued to an hour given as "h".
            if (partQuantity != null && partUnit == null && isGluedMinutesAfterHours(tokens, end, lastUnit)) {
                total += partQuantity.value * MINUTE_SECONDS
                end = partQuantity.end
                break
            }
            longest(tokens, end, quantityTails)?.let { (afterTail, extra) ->
                total += extra * lastUnit
                end = afterTail
            }
            break
        }
        return Span(total, total, end)
    }

    /** "10", "1,5", "½", "1 ½", "1½", "1 1/2", "1-1/2", "två", "en halv", "en och en halv". */
    private fun quantity(tokens: List<Token>, i: Int): Quantity? {
        val token = tokens.getOrNull(i) ?: return null
        var value: Double
        var end: Int
        if (token.kind == TokenKind.NUMBER) {
            value = token.value
            end = i + 1
            if (token.form == NumberForm.INTEGER) {
                val next = tokens.getOrNull(end)
                val afterNext = tokens.getOrNull(end + 1)
                if (next != null && next.isProperFraction) {
                    value += next.value
                    end += 1
                } else if (
                    next != null && next.text == "-" && next.start == token.end &&
                    afterNext != null && afterNext.form == NumberForm.SLASH_FRACTION && afterNext.isProperFraction &&
                    afterNext.start == next.end
                ) {
                    value += afterNext.value
                    end += 2
                }
            }
        } else {
            val (afterWords, wordValue) = longest(tokens, i, numberWords) ?: return null
            value = wordValue
            end = afterWords
        }
        longest(tokens, end, quantityTails)?.let { (afterTail, extra) ->
            value += extra
            end = afterTail
        }
        return Quantity(value, end)
    }

    /**
     * A unit word at [i], or a unit glued on with a hyphen ("10-minute", "half-hour"). A unit of
     * [numericUnits] needs a number in digits right before it ("30 s"); a genitive unit followed
     * by a measure word is no duration ("en kvarts liter").
     */
    private fun unit(tokens: List<Token>, i: Int): UnitMatch? {
        val token = tokens.getOrNull(i) ?: return null
        if (token.kind == TokenKind.WORD) {
            val seconds = units[token.text]
                ?: numericUnits[token.text]?.takeIf { isPlainNumber(tokens.getOrNull(i - 1)) }
                ?: return null
            if (token.text in genitiveUnits && isMeasureWord(tokens.getOrNull(i + 1))) return null
            return UnitMatch(seconds, i + 1)
        }
        val previous = tokens.getOrNull(i - 1)
        val next = tokens.getOrNull(i + 1)
        if (token.text == "-" && previous != null && next != null && next.kind == TokenKind.WORD &&
            previous.end == token.start && token.end == next.start
        ) {
            return units[next.text]?.let { UnitMatch(it, i + 2) }
        }
        return null
    }

    /** Digits below 1000 ("30 s", not the "1970s"). */
    private fun isPlainNumber(token: Token?): Boolean = token != null && token.kind == TokenKind.NUMBER && token.value < MAX_PLAIN_NUMBER

    private fun isMeasureWord(token: Token?): Boolean = token != null && token.kind == TokenKind.WORD && token.text in measureWords

    /** True for the "30" of "1h30": an integer below 60 glued to an "h" that ends a duration in hours. */
    private fun isGluedMinutesAfterHours(tokens: List<Token>, i: Int, lastUnit: Long): Boolean {
        val unitToken = tokens.getOrNull(i - 1) ?: return false
        val minutes = tokens.getOrNull(i) ?: return false
        return lastUnit == HOUR_SECONDS && unitToken.text == "h" && minutes.form == NumberForm.INTEGER &&
            minutes.value < MINUTES_PER_HOUR && minutes.start == unitToken.end
    }

    /** True when [connectorStart] is a lone "-" touching a word on both sides ("thirty-five"). */
    private fun isGluedHyphenBetweenWords(tokens: List<Token>, connectorStart: Int, afterConnector: Int): Boolean {
        val dash = tokens.getOrNull(connectorStart) ?: return false
        val before = tokens.getOrNull(connectorStart - 1) ?: return false
        val after = tokens.getOrNull(afterConnector) ?: return false
        return dash.text == "-" && afterConnector == connectorStart + 1 &&
            before.kind == TokenKind.WORD && after.kind == TokenKind.WORD &&
            before.end == dash.start && dash.end == after.start
    }

    /** True for the minutes of "18:30" / "3:30", which are never a duration of their own. */
    private fun isClockTime(tokens: List<Token>, i: Int): Boolean {
        val token = tokens[i]
        val colon = tokens.getOrNull(i - 1) ?: return false
        val hours = tokens.getOrNull(i - 2) ?: return false
        return token.kind == TokenKind.NUMBER && colon.text == ":" && hours.kind == TokenKind.NUMBER &&
            colon.start == hours.end && token.start == colon.end
    }

    /** Longest phrase starting at token [i]; returns the index after it and its value. */
    private fun <V> longest(tokens: List<Token>, i: Int, table: List<Phrase<V>>): Pair<Int, V>? {
        for (phrase in table) {
            if (i + phrase.words.size > tokens.size) continue
            val matches = phrase.words.indices.all { k ->
                val token = tokens[i + k]
                token.kind != TokenKind.NUMBER && token.text == phrase.words[k]
            }
            if (matches) return (i + phrase.words.size) to phrase.value
        }
        return null
    }

    private fun <V> phrases(entries: Map<String, V>): List<Phrase<V>> =
        entries.map { (text, value) -> Phrase(TimerTokenizer.tokenize(text).map { it.text }, value) }
            .filter { it.words.isNotEmpty() }
            .sortedByDescending { it.words.size }

    private companion object {
        const val MINUTE_SECONDS = 60L
        const val HOUR_SECONDS = 3600L
        const val MINUTES_PER_HOUR = 60.0
        const val MAX_PLAIN_NUMBER = 1000.0
    }
}
