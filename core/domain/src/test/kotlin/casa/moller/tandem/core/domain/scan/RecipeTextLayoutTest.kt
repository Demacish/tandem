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

    // Fas 4 review (fix-data): a cookbook page in two columns.
    private fun left(text: String, top: Int, width: Int = 150) = TextFragment(text, 0, top, width, top + 20)

    private fun right(text: String, top: Int, width: Int = 300) = TextFragment(text, 220, top, 220 + width, top + 20)

    private val columns = listOf(
        left("Ingredienser", 100),
        left("2 dl grädde", 130),
        left("1 gul lök", 160),
        left("3 ägg", 190, width = 60),
        right("Gör så här", 100),
        right("Skala löken och hacka den", 130),
        right("Vispa grädden lätt", 160),
        right("Blanda och grädda", 190),
    )
    private val readColumns = listOf(
        listOf("Ingredienser", "2 dl grädde", "1 gul lök", "3 ägg"),
        listOf("Gör så här", "Skala löken och hacka den", "Vispa grädden lätt", "Blanda och grädda"),
    )

    @Test
    fun twoColumnsOnTheSameRowsAreReadOneAfterTheOther() {
        assertEquals(readColumns, RecipeTextLayout.paragraphs(columns))
    }

    @Test
    fun twoColumnsThatDoNotShareALineGridToo() {
        val shifted = columns.map { if (it.left > 0) it.copy(top = it.top + 7, bottom = it.bottom + 7) else it }
        assertEquals(readColumns, RecipeTextLayout.paragraphs(shifted))
    }

    @Test
    fun aTitleAcrossBothColumnsAndANoteUnderThemStayWhereTheyAre() {
        val title = TextFragment("Löksoppa med grädde och ägg", 100, 20, 420, 40)
        val note = TextFragment("Serveras med bröd, gärna nybakat och varmt", 40, 260, 480, 280)
        val expected = listOf(listOf(title.text)) + readColumns + listOf(listOf(note.text))
        assertEquals(expected, RecipeTextLayout.paragraphs(columns + title + note))
    }

    @Test
    fun oneColumnWithLinesOfAnyLengthHasNoBreak() {
        val fragments = listOf(
            left("Pannkakor", 0),
            TextFragment("Vispa ihop mjöl och hälften av mjölken", 0, 30, 480, 50),
            left("till en slät smet", 60),
            TextFragment("Tillsätt resten av mjölken och äggen", 0, 90, 460, 110),
        )
        assertEquals(1, RecipeTextLayout.paragraphs(fragments).size)
        assertEquals(fragments.map { it.text }, RecipeTextLayout.paragraphs(fragments).single())
    }

    @Test
    fun anIngredientTableWithItsAmountsInAColumnKeepsItsRows() {
        val table = listOf("2,5 dl" to "vetemjöl", "6 dl" to "mjölk", "3" to "ägg", "1 tsk" to "salt")
        val fragments = table.flatMapIndexed { row, (amount, food) -> listOf(left(amount, row * 30, 60), right(food, row * 30, 100)) }
        assertEquals(listOf(listOf("2,5 dl vetemjöl", "6 dl mjölk", "3 ägg", "1 tsk salt")), RecipeTextLayout.paragraphs(fragments))
    }

    @Test
    fun blankFragmentsAndEmptyPagesAreNothing() {
        assertEquals("", RecipeTextLayout.text(listOf(line("  ", 0))))
        assertEquals("", RecipeTextLayout.text(emptyList()))
        assertEquals("Sida 1\n\nSida 2", RecipeTextLayout.join(listOf("Sida 1", " ", "Sida 2")))
    }
}
