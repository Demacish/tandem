package casa.moller.tandem.core.domain.timer

/**
 * A duration found in recipe step text.
 *
 * @property start index of the first character in the source text.
 * @property end index after the last character (exclusive), so `text.substring(start, end) == label`.
 * @property minSeconds lower bound; equal to [maxSeconds] unless the text gives a range ("20–25 min").
 * @property label the matched text as written, including any "ca"/"about" prefix.
 * @property isApproximate the text said "ca", "cirka", "about", ...
 * @property languageTag BCP 47 tag of the rule set that found the match (the recipe's language,
 *   not the app's).
 */
data class DetectedTimer(
    val start: Int,
    val end: Int,
    val minSeconds: Long,
    val maxSeconds: Long,
    val label: String,
    val isApproximate: Boolean,
    val languageTag: String,
) {
    val durationSeconds: LongRange get() = minSeconds..maxSeconds
    val isRange: Boolean get() = minSeconds != maxSeconds
}
