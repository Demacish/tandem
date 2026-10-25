package casa.moller.tandem.core.domain.shopping

import casa.moller.tandem.core.domain.shopping.ShoppingWindowRule.Verdict
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShoppingWindowRuleTest {
    private val now = Instant.parse("2026-10-25T14:30:00Z")

    private fun verdict(checked: Boolean, completedAt: Instant?, recentDays: Int = 7, onServer: Boolean = true, unsent: Boolean = false) =
        ShoppingWindowRule.missingEntry(onServer, unsent, checked, completedAt, now, recentDays)

    @Test
    fun theWindowStartsAtMidnightUtcAndIsAtMost14Days() {
        assertEquals(Instant.parse("2026-10-18T00:00:00Z"), ShoppingWindowRule.windowStart(now, 7))
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), ShoppingWindowRule.windowStart(now, 20))
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), ShoppingWindowRule.windowStart(now, 0))
    }

    /** FACTS "Fas 2": entries completed 30, 10 and 3 days ago; listed with 7 days: only 3, with 20 (= 14): 10 and 3. */
    @Test
    fun matchesWhatTandoorListed() {
        assertEquals(Verdict.AGED_OUT, verdict(true, now - 30.days))
        assertEquals(Verdict.AGED_OUT, verdict(true, now - 10.days))
        assertEquals(Verdict.DELETED, verdict(true, now - 3.days))
        assertEquals(Verdict.DELETED, verdict(true, now - 10.days, recentDays = 20))
        assertEquals(Verdict.AGED_OUT, verdict(true, now - 30.days, recentDays = 20))
    }

    @Test
    fun theFirstDayOfTheWindowIsAMarginThatArchives() {
        val start = ShoppingWindowRule.windowStart(now, 7)
        assertEquals(Verdict.AGED_OUT, verdict(true, start + 2.hours))
        assertEquals(Verdict.DELETED, verdict(true, start + 1.days))
    }

    @Test
    fun uncheckedOrUnknownCompletionMeansDeleted() {
        assertEquals(Verdict.DELETED, verdict(false, null))
        assertEquals(Verdict.DELETED, verdict(true, null))
    }

    @Test
    fun localOrChangedEntriesAreKept() {
        assertEquals(Verdict.KEEP, verdict(false, null, onServer = false))
        assertEquals(Verdict.KEEP, verdict(true, now - 1.days, unsent = true))
    }

    @Test
    fun deletionsNeedACompleteResponseBelowTheLimit() {
        assertTrue(ShoppingWindowRule.canDetectDeletions(999, complete = true))
        assertFalse(ShoppingWindowRule.canDetectDeletions(1000, complete = true))
        assertFalse(ShoppingWindowRule.canDetectDeletions(10, complete = false))
    }
}
