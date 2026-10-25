package casa.moller.tandem.core.network.dto

import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.network.testing.Fixture
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Phase 1 DTOs read from the recorded 2.6.13 responses (FACTS "Fas 1"). */
class Phase1DtoTest {
    private fun <T> decode(name: String, serializer: KSerializer<T>): T =
        TandoorJson.decodeFromString(serializer, Fixture.load(name).bodyText)

    @Test
    fun flatIndexHasRelativeImages() {
        val flat = decode("fas1/recipe-flat", ListSerializer(RecipeFlatDto.serializer()))
        assertEquals(12, flat.size)
        val pasta = flat.single { it.id == 2L }
        assertEquals("Krämig svamppasta", pasta.name)
        assertTrue(pasta.image!!.startsWith("/media/recipes/"))
        assertNull(flat.single { it.id == 8L }.image)
    }

    @Test
    fun recipeListPage() {
        val page = decode("fas1/recipe-list-page-1", PageDto.serializer(RecipeOverviewDto.serializer()))
        assertEquals(12, page.count)
        assertEquals("https://tandoor.example.com/api/recipe/?page=2&page_size=4", page.next)
        assertNull(page.previous)
        assertNull("recipe/ has no timestamp", page.timestamp)
        assertEquals(4, page.results.size)
        val pasta = page.results.single { it.id == 2L }
        assertEquals(listOf(2L, 3L), pasta.keywords.map { it.id })
        assertEquals("Pasta", pasta.keywords.first().label)
        assertEquals(20, pasta.workingTime)
        assertEquals(10, pasta.waitingTime)
        assertEquals(4, pasta.servings)
        assertEquals("portioner", pasta.servingsText)
        assertTrue(pasta.isNew)
        assertFalse(pasta.isPrivate)
        assertNull(pasta.lastCooked)
        assertNull(pasta.recent)
        assertEquals("2000-01-15T00:00:00.000000+02:00", pasta.updatedAt)
    }

    @Test
    fun ratingAndRecentOnlyWithTheirParameters() {
        val rated = decode("fas1/recipe-list-sort-rating", PageDto.serializer(RecipeOverviewDto.serializer()))
        assertTrue(rated.results.any { it.rating != null })
        val recent = decode("fas1/recipe-list-num-recent", PageDto.serializer(RecipeOverviewDto.serializer()))
        assertTrue(recent.results.all { it.recent != null })
        assertTrue(recent.results.any { it.recent == "0" })
    }

    @Test
    fun recipeDetail() {
        val recipe = decode("fas1/recipe-svamppasta", RecipeDto.serializer())
        assertEquals(2L, recipe.id)
        assertEquals(4, recipe.servings)
        assertEquals(4.0, recipe.rating!!, 0.0)
        assertEquals("https://exempel.se/recept/kramig-svamppasta", recipe.sourceUrl)
        assertEquals("Huvudrätt > Pasta", recipe.keywords.first().fullName)
        assertEquals(1L, recipe.keywords.first().parent)
        assertFalse(recipe.hasProperties)
        assertFalse(recipe.hasLegacyNutrition)
        assertEquals(JsonObject(emptyMap()), recipe.foodProperties)

        val step = recipe.steps.first()
        assertEquals("Förbered", step.name)
        assertEquals(10, step.time)
        assertTrue(step.showAsHeader)
        assertTrue(step.showIngredientsTable)
        assertFalse(step.hasBrokenTemplate)
        assertTrue(step.instruction.contains("{{ ingredients[0].food }}"))
        assertTrue(step.instructionsMarkdown.contains("<plural-name"))
        assertNull(step.stepRecipe)
        assertNull(step.stepRecipeName())

        val mushrooms = step.ingredients.first()
        assertEquals("champinjon", mushrooms.food?.name)
        assertEquals("champinjoner", mushrooms.food?.pluralName)
        assertEquals(false, mushrooms.food?.onHand)
        assertEquals("g", mushrooms.unit?.name)
        assertEquals("g", mushrooms.unit?.baseUnit)
        assertEquals(250.0, mushrooms.amount, 0.0)
        assertEquals("gärna blandade svampar", mushrooms.note)
        assertEquals(
            listOf(IngredientConversionDto("champinjon", "g", 250.0), IngredientConversionDto("champinjon", "kg", 0.25)),
            mushrooms.conversionList(),
        )
        // Without a unit there are no conversions.
        assertEquals(emptyList<IngredientConversionDto>(), step.ingredients[2].conversionList())
    }

    @Test
    fun everyRecipeFixtureDecodes() {
        val slugs = listOf(
            "pyttipanna", "svamppasta", "pannkakor", "kanelbullar", "kottbullar", "shakshuka",
            "artsoppa", "gronsakssoppa", "smorgastarta", "appelkaka", "rarakor",
        )
        for (slug in slugs) {
            val recipe = decode("fas1/recipe-$slug", RecipeDto.serializer())
            assertTrue(slug, recipe.id != null && recipe.name.isNotEmpty())
            for (ingredient in recipe.steps.flatMap { it.ingredients }) {
                if (ingredient.isHeader) {
                    assertNull("$slug: a header has no food", ingredient.food)
                }
            }
        }
        assertTrue(decode("fas1/recipe-artsoppa", RecipeDto.serializer()).isPrivate)
    }

    /**
     * A step's file is a `UserFileView` (FACTS "Fas 1 m", fixture `fas1/recipe-step-file`): only an
     * image has a `preview`, an absolute URL built from the proxy's Host; any other file has "".
     */
    @Test
    fun stepFilePreviewIsTheImageLinkOnly() {
        val recipe = decode("fas1/recipe-step-file", RecipeDto.serializer())
        val (image, pdf, none) = recipe.steps
        val preview = image.filePreview()
        assertTrue("$preview", preview!!.startsWith("https://tandoor.example.com/media/"))
        assertTrue("$preview", preview.endsWith(".png"))
        assertNull("a PDF has preview \"\"", pdf.filePreview())
        assertNull("no file", none.filePreview())
    }

    @Test
    fun stepFilePreviewKeepsRelativeAndAbsoluteValuesAndIgnoresOddShapes() {
        fun preview(file: String): String? =
            TandoorJson.decodeFromString(StepDto.serializer(), """{"id": 1, "instruction": "", "file": $file}""").filePreview()

        // As Tandoor sends it: an absolute URL (build_absolute_uri) ...
        assertEquals(
            "https://tandoor.example.com/media/files/a.png",
            preview(
                """{"id": 1, "name": "Bild", "file_download": "https://tandoor.example.com/api/download-file/1/", """ +
                    """"preview": "https://tandoor.example.com/media/files/a.png"}""",
            ),
        )
        // ... or a relative path (resolved later against the account's base URL by MediaUrls).
        assertEquals("/media/files/a.png", preview("""{"id": 1, "preview": "/media/files/a.png"}"""))
        assertNull(preview("""{"id": 2, "name": "Receptblad", "preview": ""}"""))
        assertNull(preview("""{"id": 2, "preview": "   "}"""))
        assertNull(preview("""{"id": 2, "preview": null}"""))
        assertNull(preview("""{"id": 2, "preview": 5}"""))
        assertNull(preview("""{"id": 2}"""))
        assertNull(preview("null"))
        assertNull(preview("\"files/a.png\""))
    }

    @Test
    fun brokenTemplatesAndSubRecipesAreRead() {
        val step = TandoorJson.decodeFromString(
            StepDto.serializer(),
            """
            {"id": 9, "instruction": "{{ scale( }}",
             "instructions_markdown": "Could not parse template code. Error: Template Syntax broken",
             "step_recipe": 4, "step_recipe_data": {"id": 4, "name": "Kanelbullar", "steps": []}}
            """.trimIndent(),
        )
        assertTrue(step.hasBrokenTemplate)
        assertEquals(4L, step.stepRecipe)
        assertEquals("Kanelbullar", step.stepRecipeName())
    }

    @Test
    fun conversionsOfAnUnexpectedShapeAreSkipped() {
        val ingredient = TandoorJson.decodeFromString(
            IngredientDto.serializer(),
            """{"id": 1, "amount": 2, "conversions": [{"food": "mjöl", "unit": "g", "amount": 120}, "odd", 5, {"amount": "x"}]}""",
        )
        assertEquals(listOf(IngredientConversionDto("mjöl", "g", 120.0)), ingredient.conversionList())
        val notAList = TandoorJson.decodeFromString(IngredientDto.serializer(), """{"conversions": {"a": 1}}""")
        assertEquals(emptyList<IngredientConversionDto>(), notAList.conversionList())
    }

    @Test
    fun mealPlan() {
        val page = decode("fas1/meal-plan-list", PageDto.serializer(MealPlanDto.serializer()))
        assertEquals(3, page.count)
        assertEquals("2000-01-14T00:00:00.000000+00:00", page.timestamp)
        val first = page.results.first()
        assertEquals(2L, first.recipe?.id)
        assertEquals(4.0, first.servings, 0.0)
        assertEquals("2000-01-15T18:00:00+02:00", first.fromDate)
        assertEquals("Middag", first.mealType?.name)
        assertEquals("18:00:00", first.mealType?.time)
        assertEquals("Krämig svamppasta", first.recipeName)

        val titleOnly = decode("fas1/meal-plan-create-title-only", MealPlanDto.serializer())
        assertNull(titleOnly.recipe)
        assertNull("recipe_name is missing without a recipe", titleOnly.recipeName)
        assertTrue(titleOnly.title.isNotEmpty())

        val types = decode("fas1/meal-type-list", PageDto.serializer(MealTypeDto.serializer()))
        assertEquals(listOf("Middag", "Lunch"), types.results.map { it.name })
        assertNull(types.results[1].time)
        assertNull(types.results[1].color)
    }

    @Test
    fun preferencesAndSpace() {
        val prefs = decode("fas1/user-preference", ListSerializer(UserPreferenceDto.serializer())).single()
        assertEquals(1L, prefs.user?.id)
        assertFalse(prefs.useFractions)
        assertEquals(2, prefs.ingredientDecimals)
        assertEquals("g", prefs.defaultUnit)
        assertTrue(prefs.showStepIngredients)
        assertEquals(4.0, prefs.defaultDelay, 0.0)
        assertEquals(7, prefs.shoppingRecentDays)
        assertEquals("TANDOOR", prefs.theme)
        assertNull(prefs.defaultMealType)

        val space = decode("fas1/space-current", SpaceDto.serializer())
        assertEquals(true, space.aiEnabled)
        assertEquals(false, space.householdSetupCompleted)
        assertEquals(12, space.recipeCount)
        assertEquals(2, space.userCount)
    }

    @Test
    fun logs() {
        val logs = decode("fas1/cook-log-list-recipe", PageDto.serializer(CookLogDto.serializer()))
        assertEquals(listOf(5, 4, 5), logs.results.map { it.rating })
        assertEquals(10L, logs.results.first().recipe)
        assertEquals("contract-admin", logs.results.first().createdBy?.username)

        val view = decode("fas1/view-log-create", ViewLogDto.serializer())
        val again = decode("fas1/view-log-create-again", ViewLogDto.serializer())
        assertEquals("the server merges views within 5 minutes", view.id, again.id)
        assertEquals(2L, view.recipe)
    }

    @Test
    fun booksAndTaxonomy() {
        val entries = decode("fas1/recipe-book-entry-list", PageDto.serializer(RecipeBookEntryDto.serializer()))
        assertEquals(listOf(2L, 10L), entries.results.map { it.recipe })
        assertEquals("Favoriter", entries.results.first().bookContent?.name)
        assertEquals("Krämig svamppasta", entries.results.first().recipeContent?.name)

        val keywords = decode("fas1/keyword-list", PageDto.serializer(KeywordDto.serializer()))
        assertEquals(7, keywords.results.size)
        assertEquals(1, keywords.results.single { it.name == "Huvudrätt" }.numchild)

        val units = decode("fas1/unit-list", PageDto.serializer(UnitDto.serializer()))
        assertEquals("burkar", units.results.single { it.name == "burk" }.pluralName)
        val foods = decode("fas1/food-list", PageDto.serializer(FoodDto.serializer()))
        assertTrue(foods.results.isNotEmpty())
    }

    @Test
    fun shareLinkFromTheSchema() {
        val link = TandoorJson.decodeFromString(
            ShareLinkDto.serializer(),
            """{"pk": 3, "share": "0b8e0b9e-1111-4222-8333-944444444444", "link": "https://tandoor.example.com/view/recipe/3?share=x"}""",
        )
        assertEquals(3L, link.pk)
        assertTrue(link.link.startsWith("https://"))
    }

    @Test
    fun requestBodiesLeaveOutNulls() {
        val view = TandoorJson.encodeToJsonElement(ViewLogCreateRequestDto.serializer(), ViewLogCreateRequestDto(recipe = 2)).jsonObject
        assertEquals(setOf("recipe"), view.keys)
        val cooked = TandoorJson.encodeToJsonElement(
            CookLogCreateRequestDto.serializer(),
            CookLogCreateRequestDto(recipe = 10, servings = 8, rating = 5),
        ).jsonObject
        assertEquals(setOf("recipe", "servings", "rating"), cooked.keys)
    }
}
