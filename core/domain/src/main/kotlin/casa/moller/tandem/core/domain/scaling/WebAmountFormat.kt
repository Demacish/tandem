package casa.moller.tandem.core.domain.scaling

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Rounds and writes a scaled amount exactly like Tandoor 2.6's web (FACTS.md "Fas 1 l", checked
 * against `scaling-golden.json`). This is the formatting for recipe display; [FormatAmount] is
 * the older, general helper and does not match the web.
 *
 * - **Fractions off:** JavaScript `Number(x.toFixed(decimals))`: the exact binary value is
 *   rounded half up (`0.125` → `0.13`, but `1.005` → `1` because 1.005 is stored as
 *   1.00499999…), trailing zeros dropped (`2.50` → `2.5`).
 * - **Fractions on:** the Stern–Brocot approximation with a denominator of at most 16 between
 *   ⌊x⌋ and ⌊x⌋ + 1 (the algorithm of the `frac` package the web uses) as a mixed number
 *   (`1 1⁄2`, `3⁄11`); below 1/32 the decimal form is used.
 *
 * Invalid input never throws: a negative, NaN or infinite amount is shown as `0`, and
 * `decimals` is clamped to `0..`[MAX_DECIMALS].
 */
object WebAmountFormat {
    /** Tandoor's default `ingredient_decimals`. */
    const val DEFAULT_DECIMALS: Int = 2

    /** Largest `ingredient_decimals` honoured (the web would accept up to 100). */
    const val MAX_DECIMALS: Int = 10

    /** Largest denominator of a fraction (the web's `frac(x, 16, true)`). */
    const val MAX_DENOMINATOR: Long = 16

    /** From here on fractions are not tried (a whole number fits a Long exactly). */
    private const val FRACTION_LIMIT = 1e15

    /** Formats [amount] (already multiplied with the factor). */
    fun format(amount: Double, useFractions: Boolean, decimals: Int = DEFAULT_DECIMALS): DisplayAmount {
        val x = if (amount.isFinite() && amount > 0.0) amount else 0.0
        if (useFractions && x < FRACTION_LIMIT) {
            val (whole, numerator, denominator) = mixedFraction(x)
            if (whole != 0L || numerator != 0L || denominator != 1L) {
                return DisplayAmount.Fraction(value = x, whole = whole, numerator = numerator, denominator = denominator)
            }
        }
        return DisplayAmount.Decimal(value = x, digits = toFixed(x, decimals.coerceIn(0, MAX_DECIMALS)))
    }

    /** Formats `amount × factor`; see [ScaleIngredients.amount] for how invalid input is handled. */
    fun format(amount: Double, factor: Double, useFractions: Boolean, decimals: Int): DisplayAmount =
        format(ScaleIngredients.amount(amount, factor), useFractions, decimals)

    /**
     * JavaScript `String(Number(x.toFixed(decimals)))` for `x >= 0`.
     *
     * KMP note: the only JVM API in the display logic (java.math, internal). A port replaces it
     * with any exact decimal type; the rounding must stay on the exact binary value.
     */
    internal fun toFixed(x: Double, decimals: Int): String {
        val rounded = BigDecimal(x).setScale(decimals, RoundingMode.HALF_UP)
        return if (rounded.signum() == 0) "0" else rounded.stripTrailingZeros().toPlainString()
    }

    /**
     * The best fraction with a denominator <= [MAX_DENOMINATOR] by walking the Stern–Brocot tree
     * between ⌊x⌋ and ⌊x⌋ + 1, as (whole, numerator, denominator). Doubles throughout like
     * JavaScript, so ties land on the same side as the web.
     */
    internal fun mixedFraction(x: Double): Triple<Long, Long, Long> {
        val max = MAX_DENOMINATOR.toDouble()
        var n1 = floor(x)
        var d1 = 1.0
        var n2 = n1 + 1.0
        var d2 = 1.0
        if (x != n1) {
            while (d1 <= max && d2 <= max) {
                val mediant = (n1 + n2) / (d1 + d2)
                if (x == mediant) {
                    when {
                        d1 + d2 <= max -> {
                            d1 += d2
                            n1 += n2
                            d2 = max + 1.0
                        }

                        d1 > d2 -> d2 = max + 1.0

                        else -> d1 = max + 1.0
                    }
                    break
                } else if (x < mediant) {
                    n2 += n1
                    d2 += d1
                } else {
                    n1 += n2
                    d1 += d2
                }
            }
        }
        if (d1 > max) {
            d1 = d2
            n1 = n2
        }
        val whole = floor(n1 / d1)
        return Triple(whole.toLong(), (n1 - whole * d1).toLong(), d1.toLong())
    }
}

/**
 * Tandoor's singular/plural choice for units and foods (FACTS.md "Fas 1 l" 4, the web's
 * `isSingularAmount` / `pluralString`).
 */
object PluralRule {
    /** `|amount − 1| < 0.0001` counts as one. */
    const val SINGULAR_TOLERANCE: Double = 0.0001

    /** True when [amount] (already scaled) reads as exactly one. */
    fun isSingular(amount: Double): Boolean = abs(amount - 1.0) < SINGULAR_TOLERANCE

    /**
     * [singular] for a "no amount" row, when [plural] is missing or blank, or when [amount]
     * (already scaled) is one; [plural] otherwise, also for ½ and 0 ("¼ gula lökar").
     */
    fun name(singular: String, plural: String?, amount: Double, noAmount: Boolean = false): String =
        if (noAmount || plural.isNullOrBlank() || isSingular(amount)) singular else plural
}
