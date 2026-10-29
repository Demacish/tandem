package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The receipt parser (#44) on SYNTHETIC receipts in the layouts of large Swedish chains (ICA,
 * Coop, Willys, Hemköp, Lidl). Every store, address, item, price and number is invented: no real
 * receipt, no card digits, no member numbers (only zeros).
 */
class ReceiptParserTest {

    private data class Expected(val name: String, val amount: Double? = null, val unit: String? = null)

    private fun parse(text: String): List<Expected> = ReceiptParser.parse(
        text.trimIndent().lines().mapIndexed { index, line -> ReceiptLine(index, line) },
    ).map { Expected(it.name, it.amount, it.unit) }

    @Test
    fun icaLayout() {
        val items = parse(
            """
            ICA Supermarket Exempelby
            Exempelgatan 1
            123 45 EXEMPELSTAD
            Org.nr 000000-0000
            Tel 000-000 00 00
            Kvitto 0001  Kassa 1
            2026-10-29 17:42
            Beskrivning      Pris
            Mellanmjölk 1,5L         17,90
            Gräddfil 3dl              12,50
            Bananer Eko               30,73
              1,234kg*24,90kr/kg
            Gurka                      14,90
            Kaffe Mellanrost 450g   2 st*44,90   89,80
            Rabatt Kaffe              -10,00
            Coca-Cola 1,5l             24,90
            Pant                        2,00
            Totalt 7 varor            191,73
            Erhållen rabatt           10,00
            Mottaget Kort             191,73
            Kort  ************0000
            Moms%   Moms   Netto   Brutto
            12,00   20,54  171,19  191,73
            Stammis 0000000000
            Bonusgrundande belopp     181,73
            Tack för besöket!
            Välkommen åter
            """,
        )
        assertEquals(
            listOf(
                Expected("Mellanmjölk 1,5L", 1.5, "l"),
                Expected("Gräddfil 3dl", 3.0, "dl"),
                Expected("Bananer Eko", 1.234, "kg"),
                Expected("Gurka"),
                Expected("Kaffe Mellanrost 450g", 900.0, "g"),
                Expected("Coca-Cola 1,5l", 1.5, "l"),
            ),
            items,
        )
    }

    @Test
    fun coopLayout() {
        val items = parse(
            """
            Coop Exempelby
            Exempelvägen 2, 123 45 Exempelstad
            KVITTO
            VISPGRÄDDE 40% 5DL          21,95
            ÄGG 12P FRIGÅENDE           42,90
            FALUKORV                    29,90
            KAMPANJ FALUKORV            -5,00
            POTATIS FAST
              2,050 kg à 12,95 kr/kg    26,55
            HAVREGRYN 1,5KG             26,50
            TOMATER KROSSADE 400G  3 x 11,90  35,70
            PANT                         6,00
            TOTALT                     183,55
            Medlemsnummer 0000000000
            KONTOKORT                  183,55
            MOMS 12%                    19,67
            """,
        )
        assertEquals(
            listOf(
                Expected("VISPGRÄDDE 40% 5DL", 5.0, "dl"),
                Expected("ÄGG 12P FRIGÅENDE", 12.0, "st"),
                Expected("FALUKORV"),
                Expected("POTATIS FAST", 2.05, "kg"),
                Expected("HAVREGRYN 1,5KG", 1.5, "kg"),
                Expected("TOMATER KROSSADE 400G", 1200.0, "g"),
            ),
            items,
        )
    }

    @Test
    fun willysLayout() {
        val items = parse(
            """
            WILLYS EXEMPELSTAD
            *KYCKLINGFILÉ 900G           89,00
            Lösvikt Lök gul
            0,512kg x 19,90kr/kg         10,19
            SMÖR NORMALSALT 500G         54,90
            2 st x 54,90                109,80
            Willys Plus-pris            -10,00
            BANAN EKO 1,23 kg            30,63
            Willys Plus 0000000000
            Summa att betala            219,62
            Betalat med kort            219,62
            """,
        )
        assertEquals(
            listOf(
                Expected("KYCKLINGFILÉ 900G", 900.0, "g"),
                Expected("Lösvikt Lök gul", 0.512, "kg"),
                Expected("SMÖR NORMALSALT 500G", 1000.0, "g"),
                Expected("BANAN EKO 1,23 kg", 1.23, "kg"),
            ),
            items,
        )
    }

    @Test
    fun hemkopLayout() {
        val items = parse(
            """
            HEMKÖP EXEMPELTORGET
            Org nr 000000-0000
            KRÄM FRAICHE 34% 2DL      19,95
            LAXFILÉ 2-P 250G          64,90
            PRISNEDSÄTTNING LAX      -15,00
            Hemköp Plus 0000000000
            DELSUMMA                  69,85
            Kontaktlös VISA           69,85
            XXXX XXXX XXXX 0000
            """,
        )
        assertEquals(listOf(Expected("KRÄM FRAICHE 34% 2DL", 2.0, "dl"), Expected("LAXFILÉ 2-P 250G", 250.0, "g")), items)
    }

    @Test
    fun lidlLayout() {
        val items = parse(
            """
            Lidl
            Exempelgatan 3
            SEK
            Mjölk 3% 1,5 l           17,90 B
            Bröd Rågbröd             24,90 B
            Bananer
            1,105 kg x 21,90 SEK/kg   24,20 B
            Yoghurt Naturell 1kg   2 x 21,90   43,80 B
            Lidl Plus rabatt          -5,00
            Pantretur                 -2,00
            Att betala               103,80
            Kort                     103,80
            Lidl Plus  0000000000
            """,
        )
        assertEquals(
            listOf(
                Expected("Mjölk 3% 1,5 l", 1.5, "l"),
                Expected("Bröd Rågbröd"),
                Expected("Bananer", 1.105, "kg"),
                Expected("Yoghurt Naturell 1kg", 2.0, "kg"),
            ),
            items,
        )
    }

    @Test
    fun aNameAndItsPriceOnTwoRowsAreOneItem() {
        val items = parse(
            """
            HAVREDRYCK BARISTA 1L
                                        21,90
            Moms 12%
            2,35
            """,
        )
        assertEquals(listOf(Expected("HAVREDRYCK BARISTA 1L", 1.0, "l")), items)
    }

    @Test
    fun aCountLineWithoutAnItemIsIgnored() {
        assertEquals(emptyList<Expected>(), parse("2 st x 12,90   25,80"))
    }

    @Test
    fun numbersAndMarkersNeverReachTheName() {
        val items = parse(
            """
            # 00000000000000 OST HUSHÅLL 700G    79,90
            + PASTA PENNE 500G *                 14,90 *
            """,
        )
        assertEquals(listOf(Expected("OST HUSHÅLL 700G", 700.0, "g"), Expected("PASTA PENNE 500G", 500.0, "g")), items)
    }

    @Test
    fun twentyItemLinesGiveTwentyItems() {
        val names = (1..20).map { "VARA NUMMER ${'A' + it - 1}" }
        val text = buildString {
            appendLine("EXEMPELBUTIKEN")
            names.forEachIndexed { index, name -> appendLine("$name   ${10 + index},90") }
            appendLine("TOTALT   400,00")
            appendLine("Kort   400,00")
        }
        assertEquals(names, parse(text).map { it.name })
    }
}
