package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptLine
import casa.moller.tandem.core.model.scan.ReceiptLineKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The first receipt heuristics (#44) on a SYNTHETIC receipt (no real store, no card digits). */
class ReceiptLineFilterTest {
    private val receipt = listOf(
        "EXEMPELBUTIKEN",
        "Org nr 000000-0000",
        "GRÄDDE 36% 5DL 24,90",
        "KIKÄRTOR 380G EKO 12,50",
        "PANT 2,00",
        "RABATT GRÄDDE -5,00",
        "Moms 12% 4,10",
        "SUMMA 34,40",
        "Kort 34,40",
    ).mapIndexed { index, text -> ReceiptLine(index, text) }

    @Test
    fun onlyItemLinesRemainWithoutTheirPrice() {
        assertEquals(listOf("GRÄDDE 36% 5DL", "KIKÄRTOR 380G EKO"), ReceiptLineFilter.items(receipt).map { it.name })
    }

    @Test
    fun theOtherLinesAreClassified() {
        val kinds = receipt.map { ReceiptLineFilter.classify(it) }
        assertEquals(
            listOf(
                ReceiptLineKind.OTHER,
                ReceiptLineKind.HEADER,
                ReceiptLineKind.ITEM,
                ReceiptLineKind.ITEM,
                ReceiptLineKind.DEPOSIT,
                ReceiptLineKind.DISCOUNT,
                ReceiptLineKind.VAT,
                ReceiptLineKind.TOTAL,
                ReceiptLineKind.PAYMENT,
            ),
            kinds,
        )
    }
}
