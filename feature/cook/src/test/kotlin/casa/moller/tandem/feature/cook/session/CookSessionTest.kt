package casa.moller.tandem.feature.cook.session

import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerSource
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.navigation.cookKeyAlongside
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fas 4 (two recipes at once, plan 8.1): the pure session rules and the alongside key. */
class CookSessionTest {

    @Test
    fun oneRecipeOpensAloneAndCanTakeASecond() {
        val session = CookSession.of(CookKey(101, servings = 2.0, startStep = 1))

        assertEquals(listOf(101L), session.recipeIds)
        assertEquals(101L, session.activeRecipeId)
        assertFalse(session.isTwo)
        assertTrue(session.canAdd)
        assertEquals(CookKey(101, servings = 2.0, startStep = 1), session.active.toCookKey())
        assertEquals("cook-101", session.active.viewModelKey)
    }

    @Test
    fun addingASecondRecipeSelectsItAndFillsTheSession() {
        val session = CookSession.of(CookKey(101)).add(102)

        assertEquals(listOf(101L, 102L), session.recipeIds)
        assertEquals(102L, session.activeRecipeId)
        assertTrue(session.isTwo)
        assertFalse(session.canAdd)
        assertEquals("cook-102-1", session.active.viewModelKey)
        // A third recipe does not fit; an open one is only selected.
        assertSame(session, session.add(103))
        assertEquals(101L, session.add(101).activeRecipeId)
    }

    @Test
    fun closingOneReturnsToTheOther() {
        val two = CookSession.of(CookKey(101)).add(102)

        val left = two.remove(102)!!
        assertEquals(listOf(101L), left.recipeIds)
        assertEquals(101L, left.activeRecipeId)
        assertFalse(left.isTwo)
        assertEquals(101L, two.select(101).remove(102)!!.activeRecipeId)
        assertEquals(102L, two.remove(101)!!.activeRecipeId)
        // The only recipe: cook mode closes.
        assertNull(left.remove(101))
    }

    @Test
    fun aRecipeAddedAgainGetsAFreshCookMode() {
        val again = CookSession.of(CookKey(101)).add(102).remove(102)!!.add(102)

        assertEquals("cook-102-2", again.active.viewModelKey)
    }

    @Test
    fun selectingAnUnknownRecipeChangesNothing() {
        val session = CookSession.of(CookKey(101)).add(102)

        assertSame(session, session.select(999))
        assertEquals(101L, session.select(101).activeRecipeId)
    }

    @Test
    fun theSessionRoundTripsThroughText() {
        val session = CookSession.of(CookKey(101, servings = 2.5, startStep = 2)).add(102).select(101)

        assertEquals(session, CookSession.decode(session.encode()))
        assertNull(CookSession.decode(null))
        assertNull(CookSession.decode("garbage"))
        assertNull(CookSession.decode("5|1|101:0:-:0"))
        assertNull(CookSession.decode("101|1|101:0:-:0,101:0:-:0"))
    }

    @Test
    fun aKeyWithARecipeAlongsideOpensBothWithTheNewOneSelected() {
        val key = CookKey(102, servings = 4.0, alongsideRecipeId = 101, alongsideStep = 2)
        val session = CookSession.of(key)

        assertEquals(listOf(101L, 102L), session.recipeIds)
        assertEquals(102L, session.activeRecipeId)
        assertEquals(CookKey(101, startStep = 2), session.recipes.first().toCookKey())
        assertEquals(listOf(101L, 102L), key.recipeIds)
        // The back stack is saved with the key's serializer (Navigation 3).
        assertEquals(key, Json.decodeFromString(CookKey.serializer(), Json.encodeToString(CookKey.serializer(), key)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRecipeIsNotAlongsideItself() {
        CookKey(101, alongsideRecipeId = 101)
    }

    @Test
    fun lagaWhileAnotherRecipesTimerRunsOpensBoth() {
        val source = "account-1"
        val older = timer(1, recipeId = 101, step = 0, source = source, at = 0)
        val newer = timer(2, recipeId = 103, step = 2, source = source, at = 10)

        assertEquals(
            CookKey(102, servings = 2.0, alongsideRecipeId = 103, alongsideStep = 2),
            cookKeyAlongside(102, 2.0, listOf(older, newer), source),
        )
    }

    @Test
    fun lagaWithoutOtherActiveTimersOfTheSourceOpensOneRecipe() {
        val source = "account-1"
        val finished = timer(1, recipeId = 101, step = 0, source = source, at = 0).let { it.ring(it.endsAt!!).stopRinging() }
        val sameRecipe = timer(2, recipeId = 102, step = 1, source = source, at = 0)
        val demo = timer(3, recipeId = 104, step = 1, source = TimerSource.DEMO, at = 0)
        val free = timer(4, recipeId = null, step = null, source = null, at = 0)
        val unknownSource = timer(5, recipeId = 105, step = 0, source = null, at = 0)

        assertEquals(CookKey(102), cookKeyAlongside(102, null, listOf(finished, sameRecipe, demo, free, unknownSource), source))
        assertEquals(CookKey(102), cookKeyAlongside(102, null, listOf(sameRecipe.copy(recipeId = 101)), null))
    }

    private fun timer(id: Long, recipeId: Long?, step: Int?, source: String?, at: Long): CookTimer =
        CookTimer.start(id, "Timer $id", 10.minutes, Instant.fromEpochSeconds(0) + at.seconds, recipeId, step, source)
}
