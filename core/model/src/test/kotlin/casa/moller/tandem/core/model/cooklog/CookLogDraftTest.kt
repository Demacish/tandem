package casa.moller.tandem.core.model.cooklog

import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CookLogDraftTest {
    private val now = Instant.parse("2026-10-16T18:00:00Z")

    @Test
    fun servingsAreWholeNumbersRoundedHalfUp() {
        assertEquals(4, CookLogDraft.servingsOf(4.0))
        assertEquals(3, CookLogDraft.servingsOf(2.5))
        assertEquals(3, CookLogDraft.servingsOf(2.4999999999))
        assertEquals(2, CookLogDraft.servingsOf(2.2))
        assertEquals(1, CookLogDraft.servingsOf(0.25))
        assertNull(CookLogDraft.servingsOf(0.0))
        assertNull(CookLogDraft.servingsOf(Double.NaN))
        assertNull(CookLogDraft.servingsOf(null))
    }

    @Test
    fun ratingIsZeroToFiveOrNone() {
        CookLogDraft(recipeId = 1, servings = 4, rating = 0, comment = null, cookedAt = now)
        CookLogDraft(recipeId = 1, servings = 4, rating = 5, comment = null, cookedAt = now)
        CookLogDraft(recipeId = 1, servings = null, rating = null, comment = null, cookedAt = now)
        assertThrows<IllegalArgumentException> { CookLogDraft(recipeId = 1, servings = 4, rating = 6, comment = null, cookedAt = now) }
        assertThrows<IllegalArgumentException> { CookLogDraft(recipeId = 1, servings = 0, rating = null, comment = null, cookedAt = now) }
    }

    @Test
    fun blankCommentsAreLeftOut() {
        assertNull(CookLogDraft(recipeId = 1, servings = 4, rating = null, comment = "  ", cookedAt = now).commentOrNull)
        assertEquals("Gott!", CookLogDraft(recipeId = 1, servings = 4, rating = null, comment = " Gott! ", cookedAt = now).commentOrNull)
    }
}
