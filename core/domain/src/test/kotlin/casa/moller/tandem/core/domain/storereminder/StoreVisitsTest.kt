package casa.moller.tandem.core.domain.storereminder

import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.StoreSpot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fas 5 (butik lane, plan §10 Fas 5 "högst en gång per besök"): the visit state machine, the
 * radius, the geofences that should exist and the count the reminder says.
 */
class StoreVisitsTest {
    private val start = Instant.parse("2026-11-14T10:00:00Z")

    private fun at(after: Duration): Instant = start + after

    /** Runs [events] from a fresh store and returns every decision for an ENTER (EXIT gives null). */
    private fun run(vararg events: Pair<StoreTransition, Duration>, remindPosts: Boolean = true): List<EnterDecision?> {
        var visit = StoreVisit()
        return events.map { (transition, after) ->
            when (transition) {
                StoreTransition.ENTER -> {
                    val (next, decision) = StoreVisits.onEnter(visit, at(after))
                    visit = if (decision == EnterDecision.REMIND && remindPosts) StoreVisits.reminded(next, at(after)) else next
                    decision
                }

                StoreTransition.EXIT -> {
                    visit = StoreVisits.onExit(visit, at(after))
                    null
                }
            }
        }
    }

    @Test
    fun theFirstEnterReminds() {
        assertEquals(listOf(EnterDecision.REMIND), run(StoreTransition.ENTER to Duration.ZERO))
    }

    @Test
    fun aSecondEnterWithoutExitIsTheSameVisit() {
        val decisions = run(
            StoreTransition.ENTER to Duration.ZERO,
            StoreTransition.ENTER to 5.minutes,
            StoreTransition.ENTER to 40.minutes,
        )
        assertEquals(listOf(EnterDecision.REMIND, EnterDecision.SAME_VISIT, EnterDecision.SAME_VISIT), decisions)
    }

    @Test
    fun outToTheCarAndBackInIsTooSoon() {
        val decisions = run(
            StoreTransition.ENTER to Duration.ZERO,
            StoreTransition.EXIT to 20.minutes,
            StoreTransition.ENTER to 25.minutes,
            StoreTransition.EXIT to 2.hours,
            StoreTransition.ENTER to (StoreVisits.MIN_GAP - 1.minutes),
        )
        assertEquals(listOf(EnterDecision.REMIND, null, EnterDecision.TOO_SOON, null, EnterDecision.TOO_SOON), decisions)
    }

    @Test
    fun anExitReArmsTheStoreAfterTheGap() {
        val decisions = run(
            StoreTransition.ENTER to Duration.ZERO,
            StoreTransition.EXIT to 30.minutes,
            StoreTransition.ENTER to StoreVisits.MIN_GAP,
            StoreTransition.EXIT to 4.hours,
            StoreTransition.ENTER to 26.hours,
        )
        assertEquals(listOf(EnterDecision.REMIND, null, EnterDecision.REMIND, null, EnterDecision.REMIND), decisions)
    }

    @Test
    fun aLostExitDoesNotSilenceTheStoreForGood() {
        // The phone was off when we left; the next day's ENTER is a new visit.
        val decisions = run(StoreTransition.ENTER to Duration.ZERO, StoreTransition.ENTER to StoreVisits.STALE_VISIT)
        assertEquals(listOf(EnterDecision.REMIND, EnterDecision.REMIND), decisions)
    }

    @Test
    fun anEnterWithAnEmptyListDoesNotUseUpTheGap() {
        // Nothing posted on the first visit (empty list): the next visit after an exit reminds at once.
        var visit = StoreVisit()
        val (entered, first) = StoreVisits.onEnter(visit, at(Duration.ZERO))
        assertEquals(EnterDecision.REMIND, first)
        visit = StoreVisits.onExit(entered, at(10.minutes))
        assertEquals(EnterDecision.REMIND, StoreVisits.onEnter(visit, at(20.minutes)).second)
    }

    @Test
    fun crossedRunsAreIgnoredAndAChangedClockIsNot() {
        val entered = StoreVisits.onEnter(StoreVisit(), at(1.hours)).first
        val (same, decision) = StoreVisits.onEnter(entered, at(55.minutes))
        assertEquals(EnterDecision.OUT_OF_ORDER, decision)
        assertEquals(entered, same)
        assertEquals(entered, StoreVisits.onExit(entered, at(58.minutes)), "an older exit changes nothing")

        // The clock was set back a day: the event is taken as it comes, a reminder "in the future" is long ago.
        val reminded = StoreVisits.reminded(StoreVisits.onExit(entered, at(2.hours)), at(1.hours))
        assertEquals(EnterDecision.REMIND, StoreVisits.onEnter(reminded, at(-(23.hours))).second)
    }

    @Test
    fun theRadiusSnapsToFiftyMetresBetweenOneAndFiveHundred() {
        assertEquals(200, StoreReminderRadius.DEFAULT_METERS)
        assertEquals(100, StoreReminderRadius.snap(20))
        assertEquals(150, StoreReminderRadius.snap(160f))
        assertEquals(200, StoreReminderRadius.snap(176f))
        assertEquals(500, StoreReminderRadius.snap(9000))
        assertEquals(7, StoreReminderRadius.SLIDER_STEPS, "100, 150 … 500: nine values, seven between the ends")
    }

    @Test
    fun onlyEnabledStoresAreWatchedAndNothingWhenOff() {
        val spots = listOf(spot(3), spot(1, enabled = false), spot(2, radius = 120))
        assertEquals(listOf(2L, 3L), StoreFences.wanted(enabled = true, spots).map { it.storeId })
        assertEquals(listOf(100, 200), StoreFences.wanted(enabled = true, spots).map { it.radiusMeters })
        assertTrue(StoreFences.wanted(enabled = false, spots).isEmpty())
        val many = (1L..150L).map { spot(it) }
        assertEquals(StoreFences.MAX_FENCES, StoreFences.wanted(true, many).size)
    }

    @Test
    fun aFenceIdIsOnlyAStoreId() {
        assertEquals("tandem.store.42", StoreFences.requestId(42))
        assertEquals(42L, StoreFences.storeIdOf("tandem.store.42"))
        assertNull(StoreFences.storeIdOf("someone.else.42"))
        assertNull(StoreFences.storeIdOf("tandem.store.x"))
    }

    @Test
    fun theFingerprintFollowsTheFences() {
        val one = StoreFences.wanted(true, listOf(spot(1), spot(2)))
        assertEquals(StoreFences.fingerprint(one), StoreFences.fingerprint(StoreFences.wanted(true, listOf(spot(2), spot(1)))))
        val wider = StoreFences.wanted(true, listOf(spot(1), spot(2, radius = 300)))
        assertNotEquals(StoreFences.fingerprint(one), StoreFences.fingerprint(wider))
        assertNotEquals(StoreFences.fingerprint(one), StoreFences.fingerprint(emptyList()))
        assertFalse(StoreFences.fingerprint(one).contains("12.3"), "no readable place")
    }

    @Test
    fun theCountIsWhatIsStillToBuy() {
        val now = start
        val entries = listOf(
            entry(1),
            entry(2),
            entry(3, checked = true),
            entry(4, delayUntil = now + 1.hours),
            entry(5, delayUntil = Instant.fromEpochMilliseconds(0)),
        )
        assertEquals(3, StoreReminderCount.open(entries, now))
        assertEquals(0, StoreReminderCount.open(emptyList(), now))
    }

    private fun spot(id: Long, enabled: Boolean = true, radius: Int = 200) =
        StoreSpot(storeId = id, point = GeoPoint(12.3456, -45.6789), radiusMeters = radius, enabled = enabled)

    private fun entry(id: Long, checked: Boolean = false, delayUntil: Instant? = null) =
        ShoppingEntry(id = id, food = ShoppingFood(id, "vara $id"), checked = checked, delayUntil = delayUntil)
}
