package casa.moller.tandem.core.data.demo

import casa.moller.tandem.core.network.dto.ShoppingListDto
import casa.moller.tandem.core.network.dto.ShoppingListEntryDto
import casa.moller.tandem.core.network.dto.SupermarketCategoryDto
import casa.moller.tandem.core.network.dto.SupermarketDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.json.TandoorJson
import java.io.File
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo shopping list (Phase 2, inköp-data lane; scripts/demo-content/generate_demo_shopping.py):
 * Tandoor-shaped like the recordings in `api-schema/fixtures/2.6.13/fas2/` (strict DTO decoding,
 * the same keys), the same structure in both languages, and realistic content (checked, delayed,
 * a meal's recipe, two members, two stores with their own aisle order) whose dates move with today.
 */
class DemoShoppingContentTest {
    private val assetsDir = File(System.getProperty("tandem.demoAssetsDir") ?: "src/main/assets/demo")
    private val content = DemoContent { path -> File(assetsDir, path).readText(Charsets.UTF_8) }
    private val strictJson = Json(TandoorJson) { ignoreUnknownKeys = false }
    private val languages = listOf(Locale.forLanguageTag("sv-SE"), Locale.ENGLISH)

    private fun raw(file: String): JsonElement = Json.parseToJsonElement(File(assetsDir, file).readText(Charsets.UTF_8))

    private fun fixtureResults(name: String): JsonArray {
        val fas1 = File(System.getProperty("tandem.demoFixtureDir") ?: "../../api-schema/fixtures/2.6.13/fas1")
        val exchange = Json.parseToJsonElement(File(fas1.resolveSibling("fas2"), "$name.json").readText(Charsets.UTF_8)).jsonObject
        return exchange.getValue("response").jsonObject.getValue("body").jsonObject.getValue("results").jsonArray
    }

    private fun <T> strict(serializer: KSerializer<T>, file: String): List<T> =
        strictJson.decodeFromJsonElement(ListSerializer(serializer), raw(file))

    @Test
    fun everyFileDecodesStrictlyAndHasTheRecordedKeys() {
        for (dir in DemoLanguage.entries.map { it.tag }) {
            strict(ShoppingListEntryDto.serializer(), "$dir/shopping-list-entries.json")
            strict(ShoppingListDto.serializer(), "$dir/shopping-lists.json")
            strict(SupermarketDto.serializer(), "$dir/supermarkets.json")
            strict(SupermarketCategoryDto.serializer(), "$dir/supermarket-categories.json")
            val shapes = mapOf(
                "shopping-list-entry-list" to "shopping-list-entries.json",
                "shopping-list-list" to "shopping-lists.json",
                "supermarket-list" to "supermarkets.json",
                "supermarket-category-list" to "supermarket-categories.json",
            )
            for ((fixture, file) in shapes) {
                assertSameShape("$dir/$file", fixtureResults(fixture)[0], raw("$dir/$file").jsonArray[0])
            }
        }
    }

    @Test
    fun theListIsRealisticInBothLanguages() {
        val today = LocalDate(2026, 10, 11)
        val now = java.time.Instant.parse("2026-10-11T12:00:00Z")
        for (locale in languages) {
            val entries = content.shoppingEntries(locale, today)
            assertTrue(entries.size in 15..30)
            assertTrue(entries.any { it.checked } && entries.any { !it.checked })
            assertTrue("one meal's ingredients", entries.count { it.listRecipeData?.mealplan != null } >= 5)
            assertTrue("two household members", entries.mapNotNull { it.createdBy?.id }.toSet().size == 2)
            val delayed = entries.mapNotNull { TandoorDates.parseInstantOrNull(it.delayUntil) }
            assertTrue("one entry is delayed until tomorrow", delayed.any { it.isAfter(now) })
            val stores = content.supermarkets(locale)
            assertEquals(2, stores.size)
            assertTrue(stores.all { it.categoryToSupermarket.size >= 6 })
            assertTrue(stores[0].categoryToSupermarket.map { it.category?.id } != stores[1].categoryToSupermarket.map { it.category?.id })
            assertEquals(7, content.supermarketCategories(locale).size)
            assertEquals(2, content.shoppingLists(locale).size)
            // Every aisle of the list exists.
            val aisles = content.supermarketCategories(locale).mapNotNull { it.id }.toSet()
            assertTrue(entries.mapNotNull { it.food?.supermarketCategory?.id }.all { it in aisles })
        }
        val sv = content.shoppingEntries(languages[0], today).map { it.id to it.amount }
        val en = content.shoppingEntries(languages[1], today).map { it.id to it.amount }
        assertEquals("the same entries in both languages", sv, en)
    }

    @Test
    fun checkedAndDelayedDatesMoveWithToday() {
        val first = content.shoppingEntries(Locale.ENGLISH, LocalDate(2026, 10, 11))
        val later = content.shoppingEntries(Locale.ENGLISH, LocalDate(2026, 10, 21))
        for ((a, b) in first.zip(later)) {
            listOf(a.completedAt to b.completedAt, a.delayUntil to b.delayUntil, a.createdAt to b.createdAt).forEach { (x, y) ->
                val before = TandoorDates.parseInstantOrNull(x) ?: return@forEach
                val after = TandoorDates.parseInstantOrNull(y)
                assertEquals(before.plusSeconds(TEN_DAYS_SECONDS), after)
            }
        }
    }

    /** Asserts that [actual] has the same keys as [expected], recursively (first array elements, objects only). */
    private fun assertSameShape(label: String, expected: JsonElement, actual: JsonElement) {
        if (expected is JsonObject && actual is JsonObject) {
            assertEquals("keys of $label", expected.keys, actual.keys)
            for (key in expected.keys) assertSameShape("$label.$key", expected.getValue(key), actual.getValue(key))
        } else if (expected is JsonArray && actual is JsonArray && expected.isNotEmpty() && actual.isNotEmpty()) {
            assertSameShape("$label[0]", expected[0], actual[0])
        }
    }

    private companion object {
        const val TEN_DAYS_SECONDS = 10L * 24 * 60 * 60
    }
}
