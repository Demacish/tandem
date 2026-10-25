package casa.moller.tandem.core.database.dao

import casa.moller.tandem.core.database.entity.CookLogEntity

/**
 * What makes two cook log entries the same "Klart!" (fix-data, 0.2.0 review): the same recipe,
 * the same author and the same `created_at` to the second (the app sends whole seconds). The one
 * rule for the outbox sender, which looks for its entry on the server before it posts again after
 * a lost answer, and for [CookLogDao.reconcile], which settles such an entry when a list has it.
 *
 * @property createdBy the author's Tandoor user id; null never matches an authored entry.
 * @property cookedAtEpochMillis `created_at`; an entry without it never matches.
 */
data class CookLogKey(val recipeId: Long, val createdBy: Long?, val cookedAtEpochMillis: Long?) {
    fun matches(other: CookLogKey): Boolean {
        val at = cookedAtEpochMillis ?: return false
        val otherAt = other.cookedAtEpochMillis ?: return false
        return recipeId == other.recipeId && createdBy == other.createdBy &&
            Math.floorDiv(at, MILLIS_PER_SECOND) == Math.floorDiv(otherAt, MILLIS_PER_SECOND)
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        fun of(entry: CookLogEntity): CookLogKey = CookLogKey(entry.recipeId, entry.createdBy, entry.cookedAtEpochMillis)
    }
}
