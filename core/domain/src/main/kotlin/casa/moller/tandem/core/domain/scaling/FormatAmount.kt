package casa.moller.tandem.core.domain.scaling

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Formats an ingredient amount for display.
 *
 * - **Decimals:** rounded half-up to [decimals] places (Tandoor's `ingredient_decimals`,
 *   default [DEFAULT_DECIMALS]), trailing zeros dropped, decimal separator from [Locale]
 *   (`2,5` in Swedish, `2.5` in English). No grouping separators.
 * - **Fractions:** when Tandoor's `use_fractions` is on, amounts within [FRACTION_TOLERANCE] of
 *   a common fraction (½ ⅓ ⅔ ¼ ¾ ⅛ ⅜ ⅝ ⅞) are shown with the glyph, as a mixed number
 *   (`1 ½`, joined by [MIXED_NUMBER_SEPARATOR]) when above one. Glyphs are the same in every
 *   language. Anything else falls back to decimals, so `0.3` never becomes a far-off fraction.
 * - **Edge values:** a positive amount that rounds to zero keeps one significant digit
 *   (`0.004`) instead of showing `0`; below [NEGLIGIBLE] it is shown as `0`. From [LARGE] up,
 *   amounts are whole numbers. Negative, NaN and infinite input is shown as `0`.
 */
object FormatAmount {
    const val DEFAULT_DECIMALS = 2

    /** No-break space, so `1 ½` never wraps between the whole number and the fraction. */
    const val MIXED_NUMBER_SEPARATOR = ' '

    const val FRACTION_TOLERANCE = 0.02
    private const val MAX_DECIMALS = 6
    private const val NEGLIGIBLE = 1e-6
    private const val LARGE = 1_000_000.0

    private data class Fraction(val value: Double, val glyph: String)

    private val FRACTIONS = listOf(
        Fraction(1.0 / 8, "⅛"),
        Fraction(1.0 / 4, "¼"),
        Fraction(1.0 / 3, "⅓"),
        Fraction(3.0 / 8, "⅜"),
        Fraction(1.0 / 2, "½"),
        Fraction(5.0 / 8, "⅝"),
        Fraction(2.0 / 3, "⅔"),
        Fraction(3.0 / 4, "¾"),
        Fraction(7.0 / 8, "⅞"),
    )

    operator fun invoke(amount: Double, locale: Locale, useFractions: Boolean = false, decimals: Int = DEFAULT_DECIMALS): String {
        if (!amount.isFinite() || amount < NEGLIGIBLE) return "0"
        if (useFractions && amount < LARGE) {
            asFraction(amount)?.let { return it }
        }
        return asDecimal(amount, locale, decimals)
    }

    private fun asFraction(amount: Double): String? {
        val whole = floor(amount)
        val rest = amount - whole
        if (rest <= FRACTION_TOLERANCE) {
            return if (whole > 0.0) wholeNumber(whole) else null
        }
        if (rest >= 1.0 - FRACTION_TOLERANCE) return wholeNumber(whole + 1.0)
        val nearest = FRACTIONS.minBy { abs(it.value - rest) }
        if (abs(nearest.value - rest) > FRACTION_TOLERANCE) return null
        return if (whole > 0.0) "${wholeNumber(whole)}$MIXED_NUMBER_SEPARATOR${nearest.glyph}" else nearest.glyph
    }

    private fun wholeNumber(value: Double): String = BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toPlainString()

    private fun asDecimal(amount: Double, locale: Locale, decimals: Int): String {
        val places = if (amount >= LARGE) 0 else decimals.coerceIn(0, MAX_DECIMALS)
        val exact = BigDecimal.valueOf(amount)
        var rounded = exact.setScale(places, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) {
            rounded = exact.round(MathContext(1, RoundingMode.HALF_UP))
        }
        val plain = rounded.stripTrailingZeros().toPlainString()
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') plain else plain.replace('.', separator)
    }
}
