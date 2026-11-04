package casa.moller.tandem.core.domain.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Fas 4: a photographed recipe's lines in reading order, with the page's gaps as blank lines. */
class RecipeTextLayoutTest {
    private fun line(text: String, top: Int, left: Int = 0, height: Int = 20) = TextFragment(text, left, top, left + 100, top + height)

    @Test
    fun rowsAreReadLeftToRightAndGapsStartParagraphs() {
        val fragments = listOf(
            line("Pannkakor", 0),
            line("4 portioner", 30),
            line("2,5 dl", 80),
            line("vetemjöl", 80, left = 120),
            line("6 dl mjölk", 110),
            line("3 ägg", 140),
            line("Vispa ihop allt", 200),
            line("och stek.", 230),
        )
        val expected = listOf(
            listOf("Pannkakor", "4 portioner"),
            listOf("2,5 dl vetemjöl", "6 dl mjölk", "3 ägg"),
            listOf("Vispa ihop allt", "och stek."),
        )
        assertEquals(expected, RecipeTextLayout.paragraphs(fragments))
        val text = "Pannkakor\n4 portioner\n\n2,5 dl vetemjöl\n6 dl mjölk\n3 ägg\n\nVispa ihop allt\noch stek."
        assertEquals(text, RecipeTextLayout.text(fragments))
    }

    @Test
    fun blankFragmentsAndEmptyPagesAreNothing() {
        assertEquals("", RecipeTextLayout.text(listOf(line("  ", 0))))
        assertEquals("", RecipeTextLayout.text(emptyList()))
        assertEquals("Sida 1\n\nSida 2", RecipeTextLayout.join(listOf("Sida 1", " ", "Sida 2")))
    }
}
