package casa.moller.tandem.core.domain.voice

import java.text.Normalizer
import java.util.Locale

/** What a spoken command asks cook mode to do (plan §8.1 "Handsfree", #32). */
enum class VoiceCommand {
    /** "nästa", "next": the next step. */
    NEXT,

    /** "tillbaka", "back": the previous step. */
    PREVIOUS,

    /** "upprepa", "repeat": read the current step aloud again. */
    REPEAT,
}

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32): turns what the on-device recognizer heard into a
 * [VoiceCommand]. Pure, so the Swedish and English variants are unit tested without a microphone.
 *
 * Rules, chosen so that talk in the kitchen does not turn the page:
 * - The WHOLE utterance must be a command phrase ("nästa", "gå tillbaka", "säg igen"), after
 *   [normalize] and with polite fillers removed ("okej", "snälla", "tack", "Tandem", "please").
 *   "Vi tar nästa vecka" or "nästan klart" is no command.
 * - The same phrase said twice ("nästa nästa") counts once.
 * - Both languages are always understood (a Swedish recipe in an English UI, or the other way round):
 *   the phrase tables below are data per language, like the timer rules.
 * - Diacritics are optional ("nasta" from a recognizer without å/ä/ö matches "nästa").
 * - The recognizer's alternatives are tried in its order; the first one that is a command wins.
 */
object VoiceCommands {

    /** The command [alternatives] (best first) mean, or null for anything else. */
    fun parse(alternatives: List<String>): VoiceCommand? = alternatives.firstNotNullOfOrNull { parse(it) }

    /** The command [utterance] means, or null. */
    fun parse(utterance: String): VoiceCommand? {
        val words = normalize(utterance).split(' ').filter { it.isNotEmpty() && it !in FILLERS }
        if (words.isEmpty() || words.size > MAX_WORDS) return null
        PHRASES[words.joinToString(" ")]?.let { return it }
        // "nästa nästa", "next next": the same phrase repeated.
        for (size in 1..words.size / 2) {
            if (words.size % size != 0) continue
            val first = words.subList(0, size)
            if (words.chunked(size).all { it == first }) PHRASES[first.joinToString(" ")]?.let { return it }
        }
        return null
    }

    /**
     * Lower case, without diacritics, punctuation or extra spaces: "Nästa steg!" → "nasta steg".
     * Hyphens and apostrophes are spaces ("one-more-time").
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val plain = buildString(decomposed.length) {
            for (char in decomposed) {
                when {
                    Character.getType(char) == Character.NON_SPACING_MARK.toInt() -> Unit
                    char.isLetterOrDigit() -> append(char)
                    else -> append(' ')
                }
            }
        }
        return plain.trim().replace(SPACES, " ")
    }

    /** Longer utterances are talk, not commands. */
    private const val MAX_WORDS = 6

    private val SPACES = Regex(" +")

    /** Swedish phrases (plan §6.7 "Röst: kommandon per språk"). */
    private val SWEDISH: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.NEXT to listOf(
            "nästa", "nästa steg", "nästa sida", "fortsätt", "framåt", "gå framåt", "vidare", "gå vidare", "gå till nästa",
        ),
        VoiceCommand.PREVIOUS to listOf(
            "tillbaka", "gå tillbaka", "bakåt", "gå bakåt", "backa", "föregående", "föregående steg", "förra", "förra steget",
            "steget innan", "ett steg tillbaka",
        ),
        VoiceCommand.REPEAT to listOf(
            "upprepa", "repetera", "igen", "säg igen", "säg det igen", "läs", "läs upp", "läs igen", "läs upp igen", "läs steget",
            "en gång till", "vad sa du", "vad står det",
        ),
    )

    /** English phrases. */
    private val ENGLISH: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.NEXT to listOf("next", "next step", "next page", "forward", "go forward", "continue", "go on"),
        VoiceCommand.PREVIOUS to listOf(
            "back", "go back", "previous", "previous step", "last step", "step back", "one step back", "backwards", "go backwards",
        ),
        VoiceCommand.REPEAT to listOf(
            "repeat", "repeat that", "again", "say again", "say it again", "say that again", "read", "read it", "read again",
            "read it again", "read the step", "one more time", "what was that", "pardon",
        ),
    )

    /** Words that may come around a command without changing it ("okej nästa", "next please"). */
    private val FILLERS: Set<String> = setOf(
        "okej", "ok", "okay", "tack", "snalla", "hej", "tandem", "och", "eh", "ehm", "please", "hey", "hi", "um", "uh", "now", "nu",
    ).map(::normalize).toSet()

    private val PHRASES: Map<String, VoiceCommand> = buildMap {
        for (table in listOf(SWEDISH, ENGLISH)) {
            for ((command, phrases) in table) phrases.forEach { phrase -> put(normalize(phrase), command) }
        }
    }
}
