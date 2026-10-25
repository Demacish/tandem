package casa.moller.tandem.contract

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Establishes and records the server facts Fas 1 ("läsa recept", release 0.1.0) depends on:
 * recipe index and search (plan §3.3, §3.5), recipe details with templates and conversions,
 * images, cook/view logs, the meal plan for the Today tab, and what bumps `Recipe.updated_at`.
 *
 * Seeds a throwaway Tandoor through the API ([Fas1Seed]) as the contract superuser, using a
 * token that contract.yml creates after Phase0ContractTest (which needs an account without
 * tokens and uses up the login throttle). Every request claims `Host: tandoor.example.com` and
 * `X-Forwarded-Proto: https`, as behind a TLS reverse proxy, so URLs in the recordings look
 * like a real deployment's.
 *
 * Recordings go to `build/contract-recordings/<tag>/fas1/<name>.json` with dates shifted so that
 * the run day is 2000-01-15 ([Sanitizer]); they are committed to `api-schema/fixtures/2.6.13/fas1/`.
 * Checks are "soft": every fact is recorded first and mismatches against the 2.6.13 baseline
 * are reported together by the last step, so one run shows every difference of a new version.
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase1ContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val http = TandoorHttp(env.baseUrl, sanitizer, env.fas1RecordDir, numbered = false, defaultHeaders = proxyHeaders)

    /** Same server without the proxy headers (control calls only; recorded under distinct names). */
    private val directHttp = TandoorHttp(env.baseUrl, sanitizer, env.fas1RecordDir, numbered = false)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val unitIds = LinkedHashMap<String, Long>()
    private val foodIds = LinkedHashMap<String, Long>()
    private val keywordIds = LinkedHashMap<String, Long>()
    private val mealTypeIds = LinkedHashMap<String, Long>()
    private val recipeIds = LinkedHashMap<String, Long>()
    private var temporaryRecipeId: Long? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        assertTrue(env.readerToken.isNotBlank(), "TANDOOR_READER_TOKEN must be set")
        // Fixed fake numbering: ...0001 = the contract user's token, ...0002 = the other member's.
        sanitizer.fakeTokenFor(env.fas1Token)
        sanitizer.fakeTokenFor(env.readerToken)
        env.fas1RecordDir.deleteRecursively()
        env.fas1RecordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
        facts["publicHost"] = ContractEnv.PUBLIC_HOST
    }

    @AfterAll
    fun writeFacts() {
        facts["mismatches"] = mismatches
        facts.write(File(env.fas1RecordDir, "observed-facts.json"), sanitizer)
    }

    // --- a. Seeding: units, foods, conversions, keywords, meal types ----------------------------

    @Test
    @Order(10)
    fun seedUnits() {
        for (unit in Fas1Seed.units) {
            val showcase = unit.name == "burk"
            val body = buildJsonObject {
                put("name", unit.name)
                put("plural_name", unit.plural?.let { JsonPrimitive(it) } ?: JsonNull)
                put("base_unit", unit.baseUnit?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            val created = api("seed-unit-create-${unit.name}", "POST", "/api/unit/", body, record = showcase)
            assertEquals(201, created.status, created.toString())
            val id = created.jsonObject.longAt("id")!!
            unitIds[unit.name] = id
            if (showcase) {
                // Observed in 2.6.13 [K: UnitSerializer.create]: plural_name is overwritten with the name.
                observe("unit.create.sentPluralName", unit.plural)
                observe("unit.create.returnedPluralName", created.jsonObject.stringAt("plural_name"))
                observe("unit.create.returnedBaseUnit", created.jsonObject.stringAt("base_unit"))
            }
            if (unit.plural != null || unit.baseUnit != null) {
                val patch = api("seed-unit-patch-${unit.name}", "PATCH", "/api/unit/$id/", body, record = showcase)
                assertEquals(200, patch.status, patch.toString())
                if (showcase) observe("unit.patch.returnedPluralName", patch.jsonObject.stringAt("plural_name"))
                verify("unit.${unit.name}.pluralAfterPatch", patch.jsonObject.stringAt("plural_name"), unit.plural)
                verify("unit.${unit.name}.baseUnitAfterPatch", patch.jsonObject.stringAt("base_unit"), unit.baseUnit)
            }
        }
    }

    @Test
    @Order(11)
    fun seedFoodsAndConversions() {
        for (food in Fas1Seed.foods) {
            val showcase = food.name == "champinjon"
            val body = buildJsonObject {
                put("name", food.name)
                put("plural_name", food.plural?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            val created = api("seed-food-create", "POST", "/api/food/", body, record = showcase)
            assertEquals(201, created.status, created.toString())
            foodIds[food.name] = created.jsonObject.longAt("id")!!
            verify("food.${food.name}.pluralAfterCreate", created.jsonObject.stringAt("plural_name"), food.plural)
        }
        for (conversion in Fas1Seed.conversions) {
            val body = buildJsonObject {
                put("base_amount", conversion.baseAmount)
                put("base_unit", buildJsonObject { put("name", conversion.baseUnit) })
                put("converted_amount", conversion.convertedAmount)
                put("converted_unit", buildJsonObject { put("name", conversion.convertedUnit) })
                conversion.food?.let { name -> put("food", buildJsonObject { put("name", name) }) }
            }
            if (conversion.food == null) {
                // UnitConversionSerializer.create calls validated_data.get('food', {}).get('name'): an explicit
                // null crashes it [K]; a generic conversion must leave the key out.
                val withNull = JsonObject(body + ("food" to JsonNull))
                val crash = api("seed-unit-conversion-create-food-null", "POST", "/api/unit-conversion/", withNull)
                verify("unitConversion.explicitNullFood.status", crash.status, 500)
            }
            val name = if (conversion.food == null) "seed-unit-conversion-create" else "seed-unit-conversion-create-food"
            val created = api(name, "POST", "/api/unit-conversion/", body)
            assertEquals(201, created.status, created.toString())
            observe("unitConversion.${conversion.food ?: "generic"}.name", created.jsonObject.stringAt("name"))
        }
    }

    @Test
    @Order(12)
    fun seedKeywordsAndMealTypes() {
        for (keyword in Fas1Seed.keywords) {
            val body = buildJsonObject { put("name", keyword) }
            val created = api("seed-keyword-create", "POST", "/api/keyword/", body, record = keyword == Fas1Seed.CHILD_KEYWORD)
            assertEquals(201, created.status, created.toString())
            keywordIds[keyword] = created.jsonObject.longAt("id")!!
        }
        val child = keywordIds.getValue(Fas1Seed.CHILD_KEYWORD)
        val parent = keywordIds.getValue(Fas1Seed.PARENT_KEYWORD)
        // The keyword `parent` field is read-only; the tree is changed with PUT .../move/{parent}/.
        val move = api("seed-keyword-move", "PUT", "/api/keyword/$child/move/$parent/", JsonObject(emptyMap()))
        verify("keyword.move.status", move.status, 200)

        val dinner = api(
            "seed-meal-type-create",
            "POST",
            "/api/meal-type/",
            buildJsonObject {
                put("name", Fas1Seed.DINNER)
                put("time", Fas1Seed.DINNER_TIME)
                put("color", "#C2185B")
                put("order", 0)
            },
        )
        assertEquals(201, dinner.status, dinner.toString())
        mealTypeIds[Fas1Seed.DINNER] = dinner.jsonObject.longAt("id")!!
        val lunch = api(
            "seed-meal-type-create-lunch",
            "POST",
            "/api/meal-type/",
            buildJsonObject {
                put("name", Fas1Seed.LUNCH)
                put("order", 1)
            },
            record = false,
        )
        assertEquals(201, lunch.status, lunch.toString())
        mealTypeIds[Fas1Seed.LUNCH] = lunch.jsonObject.longAt("id")!!
        observe("mealType.lunch.time", lunch.jsonObject["time"])
    }

    // --- b. Seeding: recipes and images ---------------------------------------------------------

    @Test
    @Order(20)
    fun seedRecipes() {
        for (recipe in Fas1Seed.recipes) {
            val created = api("seed-recipe-create", "POST", "/api/recipe/", recipe.toJson(), record = recipe.slug == "svamppasta")
            assertEquals(201, created.status, created.toString())
            recipeIds[recipe.slug] = created.jsonObject.longAt("id")!!
            if (recipe.slug == "smorgastarta") {
                val dill = ingredientsOf(created.jsonObject).firstOrNull { it.objAt("food")?.stringAt("name") == "dill" }
                verify("recipe.create.amountStringWithComma", dill?.get("amount"), JsonPrimitive(1.5))
            }
        }
        val temporary = api("seed-recipe-create-temporary", "POST", "/api/recipe/", Fas1Seed.temporary.toJson(), record = false)
        assertEquals(201, temporary.status, temporary.toString())
        temporaryRecipeId = temporary.jsonObject.longAt("id")
        facts["recipeIds"] = recipeIds
    }

    @Test
    @Order(21)
    fun uploadImages() {
        var first = true
        for (recipe in Fas1Seed.recipes.filter { it.image }) {
            val id = recipeIds.getValue(recipe.slug)
            val png = tinyPng(if (recipe.slug == "svamppasta") 0x8D6E63 else 0xC0392B)
            val fileName = "tandem-${recipe.slug}.png"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", fileName, png.toRequestBody(PNG))
                .build()
            val recordBody = buildJsonObject { put("image", "<multipart file $fileName, image/png, ${png.size} bytes>") }
            val upload = http.call(
                "recipe-image-put",
                "PUT",
                "/api/recipe/$id/image/",
                bearer = env.fas1Token,
                body = body,
                recordBody = recordBody,
                record = first,
            )
            assertEquals(200, upload.status, upload.toString())
            if (first) {
                observe("image.put.responseFields", upload.jsonObject.keys.sorted())
                observe("image.put.image", upload.jsonObject.stringAt("image"))
            }
            first = false
        }
    }

    // --- c. Cook log and view log ---------------------------------------------------------------

    @Test
    @Order(30)
    fun cookAndViewLogs() {
        val apple = recipeIds.getValue("appelkaka")
        val pasta = recipeIds.getValue("svamppasta")
        val pancakes = recipeIds.getValue("pannkakor")
        val buns = recipeIds.getValue("kanelbullar")
        listOf(5, 4, 5).forEachIndexed { index, rating ->
            val ex = api("cook-log-create", "POST", "/api/cook-log/", cookLog(apple, 8, rating, "Gott!"), record = index == 0)
            assertEquals(201, ex.status, ex.toString())
            if (index == 0) facts["cookLog.create.fieldTypes"] = Facts.fieldTypes(ex.jsonObject)
        }
        listOf(4, null).forEach { rating ->
            val ex = api("cook-log-create-pasta", "POST", "/api/cook-log/", cookLog(pasta, 4, rating, null), record = false)
            assertEquals(201, ex.status, ex.toString())
        }
        // created_at is writable: a cook log can be back-dated (used for "Inte lagat på länge").
        val longAgo = ZonedDateTime.of(today.minusDays(90), LocalTime.of(18, 0), zone)
        val backdated = api(
            "cook-log-create-backdated",
            "POST",
            "/api/cook-log/",
            buildJsonObject {
                put("recipe", pancakes)
                put("servings", 2)
                put("rating", 3)
                put("created_at", longAgo.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            },
        )
        assertEquals(201, backdated.status, backdated.toString())
        verify(
            "cookLog.backdated.createdAtKeptDate",
            backdated.jsonObject.stringAt("created_at")?.take(10),
            today.minusDays(90).toString(),
        )

        val list = api("cook-log-list-recipe", "GET", "/api/cook-log/?recipe=$apple")
        assertEquals(200, list.status, list.toString())
        verify("cookLog.listByRecipe.count", list.jsonObject.longAt("count"), 3)
        observe("cookLog.list.keys", list.jsonObject.keys.toList())

        val view = api("view-log-create", "POST", "/api/view-log/", buildJsonObject { put("recipe", pasta) })
        assertEquals(201, view.status, view.toString())
        facts["viewLog.create.fieldTypes"] = Facts.fieldTypes(view.jsonObject)
        val again = api("view-log-create-again", "POST", "/api/view-log/", buildJsonObject { put("recipe", pasta) })
        observe("viewLog.again.status", again.status)
        // ViewLogSerializer.create reuses a view of the same recipe from the last 5 minutes [K].
        val sameId = again.json is JsonObject && again.jsonObject.longAt("id") == view.jsonObject.longAt("id")
        verify("viewLog.againWithin5MinReturnsSameId", sameId, true)
        val bunsView = api("view-log-create-buns", "POST", "/api/view-log/", buildJsonObject { put("recipe", buns) }, record = false)
        assertEquals(201, bunsView.status, bunsView.toString())
    }

    // --- d. Meal plan (Today tab) ---------------------------------------------------------------

    @Test
    @Order(40)
    fun mealPlans() {
        val tomorrow = today.plusDays(1)
        val dinnerType = mealTypeRef(Fas1Seed.DINNER)
        val lunchType = mealTypeRef(Fas1Seed.LUNCH)
        val todayPlan = api(
            "meal-plan-create-today",
            "POST",
            "/api/meal-plan/",
            mealPlan("", recipeRef("svamppasta"), 4, "", "${today}T00:00:00", dinnerType),
        )
        assertEquals(201, todayPlan.status, todayPlan.toString())
        // A local midnight takes the meal type's time (18:00) [K: MealPlanSerializer._apply_default_time].
        verify("mealPlan.midnightWithTypeTime.fromTime", timeOf(todayPlan.jsonObject.stringAt("from_date")), "18:00:00")
        verify("mealPlan.toDateDefaultsToFromDate", todayPlan.jsonObject.stringAt("to_date"), todayPlan.jsonObject.stringAt("from_date"))
        observe("mealPlan.fieldTypes", Facts.fieldTypes(todayPlan.jsonObject))

        val lunch = api(
            "meal-plan-create-tomorrow",
            "POST",
            "/api/meal-plan/",
            mealPlan("", recipeRef("shakshuka"), 2, "", "${tomorrow}T00:00:00", lunchType),
        )
        assertEquals(201, lunch.status, lunch.toString())
        verify("mealPlan.midnightWithoutTypeTime.fromTime", timeOf(lunch.jsonObject.stringAt("from_date")), "12:00:00")

        val explicit = ZonedDateTime.of(tomorrow, LocalTime.of(19, 30), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val titleOnly = api(
            "meal-plan-create-title-only",
            "POST",
            "/api/meal-plan/",
            mealPlan("Rester", JsonNull, 1, "Ät upp det som finns i **kylen**.", explicit, dinnerType),
        )
        assertEquals(201, titleOnly.status, titleOnly.toString())
        verify("mealPlan.explicitTime.fromTime", timeOf(titleOnly.jsonObject.stringAt("from_date")), "19:30:00")
        observe("mealPlan.titleOnly.recipe", titleOnly.jsonObject["recipe"])
        observe("mealPlan.titleOnly.noteMarkdown", titleOnly.jsonObject["note_markdown"])

        val window = api("meal-plan-list", "GET", "/api/meal-plan/?from_date=$today&to_date=$tomorrow")
        assertEquals(200, window.status, window.toString())
        observe("mealPlan.list.keys", window.jsonObject.keys.toList())
        verify("mealPlan.list.todayAndTomorrow.count", window.jsonObject.longAt("count"), 3)
        val todayOnly = api("meal-plan-list-today", "GET", "/api/meal-plan/?from_date=$today&to_date=$today", record = false)
        verify("mealPlan.list.todayOnly.count", todayOnly.jsonObject.longAt("count"), 1)
        val noParams = api("meal-plan-list-default-window", "GET", "/api/meal-plan/", record = false)
        verify("mealPlan.list.defaultWindow.count", noParams.jsonObject.longAt("count"), 3)

        val types = api("meal-type-list", "GET", "/api/meal-type/")
        assertEquals(200, types.status, types.toString())
        observe("mealType.list.keys", (types.json as? JsonObject)?.keys?.toList())
    }

    // --- d2. Recipe books ("samlingar" in the search filter) -----------------------------------

    @Test
    @Order(45)
    fun recipeBooks() {
        val book = api(
            "recipe-book-create",
            "POST",
            "/api/recipe-book/",
            buildJsonObject {
                put("name", "Favoriter")
                put("description", "Det vi lagar om och om igen.")
                put("shared", JsonArray(emptyList()))
                put("filter", JsonNull)
            },
        )
        assertEquals(201, book.status, book.toString())
        val bookId = book.jsonObject.longAt("id")!!
        for (slug in listOf("svamppasta", "appelkaka")) {
            val entry = api(
                "recipe-book-entry-create",
                "POST",
                "/api/recipe-book-entry/",
                buildJsonObject {
                    put("book", bookId)
                    put("recipe", recipeIds.getValue(slug))
                },
                record = slug == "svamppasta",
            )
            assertEquals(201, entry.status, entry.toString())
        }
        val books = api("recipe-book-list", "GET", "/api/recipe-book/")
        verify("recipeBook.list.count", (books.json as? JsonObject)?.longAt("count"), 1)
        val entries = api("recipe-book-entry-list", "GET", "/api/recipe-book-entry/?book=$bookId")
        verify("recipeBook.entries.count", (entries.json as? JsonObject)?.longAt("count"), 2)
        listQuery("books-or", "books_or=$bookId", record = true).also {
            expectNames("recipeBook.recipeListBooksOr", it, listOf("Krämig svamppasta", "Äppelkaka"))
        }
        val asReader = api("recipe-book-list-as-other-member", "GET", "/api/recipe-book/", token = env.readerToken, record = false)
        verify("recipeBook.otherMember.count", (asReader.json as? JsonObject)?.longAt("count"), 0)
    }

    // --- e. What bumps Recipe.updated_at (plan §3.5) --------------------------------------------

    @Test
    @Order(50)
    fun editsAndUpdatedAt() {
        val id = recipeIds.getValue(Fas1Seed.EDITED)
        val before = recipeDetail(id)
        val step = before.arrAt("steps").objects().first()
        val stepId = step.longAt("id")!!
        val ingredientId = step.arrAt("ingredients").objects().first().longAt("id")!!
        val carrotId = foodIds.getValue("morot")
        var updatedAt = before.stringAt("updated_at")

        fun bumped(name: String): Boolean {
            val now = recipeDetail(id).stringAt("updated_at")
            val changed = now != updatedAt
            updatedAt = now
            observe("updatedAt.$name.bumpsRecipe", changed)
            return changed
        }

        Thread.sleep(SMALL_PAUSE_MS)
        val stepPatch = api(
            "step-patch",
            "PATCH",
            "/api/step/$stepId/",
            buildJsonObject { put("instruction", "Koka {{ ingredients[0] }} och {{ ingredients[1] }} i {{ scale(20) }} min. Smaka av.") },
        )
        assertEquals(200, stepPatch.status, stepPatch.toString())
        verify("updatedAt.stepPatch.bumpsRecipe", bumped("stepPatch"), false)

        Thread.sleep(SMALL_PAUSE_MS)
        val ingredientPatch = api("ingredient-patch", "PATCH", "/api/ingredient/$ingredientId/", buildJsonObject { put("amount", 5) })
        assertEquals(200, ingredientPatch.status, ingredientPatch.toString())
        verify("updatedAt.ingredientPatch.bumpsRecipe", bumped("ingredientPatch"), false)

        Thread.sleep(SMALL_PAUSE_MS)
        val foodPatch = api(
            "food-patch",
            "PATCH",
            "/api/food/$carrotId/",
            buildJsonObject {
                put("name", "ekologisk morot")
                put("plural_name", "ekologiska morötter")
            },
        )
        assertEquals(200, foodPatch.status, foodPatch.toString())
        verify("updatedAt.foodRename.bumpsRecipe", bumped("foodRename"), false)

        Thread.sleep(SMALL_PAUSE_MS)
        val recipePatch = api(
            "recipe-patch",
            "PATCH",
            "/api/recipe/$id/",
            buildJsonObject { put("description", "Beskrivningen ändrades med PATCH av kontraktstestet.") },
        )
        assertEquals(200, recipePatch.status, recipePatch.toString())
        verify("updatedAt.recipePatch.bumpsRecipe", bumped("recipePatch"), true)
        val after = recipeDetail(id)
        verify(
            "updatedAt.recipePatch.stepsKept",
            after.arrAt("steps").objects().map { it.longAt("id") },
            before.arrAt("steps").objects().map { it.longAt("id") },
        )
    }

    // --- f. Index, lists, pagination and filters ------------------------------------------------

    @Test
    @Order(60)
    fun recipeFlat() {
        val flat = api("recipe-flat", "GET", "/api/recipe/flat/")
        assertEquals(200, flat.status, flat.toString())
        val entries = flat.json.objects()
        verify("flat.isArray", flat.json is JsonArray, true)
        verify("flat.fields", entries.firstOrNull()?.keys?.sorted(), listOf("id", "image", "name"))
        // 10 seeded + the temporary + the back-dated recipe from contract.yml.
        verify("flat.count", entries.size, Fas1Seed.recipes.size + 2)
        verify("flat.containsPrivateOwnRecipe", entries.any { it.longAt("id") == recipeIds["artsoppa"] }, true)
        val withImage = entries.firstOrNull { it.longAt("id") == recipeIds["svamppasta"] }?.stringAt("image")
        observe("flat.image", withImage)
        verify("flat.imageIsRelative", withImage?.startsWith("/media/"), true)
        verify("flat.imageNullWithoutImage", entries.firstOrNull { it.longAt("id") == recipeIds["pannkakor"] }?.get("image"), JsonNull)
        observe("flat.orderOfNames", entries.map { it.stringAt("name") })
    }

    @Test
    @Order(61)
    fun recipeListPagination() {
        val total = Fas1Seed.recipes.size + 2
        var path: String? = "/api/recipe/?page_size=4"
        var page = 1
        val pages = mutableListOf<Map<String, Any?>>()
        while (path != null && page <= 5) {
            val ex = api("recipe-list-page-$page", "GET", path)
            assertEquals(200, ex.status, ex.toString())
            val body = ex.jsonObject
            pages += mapOf(
                "path" to path,
                "keys" to body.keys.toList(),
                "count" to body.longAt("count"),
                "next" to body.stringAt("next"),
                "previous" to body.stringAt("previous"),
                "names" to body.arrAt("results").objects().map { it.stringAt("name") },
            )
            path = body.stringAt("next")?.let { pathOf(it) }
            page += 1
        }
        facts["list.pages"] = pages
        verify("list.keys", pages.first()["keys"], listOf("count", "next", "previous", "results"))
        verify("list.count", pages.first()["count"], total)
        verify("list.pageCount", pages.size, 3)
        verify("list.page1.previous", pages.first()["previous"], null)
        verify(
            "list.page1.next",
            pages.first()["next"],
            "$PUBLIC_ORIGIN/api/recipe/?page=2&page_size=4",
        )
        verify("list.page2.previous", pages[1]["previous"], "$PUBLIC_ORIGIN/api/recipe/?page_size=4")
        verify("list.lastPage.next", pages.last()["next"], null)

        val outOfRange = api("recipe-list-page-out-of-range", "GET", "/api/recipe/?page=99&page_size=4")
        observe("list.outOfRange", describe(outOfRange))
        verify("list.outOfRange.status", outOfRange.status, 404)

        val first = api("recipe-list-default", "GET", "/api/recipe/")
        val results = first.jsonObject.arrAt("results").objects()
        observe("list.default.names", results.map { it.stringAt("name") })
        observe("list.overview.fieldTypes", results.firstOrNull()?.let { Facts.fieldTypes(it) })
        val pasta = results.firstOrNull { it.longAt("id") == recipeIds["svamppasta"] }
        observe("list.overview.image", pasta?.stringAt("image"))
        verify("list.overview.imageIsAbsoluteHttps", pasta?.stringAt("image")?.startsWith("$PUBLIC_ORIGIN/media/"), true)
        observe("list.overview.ratingWithoutSort", pasta?.get("rating"))
        observe("list.overview.lastCookedWithoutSort", pasta?.get("last_cooked"))
        observe("list.overview.keywords", pasta?.get("keywords"))
        // `recent` is only present when num_recent is sent (DRF skips the missing attribute).
        verify("list.overview.keys", pasta?.keys?.sorted(), OVERVIEW_KEYS)
    }

    @Test
    @Order(62)
    fun recipeListFilters() {
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val total = Fas1Seed.recipes.size + 2
        val parent = keywordIds.getValue(Fas1Seed.PARENT_KEYWORD)
        val child = keywordIds.getValue(Fas1Seed.CHILD_KEYWORD)

        listQuery("query-svamp", "query=svamp", record = true).also { expectNames("query.svamp", it, listOf("Krämig svamppasta")) }
        // Fuzzy (trigram) name search with the default search preferences: diacritics and case do not matter,
        // and similar names match too.
        listQuery("query-kottbullar", "query=kottbullar").also {
            expectNames("query.withoutDiacritics", it, listOf("Köttbullar med potatismos", "Kanelbullar"))
        }
        listQuery("query-uppercase", "query=ÄRTSOPPA").also { expectNames("query.uppercase", it, listOf("Ärtsoppa", "Grönsakssoppa")) }
        listQuery("query-english", "query=shakshuka").also { expectNames("query.english", it, listOf("Shakshuka")) }
        listQuery("query-ingredient", "query=champinjon").also { expectNames("query.ingredientName", it, emptyList()) }

        listQuery("keywords-or-parent", "keywords_or=$parent", record = true).also {
            expectNames("keywords.orParent.includesChildKeyword", it, listOf("Krämig svamppasta", "Köttbullar med potatismos"))
        }
        listQuery("keywords-or-parent-no-children", "keywords_or=$parent&include_children=false").also {
            expectNames("keywords.orParent.withoutChildren", it, listOf("Köttbullar med potatismos"))
        }
        listQuery("keywords-or-child", "keywords_or=$child").also { expectNames("keywords.orChild", it, listOf("Krämig svamppasta")) }

        // foods_and (the app's food filter, plan 6.2 "livsmedel"): a recipe must have an ingredient of every food.
        val onion = foodIds.getValue("gul lök")
        val potato = foodIds.getValue("potatis")
        listQuery("foods-and-onion", "foods_and=$onion").also {
            expectNames("foods.andOnion", it, listOf("Krämig svamppasta", "Köttbullar med potatismos", "Ärtsoppa"))
        }
        listQuery("foods-and-onion-potato", "foods_and=$onion&foods_and=$potato").also {
            expectNames("foods.andOnionPotato", it, listOf("Köttbullar med potatismos"))
        }

        // sort_order=-times_cooked is offered by the web's search page but crashes in 2.6.13 (no such annotation).
        listQuery("sort-times-cooked", "sort_order=-times_cooked", record = true).also {
            verify("sort.minusTimesCooked.status", it.status, 500)
        }
        listQuery("sort-last-cooked", "sort_order=-lastcooked", record = true).also { ex ->
            observeNames("sort.minusLastCooked", ex)
            verify("sort.minusLastCooked.first3", namesOf(ex).take(3), listOf("Krämig svamppasta", "Äppelkaka", "Pannkakor"))
            observe("sort.minusLastCooked.lastCookedField", fieldByName(ex, "last_cooked"))
        }
        listQuery("sort-rating", "sort_order=-rating", record = true).also { ex ->
            observeNames("sort.minusRating", ex)
            verify("sort.minusRating.first3", namesOf(ex).take(3), listOf("Äppelkaka", "Krämig svamppasta", "Pannkakor"))
            observe("sort.minusRating.ratingField", fieldByName(ex, "rating"))
        }
        listQuery("timescooked-gte", "timescooked_gte=1&sort_order=-lastcooked", record = true).also {
            expectNames("filter.timescookedGte1", it, listOf("Krämig svamppasta", "Äppelkaka", "Pannkakor"))
        }
        listQuery("rating-gte", "rating_gte=4").also { expectNames("filter.ratingGte4", it, listOf("Krämig svamppasta", "Äppelkaka")) }

        val longAgo = today.minusDays(60)
        listQuery("cookedon-lte", "cookedon_lte=$longAgo&sort_order=lastcooked", record = true).also {
            expectNames("filter.cookedonLte60DaysAgo", it, listOf("Pannkakor"))
        }
        listQuery("viewedon-gte", "viewedon_gte=${today.minusDays(14)}&sort_order=-lastviewed", record = true).also {
            expectNames("filter.viewedonGte14DaysAgo", it, listOf("Kanelbullar", "Krämig svamppasta"))
        }
        listQuery("num-recent", "num_recent=16", record = true).also { ex ->
            val results = ex.jsonObject.arrAt("results").objects()
            observe("numRecent.recentField", fieldByName(ex, "recent"))
            // `recent` is the id of the latest ViewLog ("0" = not among the last n viewed); recent ones first.
            verify("numRecent.firstTwo", namesOf(ex).take(2), listOf("Kanelbullar", "Krämig svamppasta"))
            verify("numRecent.othersAreZero", results.drop(2).all { it.stringAt("recent") == "0" }, true)
        }
        listQuery("new", "new=true", record = true).also { ex ->
            val results = ex.jsonObject.arrAt("results").objects()
            observe("new.namesInOrder", results.map { it.stringAt("name") })
            verify("new.count", ex.jsonObject.longAt("count"), total)
            verify("new.oldRecipeFlag", results.firstOrNull { it.stringAt("name") == OLD_RECIPE }?.get("new"), JsonPrimitive(false))
            verify("new.oldRecipeLast", results.lastOrNull()?.stringAt("name"), OLD_RECIPE)
        }

        listQuery("updatedon-gte-today", "updatedon_gte=$today", record = true).also {
            verify("updatedonGte.today.count", it.jsonObject.longAt("count"), total - 1)
            verify("updatedonGte.today.containsOld", namesOf(it).contains(OLD_RECIPE), false)
        }
        listQuery("updatedon-gte-yesterday", "updatedon_gte=$yesterday").also {
            verify("updatedonGte.yesterday.count", it.jsonObject.longAt("count"), total - 1)
        }
        listQuery("updatedon-gte-tomorrow", "updatedon_gte=$tomorrow").also {
            verify("updatedonGte.tomorrow.count", it.jsonObject.longAt("count"), 0)
        }
        val dateTime = api("recipe-list-updatedon-gte-datetime", "GET", "/api/recipe/?updatedon_gte=${today}T00:00:00")
        observe("updatedonGte.dateTime", describe(dateTime, includeBody = dateTime.json != null))
        // The filter compares updated_at__date: anything but yyyy-MM-dd crashes the query.
        verify("updatedonGte.dateTime.status", dateTime.status, 500)
    }

    @Test
    @Order(63)
    fun absoluteUrlsFollowTheRequestHost() {
        val viaProxy = api("recipe-list-proxy", "GET", "/api/recipe/?page_size=1", record = false)
        val hostOnly = http.call(
            "recipe-list-host-only",
            "GET",
            "/api/recipe/?page_size=1",
            bearer = env.fas1Token,
            extraHeaders = mapOf("X-Forwarded-Proto" to "http"),
            record = false,
        )
        val direct = directHttp.call("recipe-list-direct", "GET", "/api/recipe/?page_size=1", bearer = env.fas1Token, record = false)
        verify("urls.proxy.next", viaProxy.jsonObject.stringAt("next"), "$PUBLIC_ORIGIN/api/recipe/?page=2&page_size=1")
        val plainHttpNext = "http://${ContractEnv.PUBLIC_HOST}/api/recipe/?page=2&page_size=1"
        verify("urls.hostWithoutHttps.next", hostOnly.jsonObject.stringAt("next"), plainHttpNext)
        verify("urls.direct.next", direct.jsonObject.stringAt("next"), "${env.baseUrl}/api/recipe/?page=2&page_size=1")
    }

    // --- g. Recipe details ------------------------------------------------------------------------

    @Test
    @Order(70)
    fun recipeDetails() {
        val flat = api("recipe-flat-for-details", "GET", "/api/recipe/flat/", record = false).json.objects()
        val old = flat.firstOrNull { it.stringAt("name") == OLD_RECIPE }?.longAt("id")
        verify("old.recipeFound", old != null, true)
        val all = recipeIds + listOfNotNull(old?.let { OLD_RECIPE_SLUG to it })
        for ((slug, id) in all) {
            val ex = api("recipe-$slug", "GET", "/api/recipe/$id/")
            assertEquals(200, ex.status, ex.toString())
            val recipe = ex.jsonObject
            if (slug == "svamppasta") {
                facts["detail.fieldTypes"] = Facts.fieldTypes(recipe)
                val steps = recipe.arrAt("steps").objects()
                facts["detail.step.fieldTypes"] = steps.firstOrNull()?.let { Facts.fieldTypes(it) }
                val ingredient = steps.firstOrNull()?.arrAt("ingredients")?.objects()?.firstOrNull()
                facts["detail.ingredient.fieldTypes"] = ingredient?.let { Facts.fieldTypes(it) }
                facts["detail.food.fieldTypes"] = ingredient?.objAt("food")?.let { Facts.fieldTypes(it) }
                facts["detail.unit.fieldTypes"] = ingredient?.objAt("unit")?.let { Facts.fieldTypes(it) }
                observe("detail.image", recipe.stringAt("image"))
                verify("detail.imageIsAbsoluteHttps", recipe.stringAt("image")?.startsWith("$PUBLIC_ORIGIN/media/recipes/"), true)
                observe("detail.rating", recipe["rating"])
                observe("detail.lastCooked", recipe["last_cooked"])
                observe("detail.keywords", recipe["keywords"])
                observe("templates.svamppasta", steps.map { it.stringAt("instructions_markdown") })
            }
            if (slug == "pannkakor") {
                val flour = ingredientsOf(recipe).firstOrNull { it.objAt("food")?.stringAt("name") == "vetemjöl" }
                observe("conversions.flour", flour?.get("conversions"))
                observe("templates.pannkakor", recipe.arrAt("steps").objects().map { it.stringAt("instructions_markdown") })
                observe("detail.pannkakor.lastCooked", recipe["last_cooked"])
                observe("detail.pannkakor.rating", recipe["rating"])
            }
            if (slug == "appelkaka") {
                // rating = the requesting user's average cook-log rating, rounded. last_cooked stays null in
                // 2.6.13 although the recipe was cooked (the annotation is called `lastcooked`).
                verify("detail.appelkaka.rating", recipe["rating"], JsonPrimitive(5.0))
                verify("detail.appelkaka.lastCooked", recipe["last_cooked"], JsonNull)
            }
            if (slug == "shakshuka") {
                observe("templates.shakshuka", recipe.arrAt("steps").objects().map { it.stringAt("instructions_markdown") })
                val water = ingredientsOf(recipe).firstOrNull { it.objAt("food")?.stringAt("name") == "water" }
                observe("amount.twoThirds", water?.get("amount"))
            }
            if (slug == "smorgastarta") {
                val amounts = ingredientsOf(recipe).associate { (it.objAt("food")?.stringAt("name") ?: "?") to it["amount"] }
                observe("amount.smorgastarta", amounts)
                val conversions = ingredientsOf(recipe).associate { (it.objAt("food")?.stringAt("name") ?: "?") to it["conversions"] }
                observe("conversions.smorgastarta", conversions)
            }
            if (slug == "rarakor") {
                observe("templates.broken", recipe.arrAt("steps").objects().firstOrNull()?.stringAt("instructions_markdown"))
            }
            if (slug == "kanelbullar") {
                val header = ingredientsOf(recipe).firstOrNull { it.boolAt("is_header") == true }
                observe("header.ingredient", header)
            }
            if (slug == "kottbullar") {
                val noAmount = ingredientsOf(recipe).firstOrNull { it.boolAt("no_amount") == true }
                observe("noAmount.ingredient", noAmount)
                observe("conversions.noUnit", ingredientsOf(recipe).firstOrNull { it["unit"] is JsonNull }?.get("conversions"))
            }
            if (slug == Fas1Seed.EDITED) {
                observe("templates.edited", recipe.arrAt("steps").objects().map { it.stringAt("instructions_markdown") })
            }
        }
        facts["detail.recipes"] = all.keys.toList()
    }

    @Test
    @Order(71)
    fun mediaNeedsNoAuth() {
        val image = recipeDetail(recipeIds.getValue("svamppasta")).stringAt("image")
        assertNotNull(image, "svamppasta has no image")
        val path = pathOf(image!!)
        val anonymous = directHttp.call("media-image-get-no-auth", "GET", path, recordBodyText = false)
        observe("media.noAuth", describe(anonymous, includeBody = false))
        verify("media.noAuth.status", anonymous.status, 200)
        verify("media.noAuth.contentType", anonymous.contentType, "image/png")
        verify("media.noAuth.contentDisposition", anonymous.header("Content-Disposition"), "attachment; filename=\"\"")
        observe("media.noAuth.cacheControl", anonymous.header("Cache-Control"))
        observe("media.noAuth.etag", anonymous.header("ETag") != null)
        observe("media.noAuth.lastModified", anonymous.header("Last-Modified") != null)
        val withToken = directHttp.call("media-get-with-token", "GET", path, bearer = env.fas1Token, record = false, recordBodyText = false)
        verify("media.withToken.status", withToken.status, 200)
        val missingPath = "/media/recipes/does-not-exist.png"
        val missing = directHttp.call("media-get-missing", "GET", missingPath, record = false, recordBodyText = false)
        observe("media.missing", describe(missing, includeBody = false))
    }

    // --- h. Private recipe as another member of the space ---------------------------------------

    @Test
    @Order(80)
    fun privateRecipeForOtherMembers() {
        val reader = env.readerToken
        val privateId = recipeIds.getValue("artsoppa")
        val flat = api("recipe-flat-as-other-member", "GET", "/api/recipe/flat/", token = reader, record = false)
        assertEquals(200, flat.status, flat.toString())
        verify("private.otherMember.flatContains", flat.json.objects().any { it.longAt("id") == privateId }, false)
        val list = api("recipe-list-as-other-member", "GET", "/api/recipe/?page_size=100", token = reader, record = false)
        verify("private.otherMember.listCount", list.jsonObject.longAt("count"), Fas1Seed.recipes.size + 1)
        val detail = api("recipe-private-as-other-member", "GET", "/api/recipe/$privateId/", token = reader)
        observe("private.otherMember.detail", describe(detail))
        verify("private.otherMember.detailStatus", detail.status, 403)
        val pastaId = recipeIds.getValue("svamppasta")
        val publicDetail = api("recipe-public-as-other-member", "GET", "/api/recipe/$pastaId/", token = reader, record = false)
        verify("private.otherMember.publicDetailStatus", publicDetail.status, 200)
        observe("private.otherMember.publicRating", (publicDetail.json as? JsonObject)?.get("rating"))
    }

    // --- i. Reference lists, preferences, space -------------------------------------------------

    @Test
    @Order(85)
    fun referenceLists() {
        val lists = listOf(
            "keyword-list" to "/api/keyword/?page_size=100",
            "food-list" to "/api/food/?page_size=100",
            "unit-list" to "/api/unit/?page_size=100",
            "unit-conversion-list" to "/api/unit-conversion/?page_size=100",
        )
        for ((name, path) in lists) {
            val ex = api(name, "GET", path)
            verify("$name.status", ex.status, 200)
            val body = ex.json as? JsonObject
            observe("$name.keys", body?.keys?.toList())
            observe("$name.count", body?.longAt("count"))
            observe("$name.itemFieldTypes", body?.arrAt("results")?.objects()?.firstOrNull()?.let { Facts.fieldTypes(it) })
        }
        val keywords = api("keyword-list-root", "GET", "/api/keyword/?root=0&page_size=100", record = false)
        observe("keyword.list.root0.names", (keywords.json as? JsonObject)?.arrAt("results")?.objects()?.map { it.stringAt("name") })
        val tree = api("keyword-list-children", "GET", "/api/keyword/?root=${keywordIds.getValue(Fas1Seed.PARENT_KEYWORD)}", record = false)
        observe("keyword.list.childrenOfParent.names", (tree.json as? JsonObject)?.arrAt("results")?.objects()?.map { it.stringAt("name") })

        val preference = api("user-preference", "GET", "/api/user-preference/")
        assertEquals(200, preference.status, preference.toString())
        val first = preference.json.objects().firstOrNull()
        verify("userPreference.useFractions", first?.boolAt("use_fractions"), false)
        verify("userPreference.ingredientDecimals", first?.longAt("ingredient_decimals"), 2)
        observe("userPreference.defaultMealType", first?.get("default_meal_type"))
        val space = api("space-current", "GET", "/api/space/current/")
        verify("space.status", space.status, 200)
    }

    // --- j. Deleting a recipe -------------------------------------------------------------------

    @Test
    @Order(90)
    fun deletingARecipe() {
        val id = temporaryRecipeId
        assertNotNull(id, "temporary recipe was not created")
        val before = api("recipe-flat-before-delete", "GET", "/api/recipe/flat/", record = false).json.objects()
        verify("delete.flatContainsBefore", before.any { it.longAt("id") == id }, true)
        val delete = api("recipe-delete", "DELETE", "/api/recipe/$id/")
        verify("delete.status", delete.status, 204)
        val after = api("recipe-flat-after-delete", "GET", "/api/recipe/flat/", record = false).json.objects()
        verify("delete.flatContainsAfter", after.any { it.longAt("id") == id }, false)
        val get = api("recipe-deleted-get", "GET", "/api/recipe/$id/")
        verify("delete.getStatus", get.status, 404)
        verify("delete.getBody", get.json, buildJsonObject { put("detail", "No Recipe matches the given query.") })
        val again = api("recipe-delete-again", "DELETE", "/api/recipe/$id/", record = false)
        verify("delete.againStatus", again.status, 404)
        val list = api("recipe-list-after-delete", "GET", "/api/recipe/?page_size=100", record = false)
        verify("delete.listCount", list.jsonObject.longAt("count"), Fas1Seed.recipes.size + 1)
    }

    // --- k. "Klart!" as the app sends it (Phase 1b, 0.2.0) ---------------------------------------

    /**
     * The app's outbox sends `POST /api/cook-log/` with `created_at` = when "Klart!" was pressed, in
     * UTC whole seconds (`TandoorDates.format`), possibly days later. Tandoor must accept it, keep
     * the instant, and list it under the same instant and author: a retry after a lost answer
     * looks the entry up that way instead of posting it twice. Runs after the list facts and
     * deletes its entry again, so no other fact changes.
     */
    @Test
    @Order(91)
    fun cookLogBackdatedAsTheAppSends() {
        val pasta = recipeIds.getValue("svamppasta")
        val cookedAt = ZonedDateTime.now(ZoneId.of("UTC")).minusDays(3).withNano(0)
        val sentAt = cookedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val body = buildJsonObject {
            put("recipe", pasta)
            put("servings", 3)
            put("rating", 4)
            put("comment", "Lagad offline")
            put("created_at", sentAt)
        }
        val created = api("cook-log-create-app", "POST", "/api/cook-log/", body, record = false)
        verify("cookLog.app.status", created.status, 201)
        val entry = created.json as? JsonObject ?: return
        val returnedAt = entry.stringAt("created_at")?.let { ZonedDateTime.parse(it).toInstant() }
        verify("cookLog.app.createdAtInstantKept", returnedAt == cookedAt.toInstant(), true)
        verify(
            "cookLog.app.fieldsKept",
            listOf(entry.longAt("servings"), entry.longAt("rating"), entry.stringAt("comment")),
            listOf(3L, 4L, "Lagad offline"),
        )

        val id = entry.longAt("id")
        val author = entry.objAt("created_by")?.longAt("id")
        val listed = api("cook-log-list-app", "GET", "/api/cook-log/?recipe=$pasta&page_size=100", record = false)
        val match = listed.jsonObject.arrAt("results").objects().firstOrNull { it.longAt("id") == id }
        val listedAt = match?.stringAt("created_at")?.let { ZonedDateTime.parse(it).toInstant() }
        verify("cookLog.app.listedSameInstant", listedAt == cookedAt.toInstant(), true)
        verify("cookLog.app.listedSameAuthor", match?.objAt("created_by")?.longAt("id") == author && author != null, true)

        // A recipe deleted on the server: 400 on the `recipe` field (not 404); the outbox drops
        // such an entry (CookLogProblems.RECIPE_UNKNOWN) instead of keeping it as FAILED.
        temporaryRecipeId?.let { gone ->
            val orphan = buildJsonObject {
                put("recipe", gone)
                put("created_at", sentAt)
            }
            val answer = api("cook-log-create-deleted-recipe", "POST", "/api/cook-log/", orphan, record = false)
            verify("cookLog.deletedRecipe.status", answer.status, 400)
            verify("cookLog.deletedRecipe.fieldErrorOnRecipe", (answer.json as? JsonObject)?.containsKey("recipe"), true)
            observe("cookLog.deletedRecipe.body", answer.json)
        }

        if (id != null) {
            val cleanup = api("cook-log-delete-app", "DELETE", "/api/cook-log/$id/", record = false)
            observe("cookLog.app.deleteStatus", cleanup.status)
        }
    }

    // --- l. A step with a file (cook mode's step photo, 0.2.0) ---------------------------------

    /**
     * Cook mode shows a step's photo (`StepDto.filePreview`): the step's `file` is a
     * `UserFileView` whose `preview` Tandoor fills only for an image (PIL can open it), else "".
     * Uploads one PNG and one PDF as user files, attaches them to two steps of a new recipe, reads
     * the detail (recorded as `recipe-step-file.json`, the fixture of `DtoFixturesTest`), checks
     * the preview format and whether the preview and the download need a login, then deletes the
     * recipe again. Runs after every list fact, so no other fact changes; the scaling table
     * (`web-scaling/golden.mjs`, `NOT_SCALED`) skips this recording.
     */
    @Test
    @Order(92)
    fun stepWithAFile() {
        val image = uploadUserFile("user-file-create-image", "Stegbild", "tandem-stegbild.png", tinyPng(0x2E7D32), PNG)
        val pdf = uploadUserFile("user-file-create-pdf", "Receptblad", "tandem-receptblad.pdf", TINY_PDF, PDF)
        verify("stepFile.upload.image.status", image.status, 201)
        verify("stepFile.upload.pdf.status", pdf.status, 201)
        val imageFile = image.json as? JsonObject ?: return
        val pdfFile = pdf.json as? JsonObject ?: return
        facts["stepFile.upload.fieldTypes"] = Facts.fieldTypes(imageFile)
        observe("stepFile.upload.image.preview", imageFile.stringAt("preview"))
        observe("stepFile.upload.pdf.preview", pdfFile.stringAt("preview"))

        val recipe = stepFileRecipe(imageFile, pdfFile)
        val created = api("recipe-create-step-file", "POST", "/api/recipe/", recipe, record = false)
        verify("stepFile.recipeCreate.status", created.status, 201)
        val id = (created.json as? JsonObject)?.longAt("id") ?: return
        val detail = api("recipe-step-file", "GET", "/api/recipe/$id/")
        verify("stepFile.detail.status", detail.status, 200)
        val steps = (detail.json as? JsonObject)?.arrAt("steps").objects()
        val withImage = steps.getOrNull(0)?.objAt("file")
        val withPdf = steps.getOrNull(1)?.objAt("file")
        facts["stepFile.file.fieldTypes"] = withImage?.let { Facts.fieldTypes(it) }
        verify("stepFile.withoutFile.file", steps.getOrNull(2)?.get("file"), JsonNull)
        val preview = withImage?.stringAt("preview")
        observe("stepFile.image.preview", preview)
        verify("stepFile.image.previewIsAbsoluteHttps", preview?.startsWith("$PUBLIC_ORIGIN/media/"), true)
        verify("stepFile.pdf.preview", withPdf?.stringAt("preview"), "")
        observe("stepFile.image.fileDownload", withImage?.stringAt("file_download"))
        verify("stepFile.pdf.fileDownloadIsAbsoluteHttps", withPdf?.stringAt("file_download")?.startsWith("$PUBLIC_ORIGIN/"), true)

        // The preview is a media file: like recipe images, it must load without the token.
        if (preview != null) {
            val anonymous = directHttp.call("media-step-file-no-auth", "GET", pathOf(preview), record = false, recordBodyText = false)
            observe("stepFile.preview.noAuth", describe(anonymous, includeBody = false))
            verify("stepFile.preview.noAuth.status", anonymous.status, 200)
            verify("stepFile.preview.noAuth.contentType", anonymous.contentType, "image/png")
        }
        // file_download is an API view (any file type), not what cook mode shows.
        withPdf?.stringAt("file_download")?.let { download ->
            val anonymous = directHttp.call("step-file-download-no-auth", "GET", pathOf(download), record = false, recordBodyText = false)
            observe("stepFile.download.noAuth", describe(anonymous, includeBody = false))
            observe("stepFile.download.noAuth.location", anonymous.header("Location"))
        }

        // The user files stay: a deleted recipe leaves its steps behind for a while, and they still
        // protect the files (500 ProtectedError on DELETE /api/user-file/). The server is thrown away.
        observe("stepFile.cleanup.recipe", api("recipe-delete-step-file", "DELETE", "/api/recipe/$id/", record = false).status)
    }

    /** Three steps: the image, the PDF, no file. A file is referenced by id (UserFileView is read-only). */
    private fun stepFileRecipe(image: JsonObject, pdf: JsonObject): JsonObject {
        fun step(instruction: String, order: Int, file: JsonObject?) = buildJsonObject {
            put("instruction", instruction)
            put("order", order)
            put("ingredients", JsonArray(emptyList()))
            put(
                "file",
                file?.let {
                    buildJsonObject {
                        put("id", it.longAt("id"))
                        put("name", it.stringAt("name"))
                    }
                } ?: JsonNull,
            )
        }
        return buildJsonObject {
            put("name", STEP_FILE_RECIPE)
            put("servings", 2)
            put("internal", true)
            put("keywords", JsonArray(emptyList()))
            put(
                "steps",
                JsonArray(
                    listOf(
                        step("Lägg upp som på bilden.", 0, image),
                        step("Skriv ut receptbladet.", 1, pdf),
                        step("Servera.", 2, null),
                    ),
                ),
            )
        }
    }

    private fun uploadUserFile(name: String, fileName: String, uploadName: String, bytes: ByteArray, type: MediaType): Exchange {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", fileName)
            .addFormDataPart("file", uploadName, bytes.toRequestBody(type))
            .build()
        val recordBody = buildJsonObject {
            put("name", fileName)
            put("file", "<multipart file $uploadName, $type, ${bytes.size} bytes>")
        }
        return http.call(name, "POST", "/api/user-file/", bearer = env.fas1Token, body = body, recordBody = recordBody, record = false)
    }

    // --- Baseline ---------------------------------------------------------------------------------

    @Test
    @Order(99)
    fun everyFactMatchesTheBaseline() {
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the 2.6.13 baseline:\n" + mismatches.joinToString("\n"))
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun api(
        name: String,
        method: String,
        path: String,
        body: JsonElement? = null,
        token: String = env.fas1Token,
        record: Boolean = true,
    ): Exchange = http.call(
        name,
        method,
        path,
        bearer = token,
        body = body?.toString()?.toRequestBody(JSON),
        recordBody = body,
        record = record,
    )

    private fun listQuery(name: String, query: String, record: Boolean = false): Exchange {
        val ex = api("recipe-list-$name", "GET", "/api/recipe/?$query&page_size=100", record = record)
        observe("list.$name.status", ex.status)
        if (ex.status != 200 || ex.json !is JsonObject) {
            observe("list.$name.error", describe(ex))
            val empty = """{"count": null, "results": []}"""
            return Exchange(ex.name, ex.method, ex.path, ex.requestHeaders, null, ex.status, ex.responseHeaders, empty)
        }
        return ex
    }

    private fun namesOf(ex: Exchange): List<String?> = ex.jsonObject.arrAt("results").objects().map { it.stringAt("name") }

    private fun fieldByName(ex: Exchange, field: String): Map<String, JsonElement?> =
        ex.jsonObject.arrAt("results").objects().associate { (it.stringAt("name") ?: "?") to it[field] }

    /** Compares the names as sets (the database collation decides the order of å/ä/ö); the order is kept as a fact. */
    private fun expectNames(key: String, ex: Exchange, expected: List<String>) {
        verify(key, namesOf(ex).map { it.orEmpty() }.sorted(), expected.sorted())
        observe("$key.order", namesOf(ex))
    }

    private fun observeNames(key: String, ex: Exchange) = observe(key, namesOf(ex))

    private fun recipeDetail(id: Long): JsonObject {
        val ex = api("recipe-detail", "GET", "/api/recipe/$id/", record = false)
        assertEquals(200, ex.status, ex.toString())
        return ex.jsonObject
    }

    private fun ingredientsOf(recipe: JsonObject): List<JsonObject> {
        val steps = recipe.arrAt("steps").objects()
        return steps.flatMap { it.arrAt("ingredients").objects() }
    }

    private fun recipeRef(slug: String): JsonObject {
        val recipe = Fas1Seed.recipes.first { it.slug == slug }
        return buildJsonObject {
            put("id", recipeIds.getValue(slug))
            put("name", recipe.name)
        }
    }

    private fun mealTypeRef(name: String): JsonObject = buildJsonObject {
        put("id", mealTypeIds.getValue(name))
        put("name", name)
    }

    private fun mealPlan(
        title: String,
        recipe: JsonElement,
        servings: Int,
        note: String,
        fromDate: String,
        mealType: JsonObject,
    ): JsonObject = buildJsonObject {
        put("title", title)
        put("recipe", recipe)
        put("servings", servings)
        put("note", note)
        put("from_date", fromDate)
        put("meal_type", mealType)
    }

    private fun cookLog(recipe: Long, servings: Int, rating: Int?, comment: String?): JsonObject = buildJsonObject {
        put("recipe", recipe)
        put("servings", servings)
        put("rating", rating?.let { JsonPrimitive(it) } ?: JsonNull)
        put("comment", comment?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** `2026-10-11T18:00:00+02:00` -> `18:00:00`. */
    private fun timeOf(dateTime: String?): String? = dateTime?.substringAfter('T', "")?.take(8)

    /** Path and query of an absolute URL (the client always talks to its own base URL). */
    private fun pathOf(url: String): String {
        if (url.startsWith("/")) return url
        val uri = URI(url)
        return uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun verify(key: String, observed: Any?, expected: Any?) {
        facts[key] = observed
        val observedJson = Facts.toJson(observed)
        val expectedJson = Facts.toJson(expected)
        if (observedJson != expectedJson) mismatches += "$key: expected $expectedJson, observed $observedJson"
    }

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun describe(ex: Exchange, includeBody: Boolean = true): Map<String, Any?> = buildMap {
        put("status", ex.status)
        put("contentType", ex.contentType)
        put("isJson", ex.json != null)
        if (includeBody) put("body", ex.json ?: JsonPrimitive(ex.bodyText.take(300)))
    }

    private fun tinyPng(rgb: Int): ByteArray {
        val image = BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until IMAGE_SIZE) for (y in 0 until IMAGE_SIZE) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val PNG = "image/png".toMediaType()
        val PDF = "application/pdf".toMediaType()

        /** Smallest file Tandoor accepts as `.pdf` that PIL cannot open as an image. */
        val TINY_PDF: ByteArray = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".toByteArray()
        const val STEP_FILE_RECIPE = "Stegbild"
        const val IMAGE_SIZE = 8
        const val SMALL_PAUSE_MS = 50L

        /** Created by contract.yml with `manage.py shell` and back-dated 30 days (the API cannot set `created_at`). */
        const val OLD_RECIPE = "Pytt i panna"
        const val OLD_RECIPE_SLUG = "pyttipanna"
        const val PUBLIC_ORIGIN = "https://${ContractEnv.PUBLIC_HOST}"

        /** Fields of a recipe overview in `GET /api/recipe/` without num_recent. */
        val OVERVIEW_KEYS = listOf(
            "created_at",
            "created_by",
            "description",
            "id",
            "image",
            "internal",
            "keywords",
            "last_cooked",
            "name",
            "new",
            "private",
            "rating",
            "servings",
            "servings_text",
            "updated_at",
            "waiting_time",
            "working_time",
        )
    }
}
