package casa.moller.tandem.core.domain.scaling

import casa.moller.tandem.core.domain.format.FractionStyle
import casa.moller.tandem.core.domain.format.NumberStyle

/**
 * A scaled amount, rounded the way Tandoor's web shows it ([WebAmountFormat]). Keeps the parts,
 * so the UI can write it for the user's language ([text]) and tests can compare it with the web
 * ([html], [text] with [NumberStyle.Web]).
 */
sealed interface DisplayAmount {
    /** The amount before rounding (`amount × factor`), e.g. for singular/plural and accessibility. */
    val value: Double

    /** The amount as text in [style]: `2.5` / `2,5`, `1 1⁄2` / `1 ½`. */
    fun text(style: NumberStyle = NumberStyle.Web): String

    /** Exactly the HTML Tandoor's web inserts (`2.5`, `1 <sup>1</sup>&frasl;<sub>2</sub>`). */
    val html: String

    /**
     * A decimal number: [digits] is JavaScript's `String(Number(x.toFixed(decimals)))` with a dot
     * (`187.5`, `0.33`, `100`).
     */
    data class Decimal(override val value: Double, val digits: String) : DisplayAmount {
        override fun text(style: NumberStyle): String =
            if (style.decimalSeparator == '.') digits else digits.replace('.', style.decimalSeparator)

        override val html: String
            get() = digits
    }

    /**
     * A mixed fraction `whole numerator/denominator` with a denominator of at most 16. [whole]
     * is 0 for a proper fraction (`3⁄4`), [numerator] 0 for a whole number (`2`).
     */
    data class Fraction(override val value: Double, val whole: Long, val numerator: Long, val denominator: Long) : DisplayAmount {
        override fun text(style: NumberStyle): String {
            if (numerator <= 0L) return whole.toString()
            val fraction = when (style.fractionStyle) {
                FractionStyle.PLAIN -> "$numerator${DisplayAmount.FRACTION_SLASH}$denominator"
                FractionStyle.TYPOGRAPHIC -> typographic(numerator, denominator)
            }
            if (whole <= 0L) return fraction
            val separator = if (style.fractionStyle == FractionStyle.PLAIN) " " else DisplayAmount.NO_BREAK_SPACE.toString()
            return "$whole$separator$fraction"
        }

        override val html: String
            get() = buildString {
                if (whole > 0L) append(whole)
                if (numerator > 0L) append(" <sup>").append(numerator).append("</sup>&frasl;<sub>").append(denominator).append("</sub>")
            }
    }

    companion object {
        /** U+2044 FRACTION SLASH, what the web's `&frasl;` shows. */
        const val FRACTION_SLASH: Char = '\u2044'

        /** U+00A0, joins the whole number and the fraction in [FractionStyle.TYPOGRAPHIC]. */
        const val NO_BREAK_SPACE: Char = '\u00A0'
    }
}

private val VULGAR_FRACTIONS: Map<Pair<Long, Long>, String> = mapOf(
    (1L to 2L) to "½",
    (1L to 3L) to "⅓",
    (2L to 3L) to "⅔",
    (1L to 4L) to "¼",
    (3L to 4L) to "¾",
    (1L to 5L) to "⅕",
    (2L to 5L) to "⅖",
    (3L to 5L) to "⅗",
    (4L to 5L) to "⅘",
    (1L to 6L) to "⅙",
    (5L to 6L) to "⅚",
    (1L to 7L) to "⅐",
    (1L to 8L) to "⅛",
    (3L to 8L) to "⅜",
    (5L to 8L) to "⅝",
    (7L to 8L) to "⅞",
    (1L to 9L) to "⅑",
    (1L to 10L) to "⅒",
)

private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
private const val SUBSCRIPT_DIGITS = "₀₁₂₃₄₅₆₇₈₉"

private fun typographic(numerator: Long, denominator: Long): String {
    val glyph = VULGAR_FRACTIONS[numerator to denominator]
    if (glyph != null) return glyph
    return numerator.mapDigits(SUPERSCRIPT_DIGITS) + DisplayAmount.FRACTION_SLASH + denominator.mapDigits(SUBSCRIPT_DIGITS)
}

private fun Long.mapDigits(digits: String): String = toString().map { digits[it - '0'] }.joinToString("")
