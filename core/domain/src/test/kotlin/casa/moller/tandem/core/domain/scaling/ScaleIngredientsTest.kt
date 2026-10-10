package casa.moller.tandem.core.domain.scaling

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ScaleIngredientsTest {

    @ParameterizedTest(name = "{0} -> {1} servings gives factor {2}")
    @CsvSource(
        "4, 8, 2.0",
        "4, 2, 0.5",
        "4, 4, 1.0",
        "3, 1, 0.3333333333333333",
        "1, 6, 6.0",
        "2, 3, 1.5",
        "0, 4, 1.0", // a recipe without servings cannot be scaled by servings
        "-2, 4, 1.0",
        "4, 0, 1.0",
        "4, -1, 1.0",
        "NaN, 4, 1.0",
        "4, NaN, 1.0",
        "Infinity, 4, 1.0",
        "4, Infinity, 1.0",
    )
    fun factorFromServings(original: Double, target: Double, expected: Double) {
        assertEquals(expected, ScaleIngredients.factor(original, target), 1e-12)
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource(
        "100, 2, 200",
        "1.5, 2, 3",
        "3, 0.5, 1.5",
        "0, 10, 0",
        "2, 0, 0",
        "1, 0.3333333333333333, 0.3333333333333333",
        "-5, 2, 0", // negative amounts never come out
        "NaN, 2, 0",
        "Infinity, 2, 0",
        "2, NaN, 2", // invalid factors leave the amount as it is
        "2, -3, 2",
        "2, Infinity, 2",
        "1.0E308, 10, 1.7976931348623157E308", // overflow is clamped, not Infinity
    )
    fun scaleAmount(amount: Double, factor: Double, expected: Double) {
        val actual = ScaleIngredients.amount(amount, factor)
        assertEquals(expected, actual, 1e-12)
        assertTrue(actual.isFinite() && actual >= 0.0)
    }

    @Test
    fun headersAndNoAmountRowsAreLeftAlone() {
        val ingredients = listOf(
            ScalableIngredient(id = 1, amount = 0.0, isHeader = true),
            ScalableIngredient(id = 2, amount = 200.0),
            ScalableIngredient(id = 3, amount = 1.0, noAmount = true),
            ScalableIngredient(id = 4, amount = 0.5),
        )
        val scaled = ScaleIngredients(ingredients, originalServings = 4.0, targetServings = 6.0)
        assertEquals(
            listOf(
                ScalableIngredient(id = 1, amount = 0.0, isHeader = true),
                ScalableIngredient(id = 2, amount = 300.0),
                ScalableIngredient(id = 3, amount = 1.0, noAmount = true),
                ScalableIngredient(id = 4, amount = 0.75),
            ),
            scaled,
        )
    }

    @Test
    fun scalingKeepsOrderIdsAndFlags() {
        val ingredients = randomIngredients(Random(7), 50)
        val scaled = ScaleIngredients(ingredients, factor = 2.5)
        assertEquals(ingredients.map { Triple(it.id, it.isHeader, it.noAmount) }, scaled.map { Triple(it.id, it.isHeader, it.noAmount) })
    }

    @Test
    fun scalingByFactorThenInverseReturnsTheOriginal() {
        val random = Random(20261009)
        repeat(500) {
            val ingredients = randomIngredients(random, 12)
            val factor = random.nextDouble(0.01, 100.0)
            val roundTrip = ScaleIngredients(ScaleIngredients(ingredients, factor), 1.0 / factor)
            ingredients.zip(roundTrip).forEach { (original, back) ->
                assertClose(original.amount, back.amount, "factor $factor")
            }
        }
    }

    @Test
    fun scalingTwiceEqualsScalingByTheProduct() {
        val random = Random(42)
        repeat(500) {
            val ingredients = randomIngredients(random, 8)
            val a = random.nextDouble(0.1, 10.0)
            val b = random.nextDouble(0.1, 10.0)
            val twice = ScaleIngredients(ScaleIngredients(ingredients, a), b)
            val once = ScaleIngredients(ingredients, a * b)
            twice.zip(once).forEach { (x, y) -> assertClose(y.amount, x.amount, "a=$a b=$b") }
        }
    }

    @Test
    fun servingsRoundTripReturnsTheOriginal() {
        val random = Random(3)
        repeat(300) {
            val ingredients = randomIngredients(random, 10)
            val original = random.nextInt(1, 13).toDouble()
            val target = random.nextInt(1, 25).toDouble()
            val there = ScaleIngredients(ingredients, original, target)
            val back = ScaleIngredients(there, target, original)
            ingredients.zip(back).forEach { (o, b) -> assertClose(o.amount, b.amount, "$original -> $target") }
        }
    }

    @Test
    fun scaledAmountsAreNeverNegativeOrNaN() {
        val random = Random(99)
        val weird = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 0.0, Double.MIN_VALUE, Double.MAX_VALUE)
        repeat(200) {
            val amount = if (random.nextBoolean()) weird.random(random) else random.nextDouble(-1000.0, 1000.0)
            val factor = if (random.nextBoolean()) weird.random(random) else random.nextDouble(-10.0, 10.0)
            val scaled = ScaleIngredients.amount(amount, factor)
            assertTrue(scaled.isFinite() && scaled >= 0.0, "amount=$amount factor=$factor gave $scaled")
        }
    }

    private fun assertClose(expected: Double, actual: Double, message: String) {
        val tolerance = 1e-9 * max(1.0, abs(expected))
        assertEquals(expected, actual, tolerance, message)
    }

    private fun randomIngredients(random: Random, count: Int): List<ScalableIngredient> = List(count) { index ->
        ScalableIngredient(
            id = index.toLong(),
            amount = random.nextDouble(0.0, 5000.0),
            isHeader = random.nextInt(10) == 0,
            noAmount = random.nextInt(8) == 0,
        )
    }
}
