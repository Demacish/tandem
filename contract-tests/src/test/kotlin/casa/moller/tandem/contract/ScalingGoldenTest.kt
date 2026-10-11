package casa.moller.tandem.contract

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runs in pr.yml (no server needed). `api-schema/fixtures/2.6.13/fas1/scaling-golden.json` was
 * produced in contract.yml by executing Tandoor 2.6.13's web frontend functions on the recorded
 * recipe details; this test recomputes it from the same committed recipe fixtures with
 * [WebScalingReference], so the algorithm in FACTS.md (Fas 1, Skalning) is known to be complete.
 */
class ScalingGoldenTest {
    private val fas1 = File(System.getProperty("tandem.contract.fixturesDir") ?: "../api-schema/fixtures", "2.6.13/fas1")
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun theReferenceReproducesEveryGoldenValue() {
        val goldenFile = File(fas1, "scaling-golden.json")
        assertTrue(goldenFile.isFile, "missing $goldenFile")
        val golden = json.decodeFromString(Golden.serializer(), goldenFile.readText())
        val decimals = golden.meta["ingredientDecimals"]?.toString()?.toInt() ?: error("meta.ingredientDecimals")
        assertTrue(golden.recipes.size >= MIN_RECIPES, "the golden table covers ${golden.recipes.size} recipes")
        for (recipe in golden.recipes) {
            val body = recordedBody(recipe.fixture)
            assertEquals(recipe.id, body.longAt("id"), recipe.fixture)
            val steps = body.arrAt("steps").objects()
            for (case in recipe.cases) {
                val factor = WebScalingReference.factor(case.servings, body.longAt("servings")?.toInt())
                assertEquals(case.factor, factor, "${recipe.name}: factor for ${case.servings} servings")
                val where = "${recipe.name} (${recipe.fixture}), ${case.servings} servings"
                compare("$where, fractions off", case.fractionsOff, steps, factor, useFractions = false, decimals = decimals)
                compare("$where, fractions on", case.fractionsOn, steps, factor, useFractions = true, decimals = decimals)
            }
        }
    }

    @Test
    fun fractionsAndRoundingFollowJavaScript() {
        val ref = WebScalingReference
        // toFixed rounds the exact binary value: 1.005 is 1.00499999999999989... -> "1".
        assertEquals("1", ref.roundDecimals(1.005, 2))
        assertEquals("0.17", ref.roundDecimals(0.1665, 2))
        assertEquals("100", ref.roundDecimals(100.0, 2))
        assertEquals("0", ref.roundDecimals(0.001, 2))
        assertEquals(" <sup>1</sup>&frasl;<sub>2</sub>", ref.amountHtml(0.5, 1.0, useFractions = true, decimals = 2))
        assertEquals("1 <sup>1</sup>&frasl;<sub>2</sub>", ref.amountHtml(0.75, 2.0, useFractions = true, decimals = 2))
        assertEquals(" <sup>2</sup>&frasl;<sub>3</sub>", ref.amountHtml(0.6667, 1.0, useFractions = true, decimals = 2))
        assertEquals("2", ref.amountHtml(2.0, 1.0, useFractions = true, decimals = 2))
        assertEquals("1 1⁄2", ref.htmlToText(ref.amountHtml(1.5, 1.0, useFractions = true, decimals = 2)))
        assertTrue(ref.isSingular(0.99995))
    }

    private fun compare(
        where: String,
        expected: GoldenView,
        steps: List<JsonObject>,
        factor: Double,
        useFractions: Boolean,
        decimals: Int,
    ) {
        assertEquals(expected.steps.size, steps.size, "$where: steps")
        steps.forEachIndexed { index, step ->
            val actual = WebScalingReference.step(step, factor, useFractions, decimals)
            val golden = expected.steps[index]
            assertEquals(golden.ingredients, actual.ingredients, "$where, step ${index + 1}: ingredients")
            assertEquals(golden.templates, actual.templates, "$where, step ${index + 1}: templates")
            assertEquals(golden.instructionsHtml, actual.instructionsHtml, "$where, step ${index + 1}: instructions")
        }
    }

    private fun recordedBody(fixture: String): JsonObject {
        val recording = Json.parseToJsonElement(File(fas1, fixture).readText()).jsonObject
        return recording.objAt("response")?.objAt("body") ?: error("$fixture has no response body")
    }

    private companion object {
        const val MIN_RECIPES = 10
    }
}
