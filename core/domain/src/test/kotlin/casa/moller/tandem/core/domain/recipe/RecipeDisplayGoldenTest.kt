package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.RecipeFixtures.boolean
import casa.moller.tandem.core.domain.recipe.RecipeFixtures.double
import casa.moller.tandem.core.domain.recipe.RecipeFixtures.long
import casa.moller.tandem.core.domain.recipe.RecipeFixtures.objects
import casa.moller.tandem.core.domain.recipe.RecipeFixtures.string
import casa.moller.tandem.core.domain.scaling.PluralRule
import casa.moller.tandem.core.domain.scaling.WebAmountFormat
import casa.moller.tandem.core.model.preferences.UserPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Web parity (plan §10 Fas 1 "Klart när"): `api-schema/fixtures/2.6.13/fas1/scaling-golden.json`
 * holds what Tandoor 2.6.13's own web frontend shows for every recorded recipe at several servings,
 * with fractions off and on (made in contract.yml by running the frontend's functions). Every
 * ingredient row, every template value and every step text that [RecipeDisplay] produces with
 * [NumberStyle.Web] must be identical, character for character.
 *
 * Step texts: the golden value is HTML (the server's `instructions_markdown`), the app's is
 * Markdown, so both are compared as visible text (tags/emphasis markers removed, entities
 * decoded, whitespace collapsed); every number and name in them must match exactly.
 */
class RecipeDisplayGoldenTest {
    private val goldenFile = RecipeFixtures.file("scaling-golden.json")

    private fun golden(): JsonObject {
        assumeTrue(
            goldenFile.isFile,
            "SKIPPED: $goldenFile is missing. It comes from the kontrakt lane (contract.yml); commit it to run the web parity test.",
        )
        return Json.parseToJsonElement(goldenFile.readText()).jsonObject
    }

    @Test
    fun everyIngredientRowTemplateAndStepMatchesTheWeb() {
        val golden = golden()
        val decimals = golden["meta"]!!.jsonObject.long("ingredientDecimals").toInt()
        val recipes = golden.objects("recipes")
        assertTrue(recipes.size >= MIN_RECIPES, "the golden table covers ${recipes.size} recipes")
        var compared = 0
        for (entry in recipes) {
            val fixture = entry.string("fixture")!!
            val recipe = RecipeFixtures.recipe(fixture)
            assertEquals(entry.long("id"), recipe.id, fixture)
            for (case in entry.objects("cases")) {
                val servings = case.double("servings")!!
                for ((key, useFractions) in listOf("fractionsOff" to false, "fractionsOn" to true)) {
                    val where = "${recipe.name} ($fixture), $servings servings, $key"
                    val preferences = UserPreferences(useFractions = useFractions, ingredientDecimals = decimals)
                    val display = RecipeDisplay.build(recipe, servings, preferences, NumberStyle.Web)
                    assertEquals(case.double("factor"), display.factor, "$where: factor")
                    val steps = case[key]!!.jsonObject.objects("steps")
                    assertEquals(steps.size, display.steps.size, "$where: steps")
                    steps.forEachIndexed { index, expected ->
                        val at = "$where, step ${index + 1}"
                        compareIngredients(at, expected.objects("ingredients"), display.steps[index].ingredients, useFractions)
                        compareTemplates(at, expected.objects("templates"), display.factor, preferences)
                        compareInstruction(at, expected.string("instructionsHtml")!!, display.steps[index], recipe.steps[index].instruction)
                        compared++
                    }
                }
            }
        }
        assertTrue(compared >= MIN_STEPS, "compared $compared steps")
        println("RecipeDisplayGoldenTest: ${recipes.size} recipes, $compared steps identical to ${goldenFile.name}")
    }

    private fun compareIngredients(where: String, expected: List<JsonObject>, actual: List<IngredientRow>, useFractions: Boolean) {
        assertEquals(expected.size, actual.size, "$where: ingredient rows")
        expected.forEachIndexed { index, row ->
            val at = "$where, row ${index + 1}"
            val header = row.string("header")
            if (header != null) {
                val actualHeader = actual[index] as? IngredientRow.Header ?: error("$at: expected a header, got ${actual[index]}")
                assertEquals(header, actualHeader.title, at)
                return@forEachIndexed
            }
            val item = actual[index] as? IngredientRow.Item ?: error("$at: expected an ingredient, got ${actual[index]}")
            assertEquals(row.string("amount"), item.amount, "$at: amount")
            assertEquals(row.string("unit"), item.unit, "$at: unit")
            assertEquals(row.string("food"), item.food, "$at: food")
            assertEquals(row.string("note"), item.note, "$at: note")
            if (useFractions) {
                assertEquals(row.string("amountHtml"), item.units.firstOrNull()?.value?.html.orEmpty(), "$at: amount HTML")
            }
        }
    }

    private fun compareTemplates(where: String, expected: List<JsonObject>, factor: Double, preferences: UserPreferences) {
        expected.forEachIndexed { index, token ->
            val at = "$where, template ${index + 1}"
            when (token.string("tag")) {
                "scalable-number" -> {
                    val number = token.double("number")!!
                    val amount = WebAmountFormat.format(number, factor, preferences.useFractions, preferences.ingredientDecimals)
                    assertEquals(token.string("html"), amount.html, "$at: HTML")
                    assertEquals(token.string("text"), amount.text(NumberStyle.Web), "$at: text")
                }

                "plural-name" -> {
                    val name = PluralRule.name(
                        singular = token.string("singular")!!,
                        plural = token.string("plural"),
                        amount = token.double("amount")!! * factor,
                        noAmount = token.boolean("noAmount"),
                    )
                    assertEquals(token.string("text"), name, at)
                }

                else -> error("$at: unknown tag ${token.string("tag")}")
            }
        }
    }

    private fun compareInstruction(where: String, expectedHtml: String, step: StepDisplay, rawInstruction: String) {
        if (expectedHtml.startsWith(BROKEN_TEMPLATE)) {
            assertTrue(step.hasBrokenTemplate, "$where: the server could not parse the template")
            assertEquals(rawInstruction, step.instruction, "$where: a broken template shows the instruction as written")
            return
        }
        assertEquals(false, step.hasBrokenTemplate, "$where: template parsed")
        assertEquals(htmlText(expectedHtml), markdownText(step.instruction), "$where: instruction")
    }

    private companion object {
        const val MIN_RECIPES = 10
        const val MIN_STEPS = 200
        const val BROKEN_TEMPLATE = "Could not parse template code."

        private val htmlTag = Regex("<[^>]+>")

        /** `&amp;` last, so `&amp;lt;` stays `&lt;`. */
        private val ENTITIES = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&amp;" to "&")
        private val whitespace = Regex("\\s+")
        private val markdownEscape = Regex("\\\\([\\\\`*_\\[\\]<>])")

        /** Visible text of the server's HTML: `&frasl;` as U+2044, tags removed, entities decoded, whitespace collapsed. */
        fun htmlText(html: String): String {
            var text = html.replace("&frasl;", "\u2044").replace(htmlTag, "")
            for ((entity, character) in ENTITIES) text = text.replace(entity, character)
            return text.replace(whitespace, " ").trim()
        }

        /** Visible text of the app's Markdown: emphasis markers and escapes removed, whitespace collapsed. */
        fun markdownText(markdown: String): String {
            val unescaped = markdown.replace(Regex("(?<!\\\\)[*_]"), "").replace(markdownEscape, "$1")
            return unescaped.replace(whitespace, " ").trim()
        }
    }
}
