package casa.moller.tandem.core.domain.timer

import casa.moller.tandem.core.domain.recipe.DurationText
import casa.moller.tandem.core.model.timer.TimerSuggestion
import casa.moller.tandem.core.model.timer.TimerSuggestionSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The timer chips of one cook-mode step (plan 6.2 "Timerchips från `Step.time` och regex på
 * texten", feature #7, plan 6.7 "Språkberoende logik"). Pure Kotlin (B2).
 *
 * **Contract (Phase 1b grund; the timer lane completes the rules, the signature stays):**
 * - [stepText] is the step's instruction exactly as cook mode shows it, i.e. AFTER the templates
 *   were rendered (`StepDisplay.instruction` from `RecipeDisplay.build`), so "{{ scale(10) }} min"
 *   becomes a real number first. The [TimerSuggestion.textRange] indexes refer to this string.
 * - [stepTimeMinutes] is Tandoor's `Step.time` (0 = not set). A positive value gives one
 *   [TimerSuggestionSource.STEP_TIME] suggestion, FIRST in the list, unless the text already has a
 *   duration of exactly that length (then only the text chip, which also marks the words).
 * - [languageTag] is the BCP 47 tag of the app's language ("sv", "en", "sv-SE"). It only breaks
 *   ties: the language of a duration comes from the TEXT, so an English recipe in the Swedish app
 *   still gets its chips. It never decides whether something is a duration.
 * - Text suggestions come in text order, without overlaps, at least 5 s and at most 7 days long.
 *
 * Forms the detector must handle (tested in `TimerDetectorTest` / `StepTimerSuggestionsTest`):
 * - **Swedish:** "10 min", "10 minuter", "1 tim och 15 minuter", "1 h 15 min", "en halvtimme",
 *   "en kvart", "halvannan timme", "en och en halv timme", "ca 20–25 min", "cirka 20 minuter",
 *   "mellan 10 och 12 minuter", "20 till 25 min", "30 sek", "tjugofem minuter", "1,5 timme",
 *   "1 ½ timme"; never "min mamma", "minst", "180 grader", "kl. 18" or "18:30".
 * - **English:** "10 min", "10 minutes", "1 hour 15 minutes", "1 hr", "about 1 1/2 hours",
 *   "an hour and a half", "half an hour", "20 to 25 minutes", "between 10 and 12 minutes",
 *   "30 seconds", "twenty-five minutes", "10-minute", "1.5 hours"; never "a second egg" (too
 *   short) or "3:30".
 * - **No timer** for "över natten"/"overnight", temperatures ("200 grader"), amounts ("2 dl") or
 *   clock times ("kl. 18").
 * - **Ranges** ("ca 20–25 minuter", "mellan 10 och 12 minuter", "20 to 25 minutes") run for the
 *   UPPER bound: [TimerSuggestion.duration] is the longer end, [TimerSuggestion.minDuration] the
 *   shorter one, and the label says which time the timer runs for ("ca 20–25 minuter (25 min)").
 *   "ca/about" sets [TimerSuggestion.isApproximate].
 */
class StepTimerSuggestions(private val detector: TimerDetector = TimerDetector()) {

    /** The chips for one step, STEP_TIME first, then the text matches in text order. */
    fun suggest(stepText: String, stepTimeMinutes: Int, languageTag: String): List<TimerSuggestion> {
        val fromText = detector.detect(stepText, preferredLanguage = languageTag).map { found ->
            TimerSuggestion(
                label = if (found.isRange) "${found.label} (${formatSeconds(found.maxSeconds)})" else found.label,
                duration = found.maxSeconds.seconds,
                minDuration = found.minSeconds.seconds,
                source = TimerSuggestionSource.TEXT,
                textRange = found.start until found.end,
                isApproximate = found.isApproximate,
            )
        }
        val stepTime = stepTimeMinutes.takeIf { it > 0 }?.minutes
        val stepChip = stepTime
            ?.takeIf { time -> fromText.none { it.duration == time && !it.isRange } }
            ?.let { time ->
                TimerSuggestion(
                    label = DurationText.format(stepTimeMinutes) ?: return@let null,
                    duration = time,
                    source = TimerSuggestionSource.STEP_TIME,
                )
            }
        return listOfNotNull(stepChip) + fromText
    }

    /** "25 min", "1 h 30 min", "45 s", "1 min 30 s": the same short units as [DurationText], in both languages. */
    private fun formatSeconds(totalSeconds: Long): String {
        val separator = DurationText.NUMBER_UNIT_SEPARATOR
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return listOfNotNull(
            hours.takeIf { it > 0 }?.let { "$it${separator}h" },
            minutes.takeIf { it > 0 }?.let { "$it${separator}min" },
            seconds.takeIf { it > 0 }?.let { "$it${separator}s" },
        ).joinToString(" ")
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3600L
    }
}
