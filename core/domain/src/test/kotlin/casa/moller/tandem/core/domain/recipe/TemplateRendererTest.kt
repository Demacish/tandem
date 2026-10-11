package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.Recipe
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tandoor step templates (FACTS.md "Fas 1 d"), with the recorded recipes of the contract tests. */
class TemplateRendererTest {
    private val decimals = UserPreferences(useFractions = false)
    private val fractions = UserPreferences(useFractions = true)

    private fun render(recipe: Recipe, step: Int, servings: Double, preferences: UserPreferences, style: NumberStyle): RenderedInstruction {
        val s = recipe.steps[step]
        return TemplateRenderer.render(s.instruction, s.ingredients, ServingsScaling.factor(recipe, servings), preferences, style)
    }

    @Test
    fun ingredientsAndPartsInSwedish() {
        val pasta = RecipeFixtures.recipe("recipe-svamppasta.json")
        assertEquals(
            "Skiva champinjoner och hacka 1 st gul lök.\nPressa 2 vitlöksklyftor.",
            render(pasta, 0, 4.0, decimals, NumberStyle.Swedish).text,
        )
        assertEquals(
            "Skiva champinjoner och hacka 1,5 st gula lökar.\nPressa 3 vitlöksklyftor.",
            render(pasta, 0, 6.0, decimals, NumberStyle.Swedish).text,
        )
        assertEquals(
            "Häll i 2,5 dl vispgrädde och låt puttra.\n\nRör ner 50 g parmesan (*riven*) och smaka av med svartpeppar.",
            render(pasta, 2, 4.0, decimals, NumberStyle.Swedish).text,
        )
    }

    @Test
    fun scaleUsesTheFactorAndTheFractionPreference() {
        val pasta = RecipeFixtures.recipe("recipe-svamppasta.json")
        assertEquals("Koka pastan i 3 liter saltat vatten.", render(pasta, 1, 4.0, decimals, NumberStyle.Swedish).text)
        assertEquals("Koka pastan i 2,25 liter saltat vatten.", render(pasta, 1, 3.0, decimals, NumberStyle.Swedish).text)
        assertEquals("Koka pastan i 2\u00A0¼ liter saltat vatten.", render(pasta, 1, 3.0, fractions, NumberStyle.Swedish).text)
        assertEquals("Koka pastan i 2 1⁄4 liter saltat vatten.", render(pasta, 1, 3.0, fractions, NumberStyle.Web).text)
        val buns = RecipeFixtures.recipe("recipe-kanelbullar.json")
        assertEquals(
            "Bred fyllningen över degen, rulla och skär i 15 bitar. Grädda i 250 grader.",
            render(buns, 2, 15.0, decimals, NumberStyle.Swedish).text,
        )
    }

    @Test
    fun unitTextIsFixedButFoodFollowsTheScaledAmount() {
        val shakshuka = RecipeFixtures.recipe("recipe-shakshuka.json")
        // The server writes the unit for the recipe's own amount (1.5 cups) and never re-plurals it.
        assertEquals(
            "Fry 0.5 onions and 1 cloves garlic, then add 0.75 cups crushed tomatoes and 0.33 cups water.",
            render(shakshuka, 0, 1.0, decimals, NumberStyle.English).text,
        )
        assertEquals(
            "Fry 1 onion and 2 cloves garlic, then add 1.5 cups crushed tomatoes and 0.67 cups water.",
            render(shakshuka, 0, 2.0, decimals, NumberStyle.English).text,
        )
    }

    @Test
    fun noAmountRowsHaveNoAmountAndASingularFood() {
        val meatballs = RecipeFixtures.recipe("recipe-kottbullar.json")
        assertEquals("Servera med lingonsylt.", render(meatballs, 2, 8.0, decimals, NumberStyle.Swedish).text)
        val salt = Ingredient(id = 1, food = Food(1, "salt", "salter"), amount = 0.0, noAmount = true)
        val rendered = TemplateRenderer.render("Smaka av med {{ ingredients[0] }}.", listOf(salt), 2.0, decimals, NumberStyle.Swedish)
        assertEquals("Smaka av med salt.", rendered.text)
    }

    @Test
    fun templatesUseThePreciseAmountFromConversions() {
        // 0.6667 in the JSON, 2/3 on the server: at 13 of 12 servings the web shows 5⁄7, not 8⁄11.
        val cake = RecipeFixtures.recipe("recipe-smorgastarta.json")
        val text = render(cake, 0, 13.0, fractions, NumberStyle.Web).text
        assertEquals("Blanda 3⁄11 l majonnäs med 5⁄7 burkar stenbitsrom. Garnera med 0 st tomater.", text)
    }

    @Test
    fun ingredientsAreIndexedInStepOrder() {
        val first = Ingredient(id = 1, food = Food(1, "mjöl"), unit = MeasurementUnit(1, "dl"), amount = 2.0, order = 1)
        val second = Ingredient(id = 2, food = Food(2, "socker"), unit = MeasurementUnit(2, "dl"), amount = 1.0, order = 0)
        val instruction = "{{ ingredients[0] }}, {{ ingredients[1] }}"
        val rendered = TemplateRenderer.render(instruction, listOf(first, second), 1.0, decimals, NumberStyle.Swedish)
        assertEquals("1 dl socker, 2 dl mjöl", rendered.text)
    }

    @Test
    fun unknownTemplatesStayAsWritten() {
        val egg = Ingredient(id = 1, food = Food(1, "ägg"), amount = 2.0)
        val instruction = "Tillsätt {{ foo }}, {{ ingredients[9] }} och {{ scale(2)|round }}. Klart {{ ingredients[0].colour }}."
        val rendered = TemplateRenderer.render(instruction, listOf(egg), 2.0, decimals, NumberStyle.Swedish)
        assertFalse(rendered.hasBrokenTemplate)
        assertEquals(instruction, rendered.text)
        val mixed = TemplateRenderer.render("{{ foo }} och {{ingredients[0]}}", listOf(egg), 2.0, decimals, NumberStyle.Swedish)
        assertEquals("{{ foo }} och 4 ägg", mixed.text)
    }

    @Test
    fun brokenTemplatesShowTheInstructionAsWritten() {
        val potatoes = RecipeFixtures.recipe("recipe-rarakor.json")
        val broken = render(potatoes, 0, 4.0, decimals, NumberStyle.Swedish)
        assertTrue(broken.hasBrokenTemplate)
        assertEquals("Riv potatisen grovt. {{ scale( }}", broken.text)
        for (instruction in listOf("Blanda {{ ingredients[0] ", "{{ }}", "{{ scale(2)) }}", "{{ 'abc }}", "{{ 1 + }}")) {
            val rendered = TemplateRenderer.render(instruction, emptyList(), 1.0, decimals, NumberStyle.Swedish)
            assertTrue(rendered.hasBrokenTemplate, instruction)
            assertEquals(instruction, rendered.text)
        }
        val fine = render(potatoes, 1, 4.0, decimals, NumberStyle.Swedish)
        assertEquals("Stek tunna rårakor i 3 msk smör. Servera med lingonsylt.", fine.text)
    }

    @Test
    fun insertedTextIsMarkdownEscaped() {
        val secret = Ingredient(id = 1, food = Food(1, "*hemlig* [krydda]"), amount = 1.0, note = "_extra_")
        val instruction = "{{ ingredients[0] }} ({{ ingredients[0].note }})"
        val rendered = TemplateRenderer.render(instruction, listOf(secret), 1.0, decimals, NumberStyle.Swedish)
        assertEquals("1 \\*hemlig\\* \\[krydda\\] (\\_extra\\_)", rendered.text)
    }

    @Test
    fun textWithoutTemplatesIsUnchanged() {
        val text = "Grädda i 200 grader i {25} minuter. Servera { varm }."
        assertEquals(RenderedInstruction(text), TemplateRenderer.render(text, emptyList(), 2.0, decimals, NumberStyle.Swedish))
    }

    @Test
    fun everyRecordedRecipeRendersWithoutLeftOverTemplates() {
        for (file in RecipeFixtures.recipeFiles) {
            val recipe = RecipeFixtures.recipe(file)
            for (servings in listOf(1.0, recipe.servings.toDouble(), recipe.servings * 2.0)) {
                for (preferences in listOf(decimals, fractions)) {
                    recipe.steps.forEachIndexed { index, step ->
                        val rendered = render(recipe, index, servings, preferences, NumberStyle.Swedish)
                        if (rendered.hasBrokenTemplate) {
                            assertEquals(step.instruction, rendered.text, "$file step ${index + 1}")
                        } else {
                            assertFalse(rendered.text.contains("{{"), "$file step ${index + 1}: ${rendered.text}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun neverThrowsOnRandomInput() {
        val random = Random(2026)
        val pieces = listOf("{{", "}}", "{", "}", "scale(", ")", "ingredients[", "]", ".amount", ".food")
            .plus(listOf("0", "1", "99", " ", "'", "\"", "x", "\n", "*"))
        val ingredients = listOf(
            Ingredient(id = 1, food = Food(1, "ägg", "ägg"), amount = 2.0),
            Ingredient(id = 2, isHeader = true, note = "Deg"),
            Ingredient(id = 3, unit = MeasurementUnit(3, "dl"), amount = Double.NaN),
        )
        repeat(5_000) {
            val instruction = buildString { repeat(random.nextInt(1, 12)) { append(pieces[random.nextInt(pieces.size)]) } }
            val factor = listOf(0.0, 1.0, -1.0, Double.NaN, 1e300, 0.5)[random.nextInt(6)]
            val rendered = TemplateRenderer.render(instruction, ingredients, factor, fractions, NumberStyle.Swedish)
            if (rendered.hasBrokenTemplate) assertEquals(instruction, rendered.text)
        }
    }
}
