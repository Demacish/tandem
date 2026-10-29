package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Recognised pieces of a receipt photo to rows, and several photos of one receipt to lines (#44). */
class ReceiptLayoutTest {

    private fun piece(text: String, left: Int, top: Int, height: Int = 20, width: Int = 200) =
        TextFragment(text, left, top, left + width, top + height)

    @Test
    fun aNameAndItsPriceOnTheSameHeightAreOneRow() {
        val rows = ReceiptLayout.rows(
            listOf(
                // The price slightly lower (a receipt is rarely straight) and recognised first.
                piece("17,90", left = 600, top = 104, width = 80),
                piece("MELLANMJÖLK 1,5L", left = 40, top = 100),
                piece("GURKA", left = 40, top = 140),
                piece("14,90", left = 600, top = 137, width = 80),
                piece("   ", left = 0, top = 300),
                piece("TOTALT", left = 40, top = 200),
            ),
        )
        assertEquals(listOf("MELLANMJÖLK 1,5L  17,90", "GURKA  14,90", "TOTALT"), rows)
    }

    @Test
    fun noPiecesNoRows() {
        assertEquals(emptyList<String>(), ReceiptLayout.rows(emptyList()))
    }

    @Test
    fun overlappingPhotosOfOneLongReceiptAreJoinedOnce() {
        val first = listOf("ICA EXEMPEL", "MJÖLK  17,90", "GURKA  14,90", "SMÖR 500G  54,90")
        val second = listOf("GURKA   14,90", "smör 500g  54,90", "KAFFE 450G  44,90", "TOTALT  131,60")
        val third = listOf("Kort  131,60")
        assertEquals(
            listOf("ICA EXEMPEL", "MJÖLK  17,90", "GURKA  14,90", "SMÖR 500G  54,90", "KAFFE 450G  44,90", "TOTALT  131,60", "Kort  131,60")
                .mapIndexed { index, text -> ReceiptLine(index, text) },
            ReceiptLayout.join(listOf(first, second, third)),
        )
    }

    @Test
    fun twoEqualItemsInARowAreKeptWhenThePhotosDoNotOverlap() {
        val lines = ReceiptLayout.join(listOf(listOf("MJÖLK  17,90"), listOf("BRÖD  24,90")))
        assertEquals(listOf("MJÖLK  17,90", "BRÖD  24,90"), lines.map { it.text })
    }
}
