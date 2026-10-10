package casa.moller.tandem.core.domain.scaling

import java.util.Locale
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Decimal and fraction formatting of ingredient amounts (plan 6.7 "Format per språk").
 * In the tables "_" stands for the no-break space between a whole number and its fraction.
 */
class FormatAmountTest {

    @ParameterizedTest(name = "{0} ({1}, {2} decimals) = {3}")
    @CsvSource(
        delimiter = '|',
        value = [
            "2.5        | sv | 2 | 2,5",
            "2.5        | en | 2 | 2.5",
            "2.5        | de | 2 | 2,5",
            "2.0        | sv | 2 | 2",
            "200        | en | 2 | 200",
            "0.3333333  | sv | 2 | 0,33",
            "0.3333333  | en | 2 | 0.33",
            "0.6666667  | en | 2 | 0.67",
            "0.125      | en | 2 | 0.13",
            "1.005      | en | 2 | 1.01",
            "1234.5678  | en | 2 | 1234.57",
            "1234.5678  | sv | 2 | 1234,57",
            "0.3333333  | en | 3 | 0.333",
            "0.3333333  | en | 0 | 0.3",
            "2.6        | en | 0 | 3",
            "2.5        | en | 9 | 2.5",
            "0.004      | en | 2 | 0.004",
            "0.004      | sv | 2 | 0,004",
            "0.0000004  | en | 2 | 0",
            "0          | sv | 2 | 0",
            "-1         | en | 2 | 0",
            "NaN        | en | 2 | 0",
            "Infinity   | en | 2 | 0",
            "12345678.9 | en | 2 | 12345679",
            "1.0E15     | en | 2 | 1000000000000000",
            "999999.994 | en | 2 | 999999.99",
        ],
    )
    fun decimals(amount: Double, language: String, decimals: Int, expected: String) {
        assertEquals(expected, FormatAmount(amount, Locale.forLanguageTag(language), useFractions = false, decimals = decimals))
    }

    @ParameterizedTest(name = "{0} with fractions ({1}) = {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "0.5       | en | ½",
            "0.5       | sv | ½",
            "1.5       | en | 1_½",
            "1.5       | sv | 1_½",
            "0.25      | en | ¼",
            "0.75      | en | ¾",
            "0.3333333 | en | ⅓",
            "0.6666667 | en | ⅔",
            "0.33      | sv | ⅓",
            "0.66      | sv | ⅔",
            "0.125     | en | ⅛",
            "0.375     | en | ⅜",
            "0.625     | en | ⅝",
            "0.875     | en | ⅞",
            "2         | en | 2",
            "2.3333333 | en | 2_⅓",
            "3.75      | sv | 3_¾",
            "250.5     | en | 250_½",
            "0.99      | en | 1",
            "1.99      | en | 2",
            "2.01      | en | 2",
            "0.3       | sv | 0,3",
            "0.3       | en | 0.3",
            "0.6       | sv | 0,6",
            "0.2       | en | 0.2",
            "2.2       | sv | 2,2",
            "0.01      | en | 0.01",
            "0.005     | en | 0.01",
            "0         | en | 0",
            "NaN       | sv | 0",
            "-0.5      | en | 0",
            "1000000.5 | en | 1000001",
        ],
    )
    fun fractions(amount: Double, language: String, expected: String) {
        assertEquals(
            expected.replace('_', FormatAmount.MIXED_NUMBER_SEPARATOR),
            FormatAmount(amount, Locale.forLanguageTag(language), useFractions = true),
        )
    }

    @Test
    fun defaultDecimalsMatchTandoor() {
        // Tandoor's UserPreference.ingredient_decimals defaults to 2.
        assertEquals(2, FormatAmount.DEFAULT_DECIMALS)
        assertEquals("0.67", FormatAmount(2.0 / 3.0, Locale.ENGLISH))
    }

    @Test
    fun neverPrintsLongBinaryTails() {
        val random = Random(1)
        repeat(1000) {
            val amount = random.nextDouble(0.0, 10_000.0)
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("sv"))) {
                for (fractions in listOf(false, true)) {
                    val text = FormatAmount(amount, locale, useFractions = fractions)
                    val decimalsShown = text.substringAfter(if (locale.language == "sv") ',' else '.', "").length
                    assertFalse(decimalsShown > 2, "$amount -> $text")
                    assertFalse(text.contains('E') || text.contains('-'), "$amount -> $text")
                }
            }
        }
    }

    @Test
    fun scaledAmountsFormatLikeTheExamplesInThePlan() {
        val sv = Locale.forLanguageTag("sv-SE")
        val en = Locale.forLanguageTag("en-US")
        val half = ScaleIngredients.amount(1.0, ScaleIngredients.factor(4.0, 10.0)) // 2.5
        assertEquals("2,5", FormatAmount(half, sv))
        assertEquals("2.5", FormatAmount(half, en))
        assertEquals("2${FormatAmount.MIXED_NUMBER_SEPARATOR}½", FormatAmount(half, sv, useFractions = true))
        val third = ScaleIngredients.amount(1.0, ScaleIngredients.factor(3.0, 1.0))
        assertEquals("0,33", FormatAmount(third, sv))
        assertEquals("⅓", FormatAmount(third, en, useFractions = true))
    }
}
