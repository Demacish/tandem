package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptItem
import casa.moller.tandem.core.model.scan.ReceiptLine
import casa.moller.tandem.core.model.scan.ReceiptLineKind

/**
 * Receipt text (ML Kit text recognition on the device, #44) to item lines (plan §8.8: totals, VAT,
 * deposit, discounts and card lines are removed). A FIRST implementation with a few Swedish and
 * English words; the skanning lane fills in the heuristics (store formats, quantity lines such as
 * "2 st x 12,90", weight lines) and their tests. Pure: no Android, no network.
 */
object ReceiptLineFilter {
    private val price = Regex("""(-?\d{1,5}[,.]\d{2})\s*(kr|sek|:-)?\s*[a-z*]?$""", RegexOption.IGNORE_CASE)
    private val letters = Regex("""\p{L}{2,}""")

    private val rules: List<Pair<ReceiptLineKind, Regex>> = listOf(
        ReceiptLineKind.TOTAL to words("summa", "totalt", "total", "att betala", "subtotal", "delsumma"),
        ReceiptLineKind.VAT to words("moms", "vat", "tax", "netto", "brutto"),
        ReceiptLineKind.DEPOSIT to words("pant", "deposit"),
        ReceiptLineKind.DISCOUNT to words("rabatt", "discount", "erbjudande", "kampanj", "bonus", "spara"),
        ReceiptLineKind.PAYMENT to words("kort", "card", "visa", "mastercard", "kontant", "cash", "växel", "change", "swish", "betalt"),
        ReceiptLineKind.HEADER to words("org nr", "org.nr", "tel", "kvitto", "receipt", "kassa", "kassör", "datum", "www"),
    )

    /** What [line] is. Only [ReceiptLineKind.ITEM] lines become review rows. */
    fun classify(line: ReceiptLine): ReceiptLineKind {
        val text = line.text.trim()
        if (text.isEmpty() || !letters.containsMatchIn(text)) return ReceiptLineKind.OTHER
        rules.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.let { return it.first }
        val amount = price.find(text)?.groupValues?.get(1)
        return when {
            amount == null -> ReceiptLineKind.OTHER
            amount.startsWith("-") -> ReceiptLineKind.DISCOUNT
            else -> ReceiptLineKind.ITEM
        }
    }

    /** The item lines of a receipt, with the price removed from the name. */
    fun items(lines: List<ReceiptLine>): List<ReceiptItem> = lines
        .filter { classify(it) == ReceiptLineKind.ITEM }
        .map { line -> ReceiptItem(line, name = line.text.replace(price, "").trim()) }

    private fun words(vararg words: String): Regex {
        val alternatives = words.joinToString("|") { Regex.escape(it) }
        return Regex("""(?<![\p{L}])($alternatives)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    }
}
