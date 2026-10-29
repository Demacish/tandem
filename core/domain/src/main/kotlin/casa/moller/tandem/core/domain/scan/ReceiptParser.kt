package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.scan.ReceiptItem
import casa.moller.tandem.core.model.scan.ReceiptLine
import casa.moller.tandem.core.model.scan.ReceiptLineKind

/**
 * Receipt text to items (#44, plan §8.8 "Kvitto"). The lines come in reading order from
 * [ReceiptLayout] (text recognition on the device, grouped into rows by geometry). Per line
 * [ReceiptLineFilter] drops totals, VAT, pant, discounts, payment, loyalty, dates, header/footer
 * and price-only lines; here the neighbouring lines are combined:
 *
 * - a weight or count line ("1,234 kg x 24,90 kr/kg", "2 st x 12,90") gives the amount of the item
 *   above it, or of the name line just before it when that name has no price of its own (the
 *   weighed fruit at Willys, the multi-buy at Lidl);
 * - a name without a price followed by a price-only line is one item (a row the layout split);
 * - in the item line itself: a count at the end ("SMÖR 500G 2 x 54,90"), a weight ("BANAN EKO
 *   1,23 kg") or a package size ("GRÄDDE 36% 5DL", "MJÖLK 1,5L", "ÄGG 12P").
 *
 * [ReceiptItem.name] is the line as printed without its price, VAT letter, markers and long digit
 * runs (it is also the learned alias, so it must be the same the next time the store prints it);
 * [ReceiptNormalizer.searchText] turns it into words for the food search. Pure: no Android.
 */
object ReceiptParser {
    private val inlineCount = Regex("""(?<![\d,.])(\d{1,3})\s*(?:st|pcs)?\s*[x*×]\s*\d{1,5}[,.]\d{2}\s*$""", RegexOption.IGNORE_CASE)
    private val inlineWeight = Regex("""(?<![\d,.])(\d{1,3}[,.]\d{1,3})\s*kg(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val packageSize = Regex(
        """(?<![\d,.])(\d{1,4}(?:[,.]\d{1,3})?)\s*(kg|hg|g|l|dl|cl|ml|st|p|pack|-pack|-p)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val longDigits = Regex("""(?<!\d)\d{8,}(?!\d)""")
    private val leadingMarks = Regex("""^[\s*+#>.\-]+""")
    private val spaces = Regex("""\s+""")
    private val letters = Regex("""\p{L}{2,}""")

    /** The items of [lines] (one receipt, possibly several photos already joined), in receipt order. */
    fun parse(lines: List<ReceiptLine>): List<ReceiptItem> {
        val items = mutableListOf<ItemBuilder>()
        var pendingName: ReceiptLine? = null
        for (line in lines) {
            val kind = ReceiptLineFilter.classify(line.text)
            val pending = pendingName
            pendingName = null
            when (kind) {
                ReceiptLineKind.ITEM -> items += ItemBuilder(line, withoutPrice(line.text))

                ReceiptLineKind.QUANTITY -> {
                    val target = if (pending != null) {
                        ItemBuilder(pending, pending.text).also { items += it }
                    } else {
                        items.lastOrNull()?.takeIf { it.detail == null }
                    }
                    target?.detail = quantityOf(line.text)
                }

                ReceiptLineKind.OTHER -> when {
                    pending != null && isPriceOnly(line.text) -> items += ItemBuilder(pending, pending.text)
                    // A member number never becomes an item name.
                    hasLetters(line.text) && !ReceiptLineFilter.trailingPrice.containsMatchIn(line.text) &&
                        !longDigits.containsMatchIn(line.text) -> pendingName = line
                }

                else -> Unit
            }
        }
        return items.mapNotNull { it.build() }
    }

    /** The line without its price, VAT letter or markers. */
    private fun withoutPrice(text: String): String = text.replace(ReceiptLineFilter.trailingPrice, "")

    private fun isPriceOnly(text: String): Boolean = !hasLetters(text) && ReceiptLineFilter.trailingPrice.containsMatchIn(text)

    private fun hasLetters(text: String): Boolean = letters.containsMatchIn(text)

    private fun quantityOf(text: String): Quantity? {
        ReceiptLineFilter.weightDetail.find(text)?.let { return Quantity(number(it.groupValues[1]) ?: return null, KG, weighed = true) }
        ReceiptLineFilter.countDetail.find(text)?.let { return Quantity(number(it.groupValues[1]) ?: return null, null, weighed = false) }
        return null
    }

    private fun number(text: String): Double? = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }

    private class Quantity(val amount: Double, val unit: String?, val weighed: Boolean)

    private class ItemBuilder(val line: ReceiptLine, val body: String) {
        var detail: Quantity? = null

        fun build(): ReceiptItem? {
            var name = body
            val inline = inlineCount.find(name)
            if (inline != null) name = name.removeRange(inline.range)
            name = clean(name)
            if (!letters.containsMatchIn(name)) return null
            val count = detail?.takeUnless { it.weighed }?.amount ?: inline?.let { number(it.groupValues[1]) }
            val (amount, unit) = amountOf(name, detail?.takeIf { it.weighed }, count)
            return ReceiptItem(line = line, name = name, amount = amount, unit = unit)
        }

        private fun amountOf(name: String, weighed: Quantity?, count: Double?): Pair<Double?, String?> {
            if (weighed != null) return weighed.amount to weighed.unit
            inlineWeight.find(name)?.let { match -> number(match.groupValues[1])?.let { return it to KG } }
            val size = packageSize.findAll(name).lastOrNull()
            val sizeAmount = size?.let { number(it.groupValues[1]) }
            if (size == null || sizeAmount == null) return count to null
            // Two packages of "GRÄDDE 5DL" are 10 dl; "ÄGG 12P" is 12 st.
            return sizeAmount * (count ?: 1.0) to unitOf(size.groupValues[2])
        }
    }

    private fun clean(text: String): String = text
        .replace(longDigits, " ")
        .replace(leadingMarks, "")
        .replace(spaces, " ")
        .trim()
        .trimEnd('*', '#', '-', '.')
        .trim()

    private fun unitOf(raw: String): String = when (val unit = raw.lowercase()) {
        "p", "pack", "-pack", "-p", "st" -> PIECES
        else -> unit
    }

    private const val KG = "kg"
    private const val PIECES = "st"
}
