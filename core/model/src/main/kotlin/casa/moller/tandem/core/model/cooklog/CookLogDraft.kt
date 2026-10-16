package casa.moller.tandem.core.model.cooklog

import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * "Klart!" (feature #8): what the user logs after cooking, before it is queued as
 * `POST /api/cook-log/` `{recipe, servings, rating, comment, created_at}` (FACTS.md "Fas 1 f";
 * `created_at` is writable, so an entry sent later keeps the time it was cooked).
 *
 * @property recipeId the Tandoor recipe id.
 * @property servings whole servings as Tandoor stores them (`integer`, nullable), see [servingsOf].
 * @property rating 0–5 stars, or null for "no rating" (the field is left out).
 * @property comment free text; null or blank is left out.
 * @property cookedAt when it was cooked (normally "now" when "Klart!" was pressed).
 */
data class CookLogDraft(val recipeId: Long, val servings: Int?, val rating: Int?, val comment: String?, val cookedAt: Instant) {
    init {
        require(rating == null || rating in RATING_RANGE) { "rating is 0..5 or null" }
        require(servings == null || servings > 0) { "servings are positive" }
    }

    /** [comment] trimmed, or null when there is nothing to send. */
    val commentOrNull: String?
        get() = comment?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Stars the UI offers. */
        val RATING_RANGE: IntRange = 0..5

        /**
         * Tandoor stores whole servings: the servings cook mode used (a decimal, e.g. 2.5 after
         * halving 5) rounded half up, at least 1; null when unknown or not positive.
         */
        fun servingsOf(servings: Double?): Int? =
            servings?.takeIf { it.isFinite() && it > 0.0 }?.let { (it + HALF_EPSILON).roundToInt().coerceAtLeast(1) }

        // roundToInt already rounds .5 up; the epsilon keeps 2.4999999 (from scaling) at 2.5.
        private const val HALF_EPSILON = 1e-9
    }
}
