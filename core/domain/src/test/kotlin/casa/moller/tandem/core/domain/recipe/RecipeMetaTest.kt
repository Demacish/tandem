package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.model.recipe.Recipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Times, servings and ratings for the recipe header (plan 6.2, 6.7). "_" in the tables is the no-break space. */
class RecipeMetaTest {

    @ParameterizedTest(name = "{0} min = {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "75   | 1_h 15_min",
            "45   | 45_min",
            "1    | 1_min",
            "60   | 1_h",
            "120  | 2_h",
            "840  | 14_h",
            "1501 | 25_h 1_min",
        ],
    )
    fun durations(minutes: Int, expected: String) {
        assertEquals(expected.replace('_', '\u00A0'), DurationText.format(minutes))
    }

    @Test
    fun durationEdgeCasesAndParts() {
        assertNull(DurationText.format(0))
        assertNull(DurationText.format(-5))
        assertEquals(DurationParts(1, 15), DurationText.parts(75))
        assertNull(DurationText.parts(0))
        assertEquals("30\u00A0min", DurationText.total(20, 10))
        assertEquals("10\u00A0min", DurationText.total(-20, 10))
        assertNull(DurationText.total(0, 0))
        assertEquals("1\u00A0tim 5\u00A0minuter", DurationText.format(65, hourUnit = "tim", minuteUnit = "minuter"))
    }

    @Test
    fun servingsFactorLikeTheWeb() {
        assertEquals(1.5, ServingsScaling.factor(4, 6.0))
        assertEquals(0.5, ServingsScaling.factor(2, 1.0))
        assertEquals(2.0, ServingsScaling.factor(0, 2.0))
        assertEquals(13.0 / 12.0, ServingsScaling.factor(12, 13.0))
        assertEquals(1.0, ServingsScaling.factor(4, 0.0))
        assertEquals(1.0, ServingsScaling.factor(4, Double.NaN))
        assertEquals(1, ServingsScaling.baseServings(0))
        val recipe = Recipe(id = 1, name = "Test", servings = 4)
        assertEquals(4.0, ServingsScaling.initialServings(recipe, remembered = null))
        assertEquals(6.0, ServingsScaling.initialServings(recipe, remembered = 6.0))
        assertEquals(4.0, ServingsScaling.initialServings(recipe, remembered = -1.0))
    }

    @Test
    fun servingsText() {
        assertEquals("4", ServingsText.number(4.0, NumberStyle.Swedish))
        assertEquals("2,5", ServingsText.number(2.5, NumberStyle.Swedish))
        assertEquals("2.5", ServingsText.number(2.5, NumberStyle.English))
        assertEquals("0,33", ServingsText.number(1.0 / 3.0, NumberStyle.Swedish))
        assertEquals("4 portioner", ServingsText.label(4.0, "portioner", NumberStyle.Swedish))
        assertEquals("30 bullar", ServingsText.label(30.0, " bullar ", NumberStyle.Swedish))
        assertEquals("4 bitar", ServingsText.label(4.0, "4 bitar", NumberStyle.Swedish))
        assertNull(ServingsText.label(4.0, "", NumberStyle.Swedish))
        assertNull(ServingsText.label(4.0, "  ", NumberStyle.Swedish))
    }

    @Test
    fun ratings() {
        assertNull(RatingDisplay.of(null, NumberStyle.Swedish))
        assertNull(RatingDisplay.of(0.0, NumberStyle.Swedish))
        assertNull(RatingDisplay.of(Double.NaN, NumberStyle.Swedish))
        assertEquals(RatingDisplay(4.0, fullStars = 4, halfStar = false, text = "4"), RatingDisplay.of(4.0, NumberStyle.Swedish))
        assertEquals(RatingDisplay(4.5, fullStars = 4, halfStar = true, text = "4,5"), RatingDisplay.of(4.5, NumberStyle.Swedish))
        assertEquals(RatingDisplay(3.74, fullStars = 3, halfStar = true, text = "3.7"), RatingDisplay.of(3.74, NumberStyle.English))
        assertEquals(RatingDisplay(4.75, fullStars = 5, halfStar = false, text = "4.8"), RatingDisplay.of(4.75, NumberStyle.English))
        assertEquals(RatingDisplay(5.0, fullStars = 5, halfStar = false, text = "5"), RatingDisplay.of(7.0, NumberStyle.English))
        assertEquals(1, RatingDisplay.of(4.0, NumberStyle.Swedish)?.emptyStars)
        assertEquals(0, RatingDisplay.of(4.5, NumberStyle.Swedish)?.emptyStars)
        assertEquals(4, RatingDisplay.of(0.6, NumberStyle.Swedish)?.emptyStars)
    }
}
