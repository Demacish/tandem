package casa.moller.tandem.feature.cook.kitchen

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.ui.timer.LocalTimerClock
import casa.moller.tandem.core.ui.timer.LocalTimerTicks
import casa.moller.tandem.feature.cook.TABLET
import casa.moller.tandem.feature.cook.TestFrame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fas 5 (kok lane, plan §8.7 "Köksskärmsläge", #41): the kitchen display in Compose. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class KitchenScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cookOpensTheMealAtItsPlannedServingsAndCloseLeaves() {
        val cooked = mutableListOf<Pair<Long, Double?>>()
        var closed = false
        composeRule.setContent {
            TestFrame {
                KitchenScreen(
                    state = KitchenSamples.meals,
                    onAction = {},
                    onClose = { closed = true },
                    onStartCooking = { id, servings -> cooked += id to servings },
                    onOpenTimer = {},
                )
            }
        }
        composeRule.onNodeWithText("Nothing planned today").assertDoesNotExist()
        composeRule.onNodeWithText("Tomorrow").assertIsDisplayed()
        composeRule.onNodeWithText("No timers running").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cook ${SampleRecipes.MushroomPasta.name}").performClick()
        assertEquals(listOf(SampleRecipes.MushroomPasta.id to 4.0), cooked)
        composeRule.onNodeWithContentDescription("Leave the kitchen display").performClick()
        assertTrue(closed)
    }

    @Test
    fun aTimerOpensItsCookMode() {
        val clock = TestClock()
        val opened = mutableListOf<Long>()
        composeRule.setContent {
            TestFrame(clock = clock) {
                KitchenScreen(
                    state = KitchenSamples.withTimers(clock),
                    onAction = {},
                    onClose = {},
                    onStartCooking = { _, _ -> },
                    onOpenTimer = { opened += it.id },
                )
            }
        }
        composeRule.onNodeWithText("7:39").assertIsDisplayed()
        composeRule.onNodeWithText("Pastan · 10 min", substring = true).performClick()
        assertEquals(listOf(1L), opened)
    }

    /**
     * Plan §10 Fas 5 "Köksskärm: surfplattan kan stå på i 3 timmar med skärmen på" in CI (the device
     * test is the owner's): three hours of the clock and a running timer ticking. The meals never
     * recompose, the clock once a minute, the timers once a second, nothing more (no loop); the
     * screen stays on all the time and goes back off when the display is left.
     */
    @Test
    fun threeHoursWithTheScreenOn() {
        composeRule.mainClock.autoAdvance = false
        val origin = composeRule.mainClock.currentTime
        val start = TestClock.DEFAULT_START
        val clock = object : Clock {
            override fun now(): Instant = start + (composeRule.mainClock.currentTime - origin).milliseconds
        }
        val counts = mutableMapOf<String, Int>()
        val timer = KitchenSamples.timer(1, "Marinera · 4 timmar", 4.hours, TestClock(start))
        var shown by mutableStateOf(true)
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            TandemTheme {
                CompositionLocalProvider(
                    LocalTimerClock provides clock,
                    LocalTimerTicks provides true,
                    LocalKitchenRecompositions provides { part -> counts[part] = (counts[part] ?: 0) + 1 },
                ) {
                    if (shown) {
                        KitchenScreen(
                            state = KitchenSamples.meals.copy(timers = listOf(timer)),
                            onAction = {},
                            onClose = {},
                            onStartCooking = { _, _ -> },
                            onOpenTimer = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val first = counts.toMap()
        val firstTime = composeRule.onNodeWithTag(KITCHEN_CLOCK_TAG).fetchSemanticsNode().config.toString()
        assertTrue(view.keepScreenOn)

        repeat(THREE_HOURS_IN_MINUTES) {
            composeRule.mainClock.advanceTimeBy(MINUTE_MILLIS)
            composeRule.waitForIdle()
            assertTrue(view.keepScreenOn)
        }

        val meals = counts[PART_MEALS] ?: 0
        val clockParts = counts[PART_CLOCK] ?: 0
        val timers = counts[PART_TIMERS] ?: 0
        assertEquals("meals recomposed while nothing changed", first[PART_MEALS], meals)
        assertTrue("clock: $clockParts recompositions", clockParts - (first[PART_CLOCK] ?: 0) in 1..THREE_HOURS_IN_MINUTES + 1)
        assertTrue("timers: $timers recompositions", timers - (first[PART_TIMERS] ?: 0) <= THREE_HOURS_IN_SECONDS + 2)
        assertNotEquals(firstTime, composeRule.onNodeWithTag(KITCHEN_CLOCK_TAG).fetchSemanticsNode().config.toString())
        // The marinade has an hour left after three.
        composeRule.onNodeWithText("1:00:0", substring = true).assertIsDisplayed()

        // Leaving the display: the change is applied (waitForIdle), then a frame recomposes without it.
        shown = false
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(SECOND_MILLIS)
        composeRule.waitForIdle()
        assertFalse(view.keepScreenOn)
    }

    private companion object {
        const val THREE_HOURS_IN_MINUTES = 180
        const val THREE_HOURS_IN_SECONDS = 3 * 3600
        const val MINUTE_MILLIS = 60_000L
        const val SECOND_MILLIS = 1_000L
    }
}
