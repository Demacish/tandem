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
 * **Ranges (timer lane decision, Phase 1b):** "ca 20–25 min" runs for the UPPER bound, 25 min,
 * and the label says so ("ca 20–25 min (25 min)"); [minDuration] keeps the lower bound so the UI
 * can still tell that it was a range ([isRange]).
 *
 * @property label the chip text: for [TimerSuggestionSource.TEXT] the matched text as written
 *   ("10 min"), followed by the duration the timer runs for when the text is a range
 *   ("ca 20–25 min (25 min)"); for [TimerSuggestionSource.STEP_TIME] the formatted time
 *   ("1 h 15 min"). The UI may prefix the step name when it starts the timer.
 * @property duration what the timer runs for; for a range the LONGER end.
 * @property source see [TimerSuggestionSource].
 * @property textRange [TimerSuggestionSource.TEXT] only: the characters of the step text the
 *   suggestion was found in (`text.substring(textRange)` is the match), so cook mode can make
 *   them tappable. The indexes refer to the exact string that was passed to the detector.
 * @property isApproximate the text said "ca", "about", ...
 * @property minDuration the shorter end of a range ("20–25 min" → 20 min); equal to [duration] otherwise.
 */
data class TimerSuggestion(
    val label: String,
    val duration: Duration,
    val source: TimerSuggestionSource,
    val textRange: IntRange? = null,
    val isApproximate: Boolean = false,
    val minDuration: Duration = duration,
) {
    init {
        require(duration.isPositive()) { "a suggestion needs a positive duration" }
        require(minDuration.isPositive() && minDuration <= duration) { "minDuration is the shorter end" }
        require((source == TimerSuggestionSource.TEXT) == (textRange != null)) { "only TEXT suggestions have a textRange" }
    }

    /** The longer end of a range, i.e. [duration] (kept from the grund contract; ranges run for the longer end). */
    val maxDuration: Duration
        get() = duration

    /** "20–25 min": the text gives a range. */
    val isRange: Boolean
        get() = minDuration != duration
}
