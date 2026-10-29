package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptItem
import casa.moller.tandem.core.model.scan.ReceiptLine
import casa.moller.tandem.core.model.scan.ReceiptLineKind

/**
 * What ONE receipt line is (#44, plan §8.8 "Kvitto"): an item, or one of the lines the review never
 * shows (totals, VAT/moms, pant, discounts/rabatt, payment and card lines, loyalty/member lines,
 * dates, the store's header and footer, price-only lines), or the weight/count line of an item
 * ([ReceiptLineKind.QUANTITY]). The words are those of large Swedish chains' receipts (ICA, Coop,
 * Willys/Hemköp, Lidl) plus a few English ones. [ReceiptParser] uses this per line and adds what
 * needs the neighbouring lines (multi-line items). Pure: no Android, no network.
 *
 * Privacy: card and member lines and dates are classified away here; nothing in this object logs
 * or keeps a line.
 */
object ReceiptLineFilter {
    /** A price at the end of the line: "24,90", "-5,00", "5,00-", "24,90 kr", "15,90 A", "12,50*". */
    internal val trailingPrice = Regex(
        """(?<![\d,.])(-?)(\d{1,5}[,.]\d{2})(-?)\s*(?:kr|sek|:-)?\s*(?:[A-Za-z]|[*#]+)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val letters = Regex("""\p{L}{2,}""")

    /** "1,234 kg x 24,90 kr/kg", "0,512kg*19,90kr/kg", "1,2 kg à 24,90/kg". */
    internal val weightDetail = Regex(
        """^\W*(\d{1,3}[,.]\d{1,3})\s*kg\s*[x*×à@]\s*\d{1,5}[,.]\d{2}\s*(?:kr|sek)?\s*/\s*kg(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    /** "2 st x 12,90", "2 x 12,90", "2*12,90", "3 ST à 9,95 kr" ("2 för 30,00" is a discount). */
    internal val countDetail = Regex(
        """^\W*(\d{1,3})\s*(?:st|pcs|stk|pack)?\s*[x*×à@]\s*\d{1,5}[,.]\d{2}(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    private val dates = Regex("""(?<!\d)(\d{4}-\d{2}-\d{2}|\d{1,2}[./]\d{1,2}[./]\d{2,4}|\d{1,2}:\d{2}(:\d{2})?)(?!\d)""")

    /** Masked card numbers: "************1234", "XXXX XXXX XXXX 1234". */
    private val maskedCard = Regex("""\*{4,}|(?i:x{4,})""")

    private val rules: List<Pair<ReceiptLineKind, Regex>> = listOf(
        // Only at the start: "Läsk 33 cl inkl pant 12,90" is an item.
        ReceiptLineKind.DEPOSIT to Regex("""^\W*(pant|returpant|pantretur|panten|pantbong|deposit)(?!\p{L})""", RegexOption.IGNORE_CASE),
        ReceiptLineKind.DISCOUNT to anyOf(
            prefixes = listOf("rabatt"),
            words = listOf(
                "extrapris", "kampanj", "kampanjpris", "erbjudande", "prisnedsättning", "nedsatt", "discount", "sparar",
                "sparade", "sparat", "spara", "reducering", "mängdpris", "mängdrabatt", "medlemspris", "medlemsrabatt",
                "klubbpris", "plus-pris", "pluspris", "stammispris", "veckans", "kupong", "coupon",
            ),
            extra = listOf("""(?<!\p{L})\d+\s*för\s*\d""", """(?<!\p{L})köp\s+\d+"""),
        ),
        ReceiptLineKind.LOYALTY to anyOf(
            prefixes = listOf("medlem", "stammis", "poäng", "bonus", "kundnummer", "kundnr", "personnummer", "personnr"),
            words = listOf(
                "saldo", "kund-nr", "ica-kort", "coop-kort", "willys plus", "hemköp plus", "lidl plus", "member",
                "loyalty", "points", "klubb",
            ),
        ),
        ReceiptLineKind.PAYMENT to anyOf(
            prefixes = listOf("kontant", "kortbetal", "terminal"),
            words = listOf(
                "kort", "bankkort", "kontokort", "betalkort", "debit", "kredit", "credit", "visa", "mastercard", "maestro",
                "amex", "cash", "växel", "tillbaka", "change", "swish", "betalt", "betalning", "erhållet", "erhållen",
                "mottaget", "presentkort", "ref", "referens", "aid", "tvr", "tsi", "godkänd", "godkänt", "kontaktlös",
                "köp", "purchase", "card", "pin", "auth", "approved",
            ),
        ),
        ReceiptLineKind.VAT to anyOf(prefixes = listOf("moms"), words = listOf("varav", "netto", "brutto", "vat", "tax", "mva")),
        ReceiptLineKind.TOTAL to anyOf(
            words = listOf(
                "summa", "totalt", "total", "totalsumma", "att betala", "delsumma", "subtotal", "antal varor",
                "antal artiklar", "avrundning", "öresavrundning", "avrundat", "sum",
            ),
        ),
        ReceiptLineKind.HEADER to anyOf(
            prefixes = listOf("kvitto", "kassa", "kassör", "butik", "öppet", "välkommen", "transaktion"),
            words = listOf(
                "org nr", "org.nr", "orgnr", "org.nummer", "organisationsnummer", "tel", "telefon", "receipt", "datum",
                "tid", "www", "http", "https", "tack", "åter", "retur", "byte", "öppettider", "säljare", "ordernr",
                "thank", "welcome", "cashier", "store",
            ),
            extra = listOf("""\.(se|com|nu)(?!\p{L})""", "@"),
        ),
    )

    /** What [line] is. Only [ReceiptLineKind.ITEM] lines become review rows. */
    fun classify(line: ReceiptLine): ReceiptLineKind = classify(line.text)

    internal fun classify(raw: String): ReceiptLineKind {
        val text = raw.trim()
        if (weightDetail.containsMatchIn(text) || countDetail.containsMatchIn(text)) return ReceiptLineKind.QUANTITY
        if (text.isEmpty() || !letters.containsMatchIn(text)) return ReceiptLineKind.OTHER
        rules.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.let { return it.first }
        if (maskedCard.containsMatchIn(text)) return ReceiptLineKind.PAYMENT
        if (dates.containsMatchIn(text)) return ReceiptLineKind.DATE
        val price = trailingPrice.find(text) ?: return ReceiptLineKind.OTHER
        val negative = price.groupValues[1] == "-" || price.groupValues[3] == "-"
        return if (negative) ReceiptLineKind.DISCOUNT else ReceiptLineKind.ITEM
    }

    /** The item lines of a receipt with their amounts (see [ReceiptParser.parse]). */
    fun items(lines: List<ReceiptLine>): List<ReceiptItem> = ReceiptParser.parse(lines)

    /**
     * A regex matching any of [words] as whole words (a space inside a word matches any spacing), any
     * word that starts with one of [prefixes], or one of the [extra] patterns; case does not matter.
     */
    private fun anyOf(prefixes: List<String> = emptyList(), words: List<String> = emptyList(), extra: List<String> = emptyList()): Regex {
        fun escape(word: String) = word.split(' ').joinToString("""\s*""") { Regex.escape(it) }
        val parts = buildList {
            if (words.isNotEmpty()) add("""(?<!\p{L})(?:${words.joinToString("|") { escape(it) }})(?!\p{L})""")
            if (prefixes.isNotEmpty()) add("""(?<!\p{L})(?:${prefixes.joinToString("|") { escape(it) }})\p{L}*""")
            addAll(extra)
        }
        return Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE)
    }
}
