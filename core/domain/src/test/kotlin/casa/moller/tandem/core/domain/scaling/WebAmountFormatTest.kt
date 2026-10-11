package casa.moller.tandem.core.domain.scaling

import casa.moller.tandem.core.domain.format.FractionStyle
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.format.toNumberStyle
import java.util.Locale
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Tandoor web rounding and fractions (FACTS.md "Fas 1 l" 2), plus the app's language styles. */
class WebAmountFormatTest {

    @ParameterizedTest(name = "{0} with {1} decimals = {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "2.5        | 2 | 2.5",
            "2.0        | 2 | 2",
            "100        | 2 | 100",
            "187.5      | 2 | 187.5",
            "0.6667     | 2 | 0.67",
            "0.3333333  | 2 | 0.33",
            "0.3333333  | 3 | 0.333",
            "0.125      | 2 | 0.13",
            "1.005      | 2 | 1",
            "0.1665     | 2 | 0.17",
            "2.5        | 0 | 3",
            "4.75       | 1 | 4.8",
            "0.001      | 2 | 0",
            "0          | 2 | 0",
            "-1         | 2 | 0",
            "NaN        | 2 | 0",
            "Infinity   | 2 | 0",
            "1.0E15     | 2 | 1000000000000000",
            "16.907     | 2 | 16.91",
            "2.5        | 99 | 2.5",
            "2.5        | -3 | 3",
        ],
    )
    fun decimalsLikeJavaScriptToFixed(amount: Double, decimals: Int, expected: String) {
        val formatted = WebAmountFormat.format(amount, useFractions = false, decimals = decimals)
        assertEquals(expected, formatted.text(NumberStyle.Web))
        assertEquals(expected, formatted.html)
    }

    @ParameterizedTest(name = "{0} as a fraction = {1} / {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "0.5                | ' <sup>1</sup>&frasl;<sub>2</sub>'  | 1⁄2",
            "1.5                | '1 <sup>1</sup>&frasl;<sub>2</sub>' | 1 1⁄2",
            "2.0                | 2                                   | 2",
            "0.6667             | ' <sup>2</sup>&frasl;<sub>3</sub>'  | 2⁄3",
            "2.25               | '2 <sup>1</sup>&frasl;<sub>4</sub>' | 2 1⁄4",
            "0.3333333333333333 | ' <sup>1</sup>&frasl;<sub>3</sub>'  | 1⁄3",
            "0.01               | 0.01                                | 0.01",
            "0                  | 0                                   | 0",
        ],
    )
    fun fractionsLikeTheWeb(amount: Double, html: String, text: String) {
        val formatted = WebAmountFormat.format(amount, useFractions = true, decimals = 2)
        assertEquals(html, formatted.html)
        assertEquals(text, formatted.text(NumberStyle.Web))
    }

    @Test
    fun factorIsAppliedBeforeRoundingLikeTheWeb() {
        // 13/12 servings: the web multiplies with the factor, so 0.666…×(13/12) is 5/7, not 8/11.
        val factor = 13.0 / 12.0
        assertEquals("5⁄7", WebAmountFormat.format(0.6666666666666666, factor, useFractions = true, decimals = 2).text())
        assertEquals("3⁄11", WebAmountFormat.format(0.25, factor, useFractions = true, decimals = 2).text())
        assertEquals("3⁄13", WebAmountFormat.format(0.25, 11.0 / 12.0, useFractions = true, decimals = 2).text())
    }

    @Test
    fun languageStyles() {
        val half = WebAmountFormat.format(1.5, useFractions = true, decimals = 2)
        assertEquals("1\u00A0½", half.text(NumberStyle.Swedish))
        assertEquals("1\u00A0½", half.text(NumberStyle.English))
        assertEquals("½", WebAmountFormat.format(0.5, useFractions = true, decimals = 2).text(NumberStyle.Swedish))
        assertEquals("⅜", WebAmountFormat.format(0.375, useFractions = true, decimals = 2).text(NumberStyle.English))
        val elevenths = WebAmountFormat.format(0.25, 13.0 / 12.0, useFractions = true, decimals = 2)
        assertEquals("³⁄₁₁", elevenths.text(NumberStyle.Swedish))
        assertEquals("2,5", WebAmountFormat.format(2.5, useFractions = false, decimals = 2).text(NumberStyle.Swedish))
        assertEquals("2.5", WebAmountFormat.format(2.5, useFractions = false, decimals = 2).text(NumberStyle.English))
        assertEquals("1000", WebAmountFormat.format(1000.0, useFractions = false, decimals = 2).text(NumberStyle.Swedish))
    }

    @Test
    fun localesGiveTheirDecimalSeparator() {
        assertEquals(',', Locale.forLanguageTag("sv-SE").toNumberStyle().decimalSeparator)
        assertEquals('.', Locale.forLanguageTag("en-US").toNumberStyle().decimalSeparator)
        assertEquals(FractionStyle.TYPOGRAPHIC, Locale.ENGLISH.toNumberStyle().fractionStyle)
        assertEquals(FractionStyle.PLAIN, Locale.ENGLISH.toNumberStyle(FractionStyle.PLAIN).fractionStyle)
    }

    @Test
    fun pluralRuleLikeTheWeb() {
        assertTrue(PluralRule.isSingular(1.0))
        assertTrue(PluralRule.isSingular(0.99995))
        assertFalse(PluralRule.isSingular(0.5))
        assertEquals("gula lökar", PluralRule.name("gul lök", "gula lökar", 0.25))
        assertEquals("gula lökar", PluralRule.name("gul lök", "gula lökar", 0.0))
        assertEquals("gul lök", PluralRule.name("gul lök", "gula lökar", 1.00001))
        assertEquals("gul lök", PluralRule.name("gul lök", "gula lökar", 3.0, noAmount = true))
        assertEquals("smör", PluralRule.name("smör", null, 3.0))
        assertEquals("smör", PluralRule.name("smör", "", 3.0))
    }

    @Test
    fun neverThrowsAndAlwaysGivesText() {
        val random = Random(42)
        val specials = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0)
            .plus(listOf(Double.MIN_VALUE, Double.MAX_VALUE, 1e300))
        val amounts = specials + List(2_000) { random.nextDouble(-10.0, 10_000.0) }
        for (amount in amounts) {
            for (fractions in listOf(false, true)) {
                for (decimals in -1..4) {
                    val formatted = WebAmountFormat.format(amount, useFractions = fractions, decimals = decimals)
                    assertTrue(formatted.text(NumberStyle.Swedish).isNotEmpty(), "$amount")
                    assertFalse(formatted.html.contains('-'), "$amount -> ${formatted.html}")
                }
            }
        }
    }

    @Test
    fun fractionDenominatorsStayAtMostSixteen() {
        val random = Random(7)
        repeat(5_000) {
            val amount = random.nextDouble(0.0, 50.0)
            val formatted = WebAmountFormat.format(amount, useFractions = true, decimals = 2)
            if (formatted is DisplayAmount.Fraction) {
                assertTrue(formatted.denominator in 1..WebAmountFormat.MAX_DENOMINATOR, "$amount -> $formatted")
                assertTrue(formatted.numerator in 0 until formatted.denominator, "$amount -> $formatted")
            }
        }
    }
}
