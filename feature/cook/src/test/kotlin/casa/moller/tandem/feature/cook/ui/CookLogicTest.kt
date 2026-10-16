package casa.moller.tandem.feature.cook.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure parts of the cook screen: where the tappable durations are, and which layout a window gets. */
class CookLogicTest {

    @Test
    fun durationsAreFoundInTheVisibleTextInOrder() {
        val pieces = listOf("Koka pastan 8–10 minuter.", "Låt vila 5 min, sedan 5 min till.")
        val spans = timerSpans(
            pieces,
            listOf(TimerMatch(0, "8–10 minuter"), TimerMatch(1, "5 min"), TimerMatch(2, "5 min")),
        )

        assertEquals(listOf(TimerSpan(0, 12, 24)), spans[0])
        assertEquals(listOf(TimerSpan(1, 9, 14), TimerSpan(2, 22, 27)), spans[1])
    }

    @Test
    fun aDurationThatIsNotInTheVisibleTextIsSkipped() {
        // "**8**–10 min" in the Markdown source is "8–10 min" on screen: the match crosses a marker.
        val spans = timerSpans(listOf("Koka 8–10 min."), listOf(TimerMatch(0, "**8**–10 min"), TimerMatch(1, "10 min")))

        assertEquals(listOf(TimerSpan(1, 9, 15)), spans[0])
    }

    @Test
    fun blankMatchesAreIgnored() {
        assertEquals(emptyMap<Int, List<TimerSpan>>(), timerSpans(listOf("text"), listOf(TimerMatch(0, " "))))
    }

    @Test
    fun layoutFollowsTheWindowNotTheDevice() {
        assertEquals(CookLayout.PHONE, cookLayoutFor(412.dp, tabletop = false))
        assertEquals(CookLayout.TWO_PANE, cookLayoutFor(600.dp, tabletop = false))
        assertEquals(CookLayout.TWO_PANE, cookLayoutFor(1280.dp, tabletop = false))
        assertEquals(CookLayout.TABLETOP, cookLayoutFor(412.dp, tabletop = true))
    }
}
