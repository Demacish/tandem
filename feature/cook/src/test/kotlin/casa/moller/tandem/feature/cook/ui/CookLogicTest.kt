package casa.moller.tandem.feature.cook.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure parts of the cook screen: the visible text, where the tappable durations are, and which layout a window gets. */
class CookLogicTest {

    @Test
    fun durationsArePlacedInTheirPiecesByOffset() {
        // Plain text "Koka pastan 8–10 minuter.\nLåt vila 5 min, sedan 5 min till." (pieces joined by "\n").
        val pieces = listOf("Koka pastan 8–10 minuter.", "Låt vila 5 min, sedan 5 min till.")
        val second = pieces[0].length + 1
        val spans = timerSpans(
            pieces.map { it.length },
            listOf(TimerMatch(0, 12 until 24), TimerMatch(1, second + 9 until second + 14), TimerMatch(2, second + 22 until second + 27)),
        )

        assertEquals(listOf(TimerSpan(0, 12, 24)), spans[0])
        assertEquals(listOf(TimerSpan(1, 9, 14), TimerSpan(2, 22, 27)), spans[1])
    }

    @Test
    fun aDurationAcrossTwoPiecesIsNotTappable() {
        val spans = timerSpans(listOf(4, 10), listOf(TimerMatch(0, 2 until 8)))

        assertEquals(emptyMap<Int, List<TimerSpan>>(), spans)
    }

    @Test
    fun theVisibleTextHasNoMarkdownMarkers() {
        // "**10** min" was not detected on the source: no unit directly after the number.
        assertEquals("Koka 10 minuter.", stepPlainText("Koka **10** minuter."))
        assertEquals("Rubrik\nKoka i 10\nminuter.\nett\ntvå", stepPlainText("## Rubrik\nKoka i 10\nminuter.\n\n- ett\n- två"))
    }

    @Test
    fun tabletopHalvesMeetAtTheFold() {
        // A column 1830 px tall starting at 0; the fold from 905 to 925 px.
        assertEquals(TabletopSplit(upper = 905f, gap = 20f), tabletopSplit(FoldBounds(905f, 925f), windowTop = 0f, height = 1830f))
        // The column starts 48 px down (status bar): the upper part is shorter by that much.
        assertEquals(TabletopSplit(upper = 857f, gap = 20f), tabletopSplit(FoldBounds(905f, 925f), windowTop = 48f, height = 1782f))
        // No fold, not placed yet, or a fold outside the column: split evenly.
        assertNull(tabletopSplit(null, windowTop = 0f, height = 1830f))
        assertNull(tabletopSplit(FoldBounds(905f, 925f), windowTop = Float.NaN, height = 1830f))
        assertNull(tabletopSplit(FoldBounds(2000f, 2020f), windowTop = 0f, height = 1830f))
    }

    @Test
    fun layoutFollowsTheWindowNotTheDevice() {
        assertEquals(CookLayout.PHONE, cookLayoutFor(412.dp, tabletop = false))
        assertEquals(CookLayout.TWO_PANE, cookLayoutFor(600.dp, tabletop = false))
        assertEquals(CookLayout.TWO_PANE, cookLayoutFor(1280.dp, tabletop = false))
        assertEquals(CookLayout.TABLETOP, cookLayoutFor(412.dp, tabletop = true))
    }
}
