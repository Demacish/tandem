package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptLine
import casa.moller.tandem.core.model.scan.ReceiptLineKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The per-line receipt heuristics (#44) on SYNTHETIC lines (invented stores, items and prices; no card digits). */
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

    @Test
    fun everyKindOfLineThatIsNotAnItem() {
        val expected = mapOf(
            "Totalt 7 varor   191,73" to ReceiptLineKind.TOTAL,
            "Att betala  153,19" to ReceiptLineKind.TOTAL,
            "Öresavrundning  0,27" to ReceiptLineKind.TOTAL,
            "Moms%  Moms  Netto  Brutto" to ReceiptLineKind.VAT,
            "Varav moms 12%  20,54" to ReceiptLineKind.VAT,
            "+Pant  2,00" to ReceiptLineKind.DEPOSIT,
            "Pantretur  -2,00" to ReceiptLineKind.DEPOSIT,
            "Rabatt Kaffe  -10,00" to ReceiptLineKind.DISCOUNT,
            "Willys Plus-pris  -10,00" to ReceiptLineKind.DISCOUNT,
            "2 FÖR 30,00  -8,90" to ReceiptLineKind.DISCOUNT,
            "Erhållen rabatt  10,00" to ReceiptLineKind.DISCOUNT,
            "KAMPANJ FALUKORV  5,00-" to ReceiptLineKind.DISCOUNT,
            "Stammis 0000000000" to ReceiptLineKind.LOYALTY,
            "Medlemsnummer 0000000000" to ReceiptLineKind.LOYALTY,
            "Bonusgrundande belopp  181,73" to ReceiptLineKind.LOYALTY,
            "Lidl Plus  0000000000" to ReceiptLineKind.LOYALTY,
            "Mottaget Kort  191,73" to ReceiptLineKind.PAYMENT,
            "Kontokort  ************0000" to ReceiptLineKind.PAYMENT,
            "XXXX XXXX XXXX 0000" to ReceiptLineKind.PAYMENT,
            "Swish  45,00" to ReceiptLineKind.PAYMENT,
            "Kontant  200,00" to ReceiptLineKind.PAYMENT,
            "Växel  8,27" to ReceiptLineKind.PAYMENT,
            "Kvitto 0001  Kassa 1" to ReceiptLineKind.HEADER,
            "Tel 000-000 00 00" to ReceiptLineKind.HEADER,
            "Tack för besöket!" to ReceiptLineKind.HEADER,
            "Välkommen åter" to ReceiptLineKind.HEADER,
            "www.exempel.se" to ReceiptLineKind.HEADER,
            "29 OKT 2026  17:42" to ReceiptLineKind.DATE,
            "2026-10-29 17:42" to ReceiptLineKind.OTHER,
            "24,90" to ReceiptLineKind.OTHER,
            "12,00  20,54  171,19  191,73" to ReceiptLineKind.OTHER,
            "1,234kg*24,90kr/kg" to ReceiptLineKind.QUANTITY,
            "  2,050 kg à 12,95 kr/kg    26,55" to ReceiptLineKind.QUANTITY,
            "2 st x 54,90   109,80" to ReceiptLineKind.QUANTITY,
            "3*11,90" to ReceiptLineKind.QUANTITY,
        )
        expected.forEach { (line, kind) -> assertEquals(kind, ReceiptLineFilter.classify(ReceiptLine(0, line)), line) }
    }

    @Test
    fun itemsWithWordsThatLookLikeOtherLinesStayItems() {
        listOf(
            "Läsk 33cl inkl pant  12,90",
            "Mjölk 3% 1,5 l   17,90 B",
            "Coca-Cola 1,5l  24,90",
            "*KYCKLINGFILÉ 900G   89,00",
            "Kortbröd  19,90",
            "Kaffe Mellanrost 450g   2 st*44,90   89,80",
        ).forEach { line -> assertEquals(ReceiptLineKind.ITEM, ReceiptLineFilter.classify(ReceiptLine(0, line)), line) }
    }
}
