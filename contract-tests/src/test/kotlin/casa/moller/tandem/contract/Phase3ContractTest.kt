package casa.moller.tandem.contract

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Establishes and records the server facts Fas 3's recipe editor (release 0.5.0, feature #22,
 * plan §8.9) depends on, exactly as the app's outbox sends them (`core:sync` `RecipeEditPayloads`):
 *
 * - a. `POST /api/recipe/` with the WHOLE recipe: foods, units and keywords by name (reused or
 *   created, case ignored), a known keyword as `{id, name}` (`{id}` alone is a 400), a header
 *   line, a `no_amount` line, steps with `order`; the answer carries the ids of every step and
 *   ingredient; `internal: true`.
 * - b. `PATCH /api/recipe/{id}/` with the whole recipe (drf-writable-nested): a step or
 *   ingredient that carries its `id` is updated in place and keeps its id, one without `id` is
 *   created, an omitted one is deleted; a food by name is reused (same id); `updated_at` moves
 *   on; a keyword sent as `{id}` keeps its name.
 * - c. Who may: the other member PATCHes too (200); the guest gets 403 on PATCH and DELETE.
 * - d. `DELETE /api/recipe/{id}/` answers 204; afterwards GET answers 404.
 * - e. (import lane) `POST /api/recipe-from-source/` with `data` (a page with schema.org JSON-LD)
 *   answers the parsed recipe without creating it: foods and units by name, `original_text`,
 *   the image, and the space's recipe with the same name as a duplicate; plain text and an
 *   unreachable link answer `error: true`; the guest's answer is recorded.
 * - f. (import lane) `POST /api/ai-import/` on a space without an AI provider, and
 *   `PUT /api/recipe/{id}/image/` with an `image_url` the server cannot fetch: recorded, so the
 *   app knows what it must drop.
 *
 * Runs after the Fas S steps against the same throwaway server. Recordings go to
 * `build/contract-recordings/<tag>/fas3/` and are committed to `api-schema/fixtures/<version>/fas3/`.
 * Checks are soft as in Fas 1/2/S: [observe] records every fact and the last step compares the
 * facts in `EXPECTED` (docs/BYGGE.md "Fas 3 – kontrakt") and fails with all mismatches together.
 *
 * Skipped unless TANDOOR_GUEST_TOKEN is set. Never run against a production server.
 */
@Suppress("LongMethod")
@EnabledIfEnvironmentVariable(named = "TANDOOR_GUEST_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase3ContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val http = TandoorHttp(env.baseUrl, sanitizer, env.fas3RecordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private var recipeId: Long? = null
    private var created: JsonObject? = null
    private var keywordId: Long? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        assertTrue(env.readerToken.isNotBlank(), "TANDOOR_READER_TOKEN must be set")
        assertTrue(env.guestToken.isNotBlank(), "TANDOOR_GUEST_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        sanitizer.fakeTokenFor(env.readerToken)
        sanitizer.fakeTokenFor(env.guestToken)
        env.fas3RecordDir.deleteRecursively()
        env.fas3RecordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun writeFacts() {
        facts["mismatches"] = mismatches
        facts.write(File(env.fas3RecordDir, "observed-facts.json"), sanitizer)
    }

    // --- a. Create ------------------------------------------------------------------------------------

    @Test
    @Order(10)
    fun create() {
        // A keyword the app knows goes by id only: the first keyword of the space (Fas 1 seeded "Huvudrätt" and others).
        val keywords = read("/api/keyword/?page_size=1").json?.let { pageOrArray(it).firstOrNull() }
        keywordId = keywords?.longAt("id")
        val keywordName = keywords?.stringAt("name")
        // `{id}` alone is a 400 ("name: This field is required.", observed 2026-11-03): a known keyword goes as {id, name}.
        val idOnlyBody = recipeBody(RECIPE_NAME, listOfNotNull(keywordId?.let { obj("id" to it) }), emptyList())
        val idOnly = api("recipe-create-keyword-id-only", "POST", "/api/recipe/", idOnlyBody)
        observe("recipe.create.keywordIdOnly", describe(idOnly))
        idOnly.json?.let { (it as? JsonObject)?.longAt("id") }?.let { api("cleanup-recipe", "DELETE", "/api/recipe/$it/", record = false) }
        val body = recipeBody(
            name = RECIPE_NAME,
            keywords = listOfNotNull(keywordId?.let { obj("id" to it, "name" to keywordName) }, obj("name" to NEW_KEYWORD)),
            steps = listOf(
                step(
                    name = "Förbered",
                    instruction = "Skär {{ ingredients[0] }} i bitar.",
                    time = 10,
                    order = 0,
                    ingredients = listOf(
                        ingredient(600, "g", "torskfilé", note = "tinad", order = 0),
                        header("Sås", order = 1),
                        ingredient(2.5, "dl", "vispgrädde", order = 2),
                        ingredient(0, null, "salt", noAmount = true, order = 3),
                    ),
                ),
                step(name = "", instruction = "Grädda i 200 grader.", time = 20, order = 1, ingredients = emptyList()),
            ),
        )
        val response = api("recipe-create-full", "POST", "/api/recipe/", body)
        observe("recipe.create", describe(response, includeBody = response.status != CREATED))
        val recipe = objOf(response) ?: return observe("recipe.create.skipped", "no recipe created")
        created = recipe
        recipeId = recipe.longAt("id")
        observe("recipe.create.internal", recipe["internal"])
        observe("recipe.create.private", recipe["private"])
        observe("recipe.create.stepCount", recipe.arrAt("steps").size)
        observe("recipe.create.stepsHaveIds", recipe.arrAt("steps").objects().all { it.longAt("id") != null })
        val lines = recipe.arrAt("steps").objects().firstOrNull()?.arrAt("ingredients").objects()
        observe("recipe.create.ingredientsHaveIds", lines.all { it.longAt("id") != null })
        observe("recipe.create.foods", lines.map { it.objAt("food")?.stringAt("name") })
        observe("recipe.create.units", lines.map { it.objAt("unit")?.stringAt("name") })
        observe("recipe.create.header", lines.getOrNull(1)?.boolAt("is_header"))
        observe("recipe.create.headerNote", lines.getOrNull(1)?.stringAt("note"))
        observe("recipe.create.noAmount", lines.getOrNull(3)?.boolAt("no_amount"))
        observe("recipe.create.amountDecimal", lines.getOrNull(2)?.doubleAt("amount"))
        observe("recipe.create.keywords", recipe.arrAt("keywords").objects().mapNotNull { it.stringAt("name") }.sorted())
        val createdKeywords = recipe.arrAt("keywords").objects().mapNotNull { it.stringAt("name") }
        observe("recipe.create.keywordByIdKeptName", keywordName != null && keywordName in createdKeywords)
        observe("recipe.create.servingsText", recipe["servings_text"])
        observe("recipe.create.workingTime", recipe["working_time"])
    }

    // --- b. Edit in place ----------------------------------------------------------------------------

    @Test
    @Order(20)
    fun patchWithSteps() {
        val id = recipeId ?: return observe("recipe.patch.skipped", "no recipe created")
        val before = checkNotNull(created)
        val steps = before.arrAt("steps").objects()
        val first = steps[0]
        val firstLines = first.arrAt("ingredients").objects()
        val foodIdBefore = firstLines[0].objAt("food")?.longAt("id")
        // Step 1 edited in place (name, a changed amount, a new line), step 2 left out, a new step without id.
        val editedFirst = obj(
            "id" to first.longAt("id"),
            "name" to "Förbered allt",
            "instruction" to first.stringAt("instruction"),
            "time" to 12,
            "order" to 0,
            "show_as_header" to false,
            "show_ingredients_table" to true,
            "ingredients" to listOf(
                obj(
                    "id" to firstLines[0].longAt("id"),
                    "food" to obj("name" to "torskfilé"),
                    "unit" to obj("name" to "g"),
                    "amount" to 700,
                    "note" to "tinad",
                    "order" to 0,
                    "is_header" to false,
                    "no_amount" to false,
                ),
                obj(
                    "id" to firstLines[1].longAt("id"),
                    "food" to null,
                    "unit" to null,
                    "amount" to 0,
                    "note" to "Sås",
                    "order" to 1,
                    "is_header" to true,
                    "no_amount" to false,
                ),
                ingredient(1, "tsk", "timjan", order = 2),
            ),
        )
        val newStep = step(name = "Servera", instruction = "Servera med kokt potatis.", time = 0, order = 1, ingredients = emptyList())
        val body = JsonObject(
            recipeBody(
                name = "$RECIPE_NAME med timjan",
                keywords = before.arrAt("keywords").objects().mapNotNull { k ->
                    k.longAt("id")?.let { obj("id" to it, "name" to k.stringAt("name")) }
                },
                steps = listOf(editedFirst, newStep),
            ),
        )
        val response = api("recipe-patch-full", "PATCH", "/api/recipe/$id/", body)
        observe("recipe.patch", describe(response, includeBody = response.status != OK))
        val after = objOf(api("recipe-get-after-patch", "GET", "/api/recipe/$id/")) ?: return
        val afterSteps = after.arrAt("steps").objects()
        observe("recipe.patch.stepCount", afterSteps.size)
        observe("recipe.patch.firstStepIdKept", afterSteps.firstOrNull()?.longAt("id") == first.longAt("id"))
        observe("recipe.patch.firstStepName", afterSteps.firstOrNull()?.stringAt("name"))
        observe("recipe.patch.omittedStepDeleted", afterSteps.none { it.longAt("id") == steps[1].longAt("id") })
        val oldStepIds = steps.mapNotNull { it.longAt("id") }
        observe("recipe.patch.newStepHasNewId", afterSteps.getOrNull(1)?.longAt("id")?.let { it !in oldStepIds })
        val lines = afterSteps.firstOrNull()?.arrAt("ingredients").objects()
        observe("recipe.patch.ingredientIdsKept", lines.take(2).map { it.longAt("id") } == firstLines.take(2).map { it.longAt("id") })
        observe("recipe.patch.omittedIngredientsDeleted", lines.size == 3)
        observe("recipe.patch.amountChanged", lines.firstOrNull()?.doubleAt("amount"))
        observe("recipe.patch.foodReused", lines.firstOrNull()?.objAt("food")?.longAt("id") == foodIdBefore)
        observe("recipe.patch.newIngredientHasId", lines.getOrNull(2)?.longAt("id") != null)
        observe("recipe.patch.updatedAtBumped", after.stringAt("updated_at") != before.stringAt("updated_at"))
        observe("recipe.patch.keywordByIdKeptName", after.arrAt("keywords").objects().mapNotNull { it.stringAt("name") }.sorted())
        observe("recipe.patch.name", after.stringAt("name"))
        created = after
    }

    // --- c. Who may ------------------------------------------------------------------------------------

    @Test
    @Order(30)
    fun whoMay() {
        val id = recipeId ?: return observe("recipe.whoMay.skipped", "no recipe created")
        val current = objOf(read("/api/recipe/$id/")) ?: return
        val description = "description" to JsonPrimitive("Medlemmen skrev om beskrivningen.")
        val body = JsonObject(current.filterKeys { it in PATCH_FIELDS } + description)
        val member = api("recipe-patch-full-as-member", "PATCH", "/api/recipe/$id/", body, token = env.readerToken)
        observe("recipe.patch.member", describe(member, includeBody = member.status != OK))
        observe("recipe.patch.member.stepsKept", objOf(member)?.arrAt("steps")?.size == current.arrAt("steps").size)
        val guest = api("recipe-patch-full-as-guest", "PATCH", "/api/recipe/$id/", body, token = env.guestToken)
        observe("recipe.patch.guest", describe(guest, includeBody = false))
        val guestDelete = api("recipe-delete-as-guest", "DELETE", "/api/recipe/$id/", token = env.guestToken)
        observe("recipe.delete.guest", describe(guestDelete, includeBody = false))
        observe("recipe.delete.guest.stillThere", read("/api/recipe/$id/").status == OK)
    }

    // --- e. Import from a source (Fas 3 import lane) ---------------------------------------------------

    @Test
    @Order(35)
    fun importFromSource() {
        // The page as the app would send it (`data`): schema.org JSON-LD in HTML, the way recipe sites publish it.
        val html = jsonLdPage(name = RECIPE_NAME)
        val fromData = api("recipe-from-source-data", "POST", "/api/recipe-from-source/", obj("data" to html))
        observe("import.data", describe(fromData, includeBody = fromData.status != OK))
        val recipe = objOf(fromData)?.objAt("recipe")
        observe("import.data.name", recipe?.stringAt("name"))
        observe("import.data.servings", recipe?.get("servings"))
        observe("import.data.workingTime", recipe?.get("working_time"))
        observe("import.data.stepCount", recipe?.arrAt("steps")?.size)
        val lines = recipe?.arrAt("steps")?.objects()?.flatMap { it.arrAt("ingredients").objects() }.orEmpty()
        observe("import.data.foods", lines.map { it.objAt("food")?.stringAt("name") })
        observe("import.data.units", lines.map { it.objAt("unit")?.stringAt("name") })
        observe("import.data.amounts", lines.map { it.doubleAt("amount") })
        observe("import.data.originalTexts", lines.map { it.stringAt("original_text") })
        observe("import.data.keywords", recipe?.arrAt("keywords")?.objects()?.map { it.stringAt("name") })
        observe("import.data.imageUrl", recipe?.stringAt("image_url"))
        observe("import.data.images", objOf(fromData)?.arrAt("images"))
        observe("import.data.error", objOf(fromData)?.get("error"))
        // The recipe of step a has this name: the server names it as a duplicate.
        observe("import.data.duplicates", objOf(fromData)?.arrAt("duplicates")?.objects()?.map { it.stringAt("name") })

        // A page without a recipe (plain text) and a link the server cannot fetch.
        val plainBody = obj("data" to "Bara lite text utan recept.")
        val plain = api("recipe-from-source-plain-text", "POST", "/api/recipe-from-source/", plainBody)
        observe("import.plainText", describe(plain))
        val badUrlBody = obj("url" to "https://recipes.invalid/pannkakor")
        val badUrl = api("recipe-from-source-bad-url", "POST", "/api/recipe-from-source/", badUrlBody)
        observe("import.badUrl", describe(badUrl))
        val empty = api("recipe-from-source-empty", "POST", "/api/recipe-from-source/", obj())
        observe("import.empty", describe(empty))
        val guestBody = obj("data" to html)
        val guest = api("recipe-from-source-as-guest", "POST", "/api/recipe-from-source/", guestBody, token = env.guestToken)
        observe("import.guest", describe(guest, includeBody = guest.status != OK))
    }

    // --- f. The AI import without a provider (Fas 3 import lane) ---------------------------------------

    @Test
    @Order(36)
    fun aiImportWithoutProvider() {
        // The contract space has no AI provider (`space/current` `ai_default_provider` null): the
        // app never sends then; this records what a server answers an app that still tries, with
        // the multipart shape the app uses (`ai_provider_id`, `file`).
        val space = objOf(read("/api/space/current/"))
        observe("import.ai.spaceAiEnabled", space?.get("ai_enabled"))
        observe("import.ai.spaceDefaultProvider", space?.get("ai_default_provider"))
        val png = tinyPng()
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("ai_provider_id", "999999")
            .addFormDataPart("file", "recept.png", png.toRequestBody(PNG))
            .build()
        val recordBody = obj("ai_provider_id" to "999999", "file" to "<multipart file recept.png, image/png, ${png.size} bytes>")
        val answer = http.call(
            "ai-import-no-provider",
            "POST",
            "/api/ai-import/",
            bearer = env.fas1Token,
            body = form,
            recordBody = recordBody,
        )
        observe("import.ai.noProvider", describe(answer))
        val imageUrl = recipeId?.let { id ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image_url", "https://recipes.invalid/bilder/pannkakor.jpg")
                .build()
            http.call(
                "recipe-image-url-put",
                "PUT",
                "/api/recipe/$id/image/",
                bearer = env.fas1Token,
                body = body,
                recordBody = obj("image_url" to "https://recipes.invalid/bilder/pannkakor.jpg"),
            )
        }
        // `image_url` the server cannot fetch: the app drops the image row on 400 and keeps the recipe.
        observe("import.imageUrl.unreachable", imageUrl?.let { describe(it) })
    }

    // --- d. Delete -------------------------------------------------------------------------------------

    @Test
    @Order(40)
    fun delete() {
        val id = recipeId ?: return observe("recipe.delete.skipped", "no recipe created")
        val deleted = api("recipe-delete-full", "DELETE", "/api/recipe/$id/")
        observe("recipe.delete", describe(deleted, includeBody = false))
        val gone = api("recipe-deleted-get-full", "GET", "/api/recipe/$id/")
        observe("recipe.deleted.get", describe(gone))
        val again = api("recipe-delete-again", "DELETE", "/api/recipe/$id/", record = false)
        observe("recipe.delete.again.status", again.status)
    }

    // --- Baseline ---------------------------------------------------------------------------------

    @Test
    @Order(99)
    fun everyFactMatchesTheBaseline() {
        for ((key, expected) in EXPECTED) {
            val described = (facts[key.removeSuffix(".status")] as? JsonObject)?.get("status")
            val observed = facts[key] ?: described.takeIf { key.endsWith(".status") }
            if (observed != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed $observed"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the Fas 3 baseline:\n" + mismatches.joinToString("\n"))
    }

    // --- Helpers ------------------------------------------------------------------------------------

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
        body = (body ?: JsonObject(emptyMap()).takeIf { method in BODY_METHODS })?.toString()?.toRequestBody(JSON),
        recordBody = body,
        record = record,
    )

    private fun read(path: String, token: String = env.fas1Token): Exchange = api("read", "GET", path, token = token, record = false)

    private fun recipeBody(name: String, keywords: List<JsonObject>, steps: List<JsonObject>): JsonObject = obj(
        "name" to name,
        "description" to "Vardagsmat från appen.",
        "servings" to 4,
        "servings_text" to "portioner",
        "working_time" to 20,
        "waiting_time" to 30,
        "source_url" to "",
        "private" to false,
        "internal" to true,
        "keywords" to keywords,
        "steps" to steps,
    )

    private fun step(name: String, instruction: String, time: Int, order: Int, ingredients: List<JsonObject>): JsonObject = obj(
        "name" to name,
        "instruction" to instruction,
        "time" to time,
        "order" to order,
        "show_as_header" to false,
        "show_ingredients_table" to true,
        "ingredients" to ingredients,
    )

    private fun ingredient(
        amount: Number,
        unit: String?,
        food: String,
        order: Int,
        note: String = "",
        noAmount: Boolean = false,
    ): JsonObject = obj(
        "food" to obj("name" to food),
        "unit" to unit?.let { obj("name" to it) },
        "amount" to amount,
        "note" to note,
        "order" to order,
        "is_header" to false,
        "no_amount" to noAmount,
    )

    /** A recipe page as sites publish it: schema.org `Recipe` as JSON-LD (what Tandoor's scraper reads first). */
    private fun jsonLdPage(name: String): String {
        val ld = obj(
            "@context" to "https://schema.org",
            "@type" to "Recipe",
            "name" to name,
            "description" to "Importerad av Tandems kontraktstest.",
            "image" to listOf("https://recipes.invalid/bilder/fiskgratang.jpg"),
            "recipeYield" to "4 portioner",
            "prepTime" to "PT20M",
            "cookTime" to "PT30M",
            "keywords" to "Fisk, Vardag",
            "recipeIngredient" to listOf("600 g torskfilé", "2,5 dl vispgrädde", "salt"),
            "recipeInstructions" to listOf(
                obj("@type" to "HowToStep", "text" to "Skär fisken i bitar och lägg i en form."),
                obj("@type" to "HowToStep", "text" to "Häll över grädden och grädda i 200 grader i 30 minuter."),
            ),
        )
        return "<html><head><title>$name</title><script type=\"application/ld+json\">$ld</script></head>" +
            "<body><h1>$name</h1></body></html>"
    }

    private fun tinyPng(): ByteArray {
        val image = BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until IMAGE_SIZE) for (y in 0 until IMAGE_SIZE) image.setRGB(x, y, PNG_COLOR)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun header(text: String, order: Int): JsonObject =
        obj("food" to null, "unit" to null, "amount" to 0, "note" to text, "order" to order, "is_header" to true, "no_amount" to false)

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun describe(ex: Exchange, includeBody: Boolean = true): Map<String, Any?> = buildMap {
        put("status", ex.status)
        put("contentType", ex.contentType?.substringBefore(';'))
        put("isJson", ex.json != null)
        if (includeBody) put("body", ex.json ?: JsonPrimitive(ex.bodyText.take(MAX_TEXT)))
    }

    private fun objOf(ex: Exchange): JsonObject? = ex.json as? JsonObject

    private fun pageOrArray(json: JsonElement?): List<JsonObject> = when (json) {
        is JsonArray -> json.objects()
        is JsonObject -> json.arrAt("results").objects()
        else -> emptyList()
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    private companion object {
        val JSON = "application/json".toMediaType()
        val PNG = "image/png".toMediaType()
        const val IMAGE_SIZE = 8
        const val PNG_COLOR = 0x3A6EA5
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val OK = 200
        const val CREATED = 201
        const val MAX_TEXT = 200
        const val RECIPE_NAME = "Fiskgratäng från Tandem"
        const val NEW_KEYWORD = "Tandem-redigerad"

        /** The fields of a recipe the app's PATCH sends (what the member's edit carries). */
        val PATCH_FIELDS = setOf(
            "name", "description", "servings", "servings_text", "working_time", "waiting_time", "source_url", "private", "internal",
            "keywords", "steps",
        )

        /**
         * The facts the app relies on (api-schema/fixtures/<version>/FACTS.md "Fas 3", docs/BYGGE.md
         * "Fas 3 – kontrakt"). A key ending in `.status` also matches the status of a recorded
         * response description. Every other fact is only recorded; tandoor-watch compares it.
         */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "recipe.create.keywordIdOnly.status" to 400,
            "recipe.create.status" to 201,
            "recipe.create.internal" to true,
            "recipe.create.stepCount" to 2,
            "recipe.create.stepsHaveIds" to true,
            "recipe.create.ingredientsHaveIds" to true,
            "recipe.create.foods" to listOf("torskfilé", null, "vispgrädde", "salt"),
            "recipe.create.units" to listOf("g", null, "dl", null),
            "recipe.create.header" to true,
            "recipe.create.headerNote" to "Sås",
            "recipe.create.noAmount" to true,
            "recipe.create.keywordByIdKeptName" to true,
            "recipe.patch.status" to 200,
            "recipe.patch.stepCount" to 2,
            "recipe.patch.firstStepIdKept" to true,
            "recipe.patch.firstStepName" to "Förbered allt",
            "recipe.patch.omittedStepDeleted" to true,
            "recipe.patch.newStepHasNewId" to true,
            "recipe.patch.ingredientIdsKept" to true,
            "recipe.patch.omittedIngredientsDeleted" to true,
            "recipe.patch.amountChanged" to 700.0,
            "recipe.patch.foodReused" to true,
            "recipe.patch.newIngredientHasId" to true,
            "recipe.patch.updatedAtBumped" to true,
            "recipe.patch.member.status" to 200,
            "recipe.patch.member.stepsKept" to true,
            "recipe.patch.guest.status" to 403,
            "recipe.delete.guest.status" to 403,
            "recipe.delete.guest.stillThere" to true,
            "recipe.delete.status" to 204,
            "recipe.deleted.get.status" to 404,
            "recipe.delete.again.status" to 404,
            // e. (import lane) the server parses a page with JSON-LD without creating the recipe.
            "import.data.status" to 200,
            "import.data.name" to RECIPE_NAME,
            "import.data.error" to false,
            "import.data.stepCount" to 2,
            "import.data.foods" to listOf("torskfilé", "vispgrädde", "salt"),
            "import.empty.status" to 400,
            "import.guest.status" to 403,
        )
    }
}
