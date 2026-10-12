package casa.moller.tandem.feature.recipes.detail

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.feature.recipes.FakeSessionRepository
import casa.moller.tandem.feature.recipes.detail.FixtureRecipes.double
import casa.moller.tandem.feature.recipes.detail.FixtureRecipes.long
import casa.moller.tandem.feature.recipes.detail.FixtureRecipes.objects
import casa.moller.tandem.feature.recipes.detail.FixtureRecipes.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The recipe detail shows exactly what Tandoor's web shows (plan §10 Fas 1 "Klart när"): a real
 * [RecipeDetailViewModel] over the core:testing fakes, seeded with recorded Tandoor recipes, at
 * half and double servings with `use_fractions` off and on, must produce the ingredient rows
 * (overview and per step) and step texts of `scaling-golden.json`, which contract.yml makes by
 * running the web frontend's own functions. The numbers are compared in [NumberStyle.Web]; the
 * app's decimal comma and fraction glyphs are a layer on top (FACTS.md "Fas 1: konsekvenser" 6).
 *
 * core:domain's `RecipeDisplayGoldenTest` checks every recipe and servings value; this test proves
 * the screen's state is built from that logic and not from a stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailWebParityTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun theDetailShowsTheWebsIngredientRowsAndStepTexts() = runTest(mainDispatcher.dispatcher) {
        val golden = FixtureRecipes.golden()
        val decimals = golden["meta"]!!.jsonObject.long("ingredientDecimals").toInt()
        val entries = golden.objects("recipes").associateBy { it.string("fixture") }
        var compared = 0
        for (fixture in FIXTURES) {
            val entry = entries[fixture] ?: error("$fixture is not in scaling-golden.json")
            val recipe = FixtureRecipes.recipe(fixture)
            val cases = entry.objects("cases").filter { case -> case.double("factor")?.let { it in FACTORS } == true }
            assertEquals("$fixture: cases for the factors $FACTORS", FACTORS.size, cases.size)
            for (case in cases) {
                val servings = case.double("servings")!!
                for ((key, useFractions) in listOf("fractionsOff" to false, "fractionsOn" to true)) {
                    val where = "${recipe.name} ($fixture), $servings servings, $key"
                    val state = detailState(recipe, servings, UserPreferences(useFractions = useFractions, ingredientDecimals = decimals))
                    assertEquals("$where: servings", servings, state.servings?.current)
                    val steps = case[key]!!.jsonObject.objects("steps")
                    assertEquals("$where: steps", steps.size, state.steps.size)
                    steps.forEachIndexed { index, expected ->
                        val step = state.steps[index]
                        val at = "$where, step ${index + 1}"
                        compareRows(at, expected.objects("ingredients"), step.ingredients)
                        compareInstruction(at, expected.string("instructionsHtml")!!, step, recipe.steps[index].instruction)
                        compared++
                    }
                    // Every fixture shows the overview: all steps' rows in step order.
                    assertEquals("$where: overview", state.steps.flatMap { it.ingredients }, state.ingredients)
                }
            }
        }
        assertTrue("compared $compared steps", compared >= MIN_STEPS)
        println("RecipeDetailWebParityTest: ${FIXTURES.size} recipes, $compared steps identical to scaling-golden.json")
    }

    /** The detail of [recipe] as the ViewModel shows it at [servings] with [preferences]. */
    private fun TestScope.detailState(recipe: Recipe, servings: Double, preferences: UserPreferences): RecipeDetailUiState {
        val repository = FakeRecipeRepository(details = listOf(recipe), overviews = emptyList())
        repository.servingsChoices.value = mapOf(recipe.id to servings)
        val viewModel = RecipeDetailViewModel(
            recipe.id,
            SavedStateHandle(),
            repository,
            FakePreferencesRepository(preferences),
            FakeSyncRepository(),
            FakeSessionRepository(),
        )
        viewModel.onAction(RecipeDetailAction.SetNumberStyle(NumberStyle.Web))
        val collecting = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val state = viewModel.uiState.value
        collecting.cancel()
        return state
    }

    private fun compareRows(where: String, expected: List<JsonObject>, actual: List<IngredientRow>) {
        assertEquals("$where: ingredient rows", expected.size, actual.size)
        expected.forEachIndexed { index, row ->
            val at = "$where, row ${index + 1}"
            val header = row.string("header")
            if (header != null) {
                assertEquals(at, header, (actual[index] as? IngredientRow.Header)?.title)
                return@forEachIndexed
            }
            val item = actual[index] as? IngredientRow.Item ?: error("$at: expected an ingredient, got ${actual[index]}")
            assertEquals("$at: amount", row.string("amount"), item.amount)
            assertEquals("$at: unit", row.string("unit"), item.unit)
            assertEquals("$at: food", row.string("food"), item.food)
            assertEquals("$at: note", row.string("note"), item.note)
        }
    }

    private fun compareInstruction(where: String, expectedHtml: String, step: StepItem, rawInstruction: String) {
        if (expectedHtml.startsWith(BROKEN_TEMPLATE)) {
            assertTrue("$where: the server could not parse the template", step.hasBrokenTemplate)
            assertEquals("$where: shown as written", rawInstruction, step.instruction)
            return
        }
        assertEquals("$where: template parsed", false, step.hasBrokenTemplate)
        assertEquals("$where: instruction", htmlText(expectedHtml), markdownText(step.instruction))
    }

    private companion object {
        /** Mushroom pasta (conversions, notes), cinnamon buns (headers, 30 servings), sandwich cake (odd fractions), shakshuka. */
        val FIXTURES = listOf("recipe-svamppasta.json", "recipe-kanelbullar.json", "recipe-smorgastarta.json", "recipe-shakshuka.json")

        /** Half and double the recipe's servings. */
        val FACTORS = setOf(0.5, 2.0)

        const val MIN_STEPS = 20
        const val BROKEN_TEMPLATE = "Could not parse template code."

        private val htmlTag = Regex("<[^>]+>")
        private val whitespace = Regex("\\s+")
        private val emphasis = Regex("(?<!\\\\)[*_]")
        private val markdownEscape = Regex("\\\\([\\\\`*_\\[\\]<>])")

        /** `&amp;` last, so `&amp;lt;` stays `&lt;`. */
        private val ENTITIES = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&amp;" to "&")

        /** Visible text of the server's HTML: `&frasl;` as U+2044, tags removed, entities decoded, whitespace collapsed. */
        fun htmlText(html: String): String {
            var text = html.replace("&frasl;", "⁄").replace(htmlTag, "")
            for ((entity, character) in ENTITIES) text = text.replace(entity, character)
            return text.replace(whitespace, " ").trim()
        }

        /** Visible text of the app's Markdown: emphasis markers and escapes removed, whitespace collapsed. */
        fun markdownText(markdown: String): String =
            markdown.replace(emphasis, "").replace(markdownEscape, "$1").replace(whitespace, " ").trim()
    }
}
