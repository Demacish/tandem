package casa.moller.tandem.core.data.demo

import casa.moller.tandem.core.domain.timer.TimerDetector
import casa.moller.tandem.core.network.dto.CookLogDto
import casa.moller.tandem.core.network.dto.KeywordDto
import casa.moller.tandem.core.network.dto.MealPlanDto
import casa.moller.tandem.core.network.dto.MealTypeDto
import casa.moller.tandem.core.network.dto.RecipeBookDto
import casa.moller.tandem.core.network.dto.RecipeBookEntryDto
import casa.moller.tandem.core.network.dto.RecipeDto
import casa.moller.tandem.core.network.dto.RecipeFlatDto
import casa.moller.tandem.core.network.dto.RecipeOverviewDto
import casa.moller.tandem.core.network.dto.UserPreferenceDto
import casa.moller.tandem.core.network.dto.ViewLogDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.json.TandoorJson
import java.io.File
import java.text.Collator
import java.util.Locale
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo content (plan 6.2 "Demoläge") read from core/data/src/main/assets/demo on the plain JVM.
 * Checks that it really is Tandoor 2.6.13-shaped (same keys as the recorded fixtures, strict DTO
 * decoding), that both languages have the same structure, that the date shift is deterministic,
 * and that the timers, templates and images the demo is meant to show are there.
 */
class DemoContentTest {
    private val assetsDir = File(System.getProperty("tandem.demoAssetsDir") ?: "src/main/assets/demo")
    private val content = DemoContent { path -> File(assetsDir, path).readText(Charsets.UTF_8) }
    private val today = LocalDate(2026, 10, 11)
    private val languages = listOf(SWEDISH, Locale.ENGLISH)

    /** Like TandoorJson, but fails on any key the DTOs do not declare (catches typos in the generator). */
    private val strictJson = Json(TandoorJson) { ignoreUnknownKeys = false }

    private fun raw(file: String): JsonElement = Json.parseToJsonElement(File(assetsDir, file).readText(Charsets.UTF_8))

    private fun firstOf(file: String): JsonElement = raw(file).jsonArray[0]

    /** The response body of a recorded Tandoor 2.6.13 exchange in api-schema/fixtures/2.6.13/fas1 (FACTS.md). */
    private fun fixtureBody(name: String): JsonElement {
        val dir = File(System.getProperty("tandem.demoFixtureDir") ?: "../../api-schema/fixtures/2.6.13/fas1")
        val exchange = Json.parseToJsonElement(File(dir, "$name.json").readText(Charsets.UTF_8)).jsonObject
        return exchange.getValue("response").jsonObject.getValue("body")
    }

    private fun fixtureResults(name: String): JsonArray = when (val body = fixtureBody(name)) {
        is JsonArray -> body
        else -> body.jsonObject.getValue("results").jsonArray
    }

    @Test
    fun hasTwelveToFifteenRecipesInBothLanguages() {
        assertTrue(content.recipeIds.size in 12..15)
        for (locale in languages) {
            val recipes = content.recipes(locale, today)
            assertEquals(content.recipeIds, recipes.map { it.id })
            assertEquals(content.recipeIds.toSet(), content.recipeOverviews(locale, today).map { it.id }.toSet())
            assertEquals(content.recipeIds.toSet(), content.recipeFlat(locale).map { it.id }.toSet())
        }
        assertEquals("Pannkakor", content.recipe(7, SWEDISH, today)?.name)
        assertEquals("Swedish pancakes", content.recipe(7, Locale.ENGLISH, today)?.name)
        assertNull(content.recipe(9999, SWEDISH, today))
    }

    @Test
    fun everyFileDecodesStrictlyWithTheNetworkDtos() {
        for (language in DemoLanguage.entries) {
            val dir = language.tag
            for (id in content.recipeIds) {
                val recipe = strict(RecipeDto.serializer(), "$dir/recipe-$id.json")
                assertEquals(id, recipe.id)
                assertTrue("recipe $id has no steps", recipe.steps.isNotEmpty())
            }
            strict(ListSerializer(RecipeOverviewDto.serializer()), "$dir/recipe-overviews.json")
            strict(ListSerializer(RecipeFlatDto.serializer()), "$dir/recipe-flat.json")
            strict(ListSerializer(KeywordDto.serializer()), "$dir/keywords.json")
            strict(ListSerializer(MealTypeDto.serializer()), "$dir/meal-types.json")
            strict(ListSerializer(MealPlanDto.serializer()), "$dir/meal-plan.json")
            strict(ListSerializer(RecipeBookDto.serializer()), "$dir/recipe-books.json")
            strict(ListSerializer(RecipeBookEntryDto.serializer()), "$dir/recipe-book-entries.json")
            strict(UserPreferenceDto.serializer(), "$dir/user-preference.json")
        }
        strict(ListSerializer(CookLogDto.serializer()), "cook-log.json")
        strict(ListSerializer(ViewLogDto.serializer()), "view-log.json")
    }

    private fun <T> strict(serializer: KSerializer<T>, file: String): T = strictJson.decodeFromJsonElement(serializer, raw(file))

    @Test
    fun filesHaveTheSameKeysAsTheRecordedTandoorResponses() {
        val recipeFixture = fixtureBody("recipe-svamppasta").jsonObject
        val dirs = DemoLanguage.entries.map { it.tag }
        for (dir in dirs) {
            for (id in content.recipeIds) {
                assertSameShape("$dir/recipe-$id.json", recipeFixture, raw("$dir/recipe-$id.json").jsonObject)
            }
            assertSameShape("$dir/recipe-overviews", fixtureResults("recipe-list-default")[0], firstOf("$dir/recipe-overviews.json"))
            assertSameShape("$dir/recipe-flat", fixtureResults("recipe-flat")[0], firstOf("$dir/recipe-flat.json"))
            assertSameShape("$dir/keywords", fixtureResults("keyword-list")[0], firstOf("$dir/keywords.json"))
            assertSameShape("$dir/meal-types", fixtureResults("meal-type-list")[0], firstOf("$dir/meal-types.json"))
            assertSameShape("$dir/recipe-books", fixtureResults("recipe-book-list")[0], firstOf("$dir/recipe-books.json"))
            assertSameShape(
                "$dir/recipe-book-entries",
                fixtureResults("recipe-book-entry-list")[0],
                firstOf("$dir/recipe-book-entries.json"),
            )
            assertSameShape("$dir/user-preference", fixtureResults("user-preference")[0], raw("$dir/user-preference.json"))
            val plan = raw("$dir/meal-plan.json").jsonArray.map { it.jsonObject }
            val fixturePlan = fixtureResults("meal-plan-list").map { it.jsonObject }
            // With a recipe, and the title-only entry without `recipe_name` (FACTS "Fas 1 g").
            assertSameShape("$dir/meal-plan", fixturePlan.first { it["recipe"] !is JsonNull }, plan.first { it["recipe"] !is JsonNull })
            assertSameShape("$dir/meal-plan title", fixturePlan.first { it["recipe"] is JsonNull }, plan.first { it["recipe"] is JsonNull })
        }
        assertSameShape("cook-log", fixtureResults("cook-log-list-recipe")[0], firstOf("cook-log.json"))
        assertSameShape("view-log", fixtureBody("view-log-create"), firstOf("view-log.json"))
    }

    @Test
    fun bothLanguagesHaveTheSameStructure() {
        val files = content.recipeIds.map { "recipe-$it.json" } + listOf(
            "recipe-overviews.json",
            "recipe-flat.json",
            "keywords.json",
            "meal-types.json",
            "meal-plan.json",
            "recipe-books.json",
            "recipe-book-entries.json",
            "user-preference.json",
        )
        for (file in files) {
            assertEquals(file, skeleton(byId(raw("sv/$file")), null), skeleton(byId(raw("en/$file")), null))
        }
    }

    @Test
    fun localesFallBackToEnglish() {
        assertEquals(DemoLanguage.SWEDISH, DemoLanguage.of(Locale.forLanguageTag("sv-SE")))
        assertEquals(DemoLanguage.SWEDISH, DemoLanguage.of(Locale.forLanguageTag("sv-FI")))
        assertEquals(DemoLanguage.ENGLISH, DemoLanguage.of(Locale.forLanguageTag("en-GB")))
        assertEquals(DemoLanguage.ENGLISH, DemoLanguage.of(Locale.GERMANY))
        assertEquals("Middag", content.userPreferences(SWEDISH).defaultMealType?.name)
        assertEquals("Dinner", content.userPreferences(Locale.GERMANY).defaultMealType?.name)
    }

    @Test
    fun datesMoveWithTodayAndAreDeterministic() {
        assertEquals(content.recipes(SWEDISH, today), content.recipes(SWEDISH, today))
        assertEquals(content.mealPlan(SWEDISH, today), content.mealPlan(SWEDISH, today))
        val reference = content.recipe(5, SWEDISH, DemoContent.REFERENCE_DATE)!!
        val shifted = content.recipe(5, SWEDISH, today)!!
        assertEquals("2000-01-14T09:15:00Z", reference.createdAt)
        assertEquals("2026-10-10T09:15:00Z", shifted.createdAt)
        // Only the date changes; the time and offset stay.
        val days = DemoContent.daysSinceReference(today)
        assertEquals(9766, days)
        assertEquals(
            JsonPrimitive("2026-10-11T18:00:00+02:00"),
            DemoContent.shiftDates(JsonObject(mapOf("from_date" to JsonPrimitive("2000-01-15T18:00:00+02:00"))), days)
                .jsonObject["from_date"],
        )
        // Names that merely look like dates are left alone.
        val name = JsonObject(mapOf("name" to JsonPrimitive("2000-01-15")))
        assertEquals(name, DemoContent.shiftDates(name, 3))
    }

    @Test
    fun mealPlanCoversTodayTomorrowAndTheComingWeek() {
        for (locale in languages) {
            val plan = content.mealPlan(locale, today)
            val days = plan.map { LocalDate.parse(it.fromDate!!.substring(0, 10)) }.toSet()
            for (offset in 0..7) assertTrue("no meal on day +$offset", today.plus(offset, DateTimeUnit.DAY) in days)
            assertTrue(today.minus(1, DateTimeUnit.DAY) in days)
            val todays = plan.filter { it.fromDate!!.startsWith(today.toString()) }
            assertTrue("a full day today", todays.size >= 3)
            assertTrue(plan.any { it.recipe == null && it.title.isNotBlank() && it.recipeName == null })
            assertTrue(plan.any { it.fromDate!!.substring(0, 10) != it.toDate!!.substring(0, 10) })
            val mealTypeIds = content.mealTypes(locale).map { it.id }.toSet()
            for (entry in plan) {
                assertTrue(entry.mealType?.id in mealTypeIds)
                entry.recipe?.let { assertTrue(it.id in content.recipeIds) }
            }
        }
    }

    @Test
    fun cookLogFillsOftenCookedNotLatelyAndRatings() {
        val log = content.cookLog(today)
        val now = TandoorDates.parseInstantOrNull("${today}T00:00:00Z")!!
        assertTrue(log.all { it.recipe in content.recipeIds })
        assertTrue(log.all { TandoorDates.parseInstantOrNull(it.createdAt)!! < now })
        assertTrue(log.mapNotNull { it.rating }.all { it in 1..5 })
        val timesCooked = log.groupingBy { it.recipe!! }.eachCount()
        assertTrue("often cooked", timesCooked.values.count { it >= 5 } >= 4)
        val lastCooked = log.groupBy { it.recipe!! }.mapValues { (_, entries) -> entries.maxOf { it.createdAt!! } }
        val longAgo = "${today.minus(45, DateTimeUnit.DAY)}"
        assertTrue("not cooked lately", lastCooked.values.count { it < longAgo } >= 3)
        // The detail's rating is the average of the demo user's ratings, as in Tandoor.
        for (recipe in content.recipes(SWEDISH, today)) {
            val ratings = log.filter { it.recipe == recipe.id }.mapNotNull { it.rating }
            if (ratings.isEmpty()) assertNull(recipe.rating) else assertEquals(ratings.average(), recipe.rating!!, 0.01)
            assertNull("last_cooked is always null in 2.6.13", recipe.lastCooked)
        }
        assertTrue(content.viewLog(today).all { it.recipe in content.recipeIds })
        assertTrue(content.pinnedRecipeIds.all { it in content.recipeIds })
    }

    @Test
    fun newRecipesAreTheOnesCreatedThisWeek() {
        val overviews = content.recipeOverviews(SWEDISH, today)
        val weekAgo = today.minus(7, DateTimeUnit.DAY).toString()
        val cooked = content.cookLog(today).map { it.recipe }.toSet()
        assertTrue(overviews.count { it.isNew } >= 3)
        for (overview in overviews) {
            assertEquals(overview.name, overview.isNew, overview.createdAt!! > weekAgo)
            if (overview.isNew) assertFalse("a new recipe has not been cooked yet", overview.id in cooked)
        }
        // The list is sorted by name with Swedish collation (Å, Ä, Ö last).
        val names = overviews.map { it.name }
        val collator = Collator.getInstance(SWEDISH)
        assertEquals(names.sortedWith(collator), names)
    }

    @Test
    fun recipesShowWhatTheRecipeScreensNeed() {
        for (locale in languages) {
            val recipes = content.recipes(locale, today)
            val ingredients = recipes.flatMap { r -> r.steps.flatMap { it.ingredients } }
            assertTrue("headers", ingredients.any { it.isHeader && !it.note.isNullOrBlank() })
            assertTrue("fractions", ingredients.any { !it.isHeader && it.amount % 1.0 != 0.0 })
            assertTrue("notes", ingredients.any { !it.isHeader && !it.note.isNullOrBlank() })
            assertTrue("no amount", ingredients.any { it.noAmount })
            assertTrue("unit plurals", ingredients.any { it.unit?.pluralName?.let { p -> p != it.unit?.name } == true })
            assertTrue("food plurals", ingredients.any { it.food?.pluralName != null })
            assertTrue("scale templates", recipes.sumOf { r -> r.steps.count { "{{ scale(" in it.instruction } } >= 2)
            assertTrue("ingredient templates", recipes.any { r -> r.steps.any { "{{ ingredients[" in it.instruction } })
            assertTrue("nutrition", recipes.count { it.hasProperties || it.hasLegacyNutrition } >= 2)
            assertTrue("keywords", recipes.all { it.keywords.isNotEmpty() })
            assertTrue(recipes.flatMap { it.keywords }.any { it.parent != null })
            for (recipe in recipes) {
                assertTrue(recipe.servings > 0 && recipe.servingsText.isNotBlank())
                assertTrue(recipe.workingTime + recipe.waitingTime > 0)
                for (step in recipe.steps) {
                    assertFalse(step.hasBrokenTemplate)
                    assertTrue(step.instructionsMarkdown.startsWith("<p>"))
                    TEMPLATE.findAll(step.instruction).forEach { match ->
                        INGREDIENT_REF.find(match.value)?.let { ref ->
                            val row = step.ingredients.getOrNull(ref.groupValues[1].toInt())
                            assertNotNull("${recipe.name}: ${match.value}", row)
                            assertFalse("${recipe.name}: ${match.value} points at a header", row!!.isHeader)
                        }
                    }
                }
            }
        }
        assertEquals(true, content.userPreferences(SWEDISH).useFractions)
    }

    @Test
    fun timerDetectorFindsATimerInEveryTimedStep() {
        val detector = TimerDetector()
        for (locale in languages) {
            val language = DemoLanguage.of(locale).tag
            var longest = 0L
            for (recipe in content.recipes(locale, today)) {
                assertTrue("${recipe.name} has no timed step", recipe.steps.any { it.time > 0 })
                for (step in recipe.steps.filter { it.time > 0 }) {
                    val timers = detector.detect(TEMPLATE.replace(step.instruction, "X"))
                    assertTrue("${recipe.name} / ${step.name}: no timer in \"${step.instruction}\"", timers.isNotEmpty())
                    assertTrue(timers.all { it.languageTag == language })
                    longest = maxOf(longest, timers.maxOf { it.maxSeconds })
                }
            }
            // "1 tim och 15 minuter" / "1 hour and 15 minutes" in the roast chicken.
            assertEquals(75L * 60, longest)
        }
    }

    @Test
    fun imagesExistAreSmallAndReferenced() {
        val imagesDir = File(assetsDir, "images")
        val files = imagesDir.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
        val referenced = (DemoLanguage.entries.map { Locale.forLanguageTag(it.tag) }).flatMap { locale ->
            content.recipes(locale, today).mapNotNull { it.image } + content.recipeOverviews(locale, today).mapNotNull { it.image }
        }.toSet()
        assertEquals(content.recipeIds.size, content.recipes(SWEDISH, today).count { it.image != null })
        assertEquals(content.imageUris.toSet(), referenced)
        for (uri in referenced) {
            val path = DemoContent.imageAssetPath(uri)
            assertNotNull(uri, path)
            val file = File(assetsDir, path!!)
            assertTrue("$uri does not exist", file.isFile)
            val bytes = file.readBytes()
            assertTrue("${file.name} is ${bytes.size} bytes", bytes.size <= 70 * 1024)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
            assertTrue("${file.name} is wider than 640 px", webpWidth(bytes) in 1..640)
        }
        assertEquals("unreferenced images", referenced.map { it.substringAfterLast('/') }.toSet(), files.keys)
        val total = assetsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("demo assets are $total bytes", total <= 1_200_000)
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

    /**
     * Everything but the words: strings become "s" (plural names may be missing in one language),
     * numbers, booleans, ids and dates stay. Image URIs and dates stay as they are.
     */
    private fun skeleton(json: JsonElement, key: String?): JsonElement = when (json) {
        is JsonObject -> JsonObject(json.mapValues { (k, v) -> skeleton(v, k) })

        is JsonArray -> JsonArray(json.map { skeleton(it, key) })

        is JsonNull -> if (key == "plural_name") JsonPrimitive("s") else json

        is JsonPrimitive -> when {
            !json.isString -> json
            key in KEEP_STRINGS -> json
            else -> JsonPrimitive("s")
        }
    }

    /** Lists sorted by name (overviews, flat, keywords) differ in order between languages: compare them by id. */
    private fun byId(json: JsonElement): JsonElement = if (json is JsonArray && json.all { it is JsonObject && "id" in it.jsonObject }) {
        JsonArray(json.sortedBy { it.jsonObject.getValue("id").jsonPrimitive.long })
    } else {
        json
    }

    /** Width of a WebP image from its first chunk (lossy VP8, lossless VP8L or extended VP8X). */
    private fun webpWidth(bytes: ByteArray): Int {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> (u8(26) or (u8(27) shl 8)) and 0x3FFF
            "VP8L" -> 1 + (u8(21) or ((u8(22) and 0x3F) shl 8))
            "VP8X" -> 1 + (u8(24) or (u8(25) shl 8) or (u8(26) shl 16))
            else -> -1
        }
    }

    private companion object {
        val SWEDISH: Locale = Locale.forLanguageTag("sv-SE")
        val TEMPLATE = Regex("\\{\\{.*?\\}\\}")
        val INGREDIENT_REF = Regex("ingredients\\[(\\d+)\\]")
        val KEEP_STRINGS = setOf("image", "created_at", "updated_at", "from_date", "to_date", "time", "color", "base_unit")
    }
}
