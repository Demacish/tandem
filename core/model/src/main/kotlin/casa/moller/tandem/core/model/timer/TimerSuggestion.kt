package casa.moller.tandem.core.model.timer

import kotlin.time.Duration

/** Where a [TimerSuggestion] comes from. */
enum class TimerSuggestionSource {
    /** The step's own time field (Tandoor `Step.time`, minutes). */
    STEP_TIME,

    /** A duration found in the step text ("koka 10 min", "about 1 1/2 hours"). */
    TEXT,
}

/**
 * A timer cook mode offers as a chip on a step (plan 6.2 "Timerchips från `Step.time` och regex
 * på texten"). Produced by core:domain's `StepTimerSuggestions`; tapping the chip starts a
 * [CookTimer] with [label] and [duration].
 *
 * @property label the chip text: for [TimerSuggestionSource.TEXT] the matched text as written
 *   ("ca 20–25 min"); for [TimerSuggestionSource.STEP_TIME] the formatted time ("1 h 15 min").
 *   The UI may prefix the step name when it starts the timer.
 * @property duration what the timer runs for; for a range ("20–25 min") the SHORTER end, so the
 *   cook checks in time.
 * @property maxDuration the longer end of a range; equal to [duration] otherwise.
 * @property source see [TimerSuggestionSource].
 * @property textRange [TimerSuggestionSource.TEXT] only: the characters of the step text the
 *   suggestion was found in (`text.substring(textRange)` is the match), so cook mode can make
 *   them tappable. The indexes refer to the exact string that was passed to the detector.
 * @property isApproximate the text said "ca", "about", ...
 */
data class TimerSuggestion(
    val label: String,
    val duration: Duration,
    val maxDuration: Duration = duration,
    val source: TimerSuggestionSource,
    val textRange: IntRange? = null,
    val isApproximate: Boolean = false,
) {
    init {
        require(duration.isPositive()) { "a suggestion needs a positive duration" }
        require(maxDuration >= duration) { "maxDuration is the longer end" }
        require((source == TimerSuggestionSource.TEXT) == (textRange != null)) { "only TEXT suggestions have a textRange" }
    }

    /** "20–25 min": the text gives a range. */
    val isRange: Boolean
        get() = maxDuration != duration
}
