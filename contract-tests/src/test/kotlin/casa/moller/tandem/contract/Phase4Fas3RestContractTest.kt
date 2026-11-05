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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
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
 * Server facts of Fas 3's leftovers, built in Fas 4 (lane "fas3-rester", plan §10 "Fas 3", §8.4):
 *
 * - a. #38, the WebView fallback: `POST /api/recipe-from-source/` with BOTH `url` (the shared
 *   link) and `data` (the page's HTML as a WebView read it) parses the page without fetching the
 *   link; the link becomes the recipe's `source_url`. A `%` in the page survives (the server
 *   URL-unquotes `data`). A page without a recipe answers an error.
 * - b. #40, `POST /api/ai-step-sort/` (Recipe in, Recipe out): what a space without an AI
 *   provider answers, with and without `?provider=`, and the guest.
 * - c. Deleting a recipe's image: `PUT /api/recipe/{id}/image/` (multipart) with a PNG sets an
 *   image; the variants an app can send to remove it are recorded (the web sends an empty form).
 * - d. A change of only one step (`PATCH /api/step/{id}/`, `PATCH /api/ingredient/{id}/`): does
 *   the recipe's `updated_at` move? The editor's conflict check depends on the answer.
 *
 * Runs after Phase3ContractTest against the same throwaway server. Recordings go to
 * `build/contract-recordings/<tag>/fas4/fas3-rester/` and are committed to
 * `api-schema/fixtures/<version>/fas4/fas3-rester/`. Soft checks as in the other phases: every
 * fact is recorded and the last step compares `EXPECTED` (docs/BYGGE.md "Fas 3:s rester").
 *
 * Skipped unless TANDOOR_GUEST_TOKEN is set. Never run against a production server.
 */
@Suppress("LongMethod")
@EnabledIfEnvironmentVariable(named = "TANDOOR_GUEST_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase4Fas3RestContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas4/fas3-rester")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private var recipeId: Long? = null
    private var imported: JsonObject? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        assertTrue(env.guestToken.isNotBlank(), "TANDOOR_GUEST_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        sanitizer.fakeTokenFor(env.readerToken)
        sanitizer.fakeTokenFor(env.guestToken)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun writeFacts() {
        recipeId?.let { api("cleanup-recipe", "DELETE", "/api/recipe/$it/", record = false) }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    // --- a. #38: the page's HTML from a WebView, together with the shared link ------------------------

    @Test
    @Order(10)
    fun pageHtmlWithLink() {
        val html = jsonLdPage(RECIPE_NAME)
        // The link is unreachable from the server on purpose (a site that blocks the server): with
        // `data` the server must not fetch it.
        val both = api("recipe-from-source-url-and-data", "POST", "/api/recipe-from-source/", obj("url" to PAGE_URL, "data" to html))
        observe("webview.urlAndData", describe(both, includeBody = both.status != OK))
        val recipe = objOf(both)?.objAt("recipe")
        imported = recipe
        observe("webview.urlAndData.error", objOf(both)?.get("error"))
        observe("webview.urlAndData.name", recipe?.stringAt("name"))
        observe("webview.urlAndData.sourceUrl", recipe?.stringAt("source_url"))
        observe("webview.urlAndData.stepCount", recipe?.arrAt("steps")?.size)
        observe("webview.urlAndData.description", recipe?.stringAt("description"))
        val lines = recipe?.arrAt("steps")?.objects()?.flatMap { it.arrAt("ingredients").objects() }.orEmpty()
        observe("webview.urlAndData.foods", lines.map { it.objAt("food")?.stringAt("name") })
        observe("webview.urlAndData.imageUrl", recipe?.stringAt("image_url"))
        observe("webview.urlAndData.duplicates", objOf(both)?.arrAt("duplicates")?.objects()?.map { it.stringAt("name") })

        // A page a WebView read that has no recipe in it (no JSON-LD, no microdata).
        val noRecipe = "<html><head><title>Om oss</title></head><body><h1>Om oss</h1><p>Inget recept här.</p></body></html>"
        val noneBody = obj("url" to PAGE_URL, "data" to noRecipe)
        val none = api("recipe-from-source-page-without-recipe", "POST", "/api/recipe-from-source/", noneBody)
        observe("webview.noRecipe", describe(none))
        val guestBody = obj("url" to PAGE_URL, "data" to html)
        val guest = api("recipe-from-source-url-and-data-as-guest", "POST", "/api/recipe-from-source/", guestBody, token = env.guestToken)
        observe("webview.guest", describe(guest, includeBody = guest.status != OK))
    }

    // --- b. #40: ai-step-sort on a space without an AI provider ---------------------------------------

    @Test
    @Order(20)
    fun aiStepSortWithoutProvider() {
        val space = objOf(read("/api/space/current/"))
        observe("stepSort.spaceDefaultProvider", space?.get("ai_default_provider"))
        val body = imported ?: obj("name" to RECIPE_NAME, "steps" to JsonArray(emptyList()))
        val noParam = api("ai-step-sort-without-provider-param", "POST", "/api/ai-step-sort/", body)
        observe("stepSort.noProviderParam", describe(noParam))
        val unknown = api("ai-step-sort-unknown-provider", "POST", "/api/ai-step-sort/?provider=999999", body)
        observe("stepSort.unknownProvider", describe(unknown))
        val guest = api("ai-step-sort-as-guest", "POST", "/api/ai-step-sort/?provider=999999", body, token = env.guestToken)
        observe("stepSort.guest", describe(guest, includeBody = false))
        val providers = read("/api/ai-provider/")
        observe("stepSort.providers", describe(providers, includeBody = false))
    }

    // --- c. Removing a recipe's image -----------------------------------------------------------------

    @Test
    @Order(30)
    fun removeImage() {
        val created = api(
            "recipe-create-for-image",
            "POST",
            "/api/recipe/",
            obj("name" to IMAGE_RECIPE_NAME, "internal" to true, "steps" to listOf(step("Blanda allt.", 0))),
        )
        observe("image.create.status", created.status)
        val id = objOf(created)?.longAt("id") ?: return observe("image.skipped", "no recipe created")
        recipeId = id

        val variants = linkedMapOf<String, () -> RequestBody>(
            // What Tandoor's web editor sends ("delete image"): a multipart form without parts.
            "emptyForm" to { EmptyMultipart },
            // A blank `image` part (DRF turns '' into null for a nullable field from a form).
            "blankImage" to { MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image", "").build() },
            // A blank `image_url` part.
            "blankImageUrl" to { MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image_url", "").build() },
        )
        for ((variant, removal) in variants) {
            val upload = putImage("recipe-image-upload-before-$variant", id, png())
            observe("image.upload.$variant.status", upload.status)
            observe("image.upload.$variant.hasImage", objOf(read("/api/recipe/$id/"))?.stringAt("image") != null)
            val removed = http.call(
                "recipe-image-remove-$variant",
                "PUT",
                "/api/recipe/$id/image/",
                bearer = env.fas1Token,
                body = removal(),
                recordBody = obj("variant" to variant),
            )
            observe("image.remove.$variant", describe(removed))
            observe("image.remove.$variant.imageAfter", objOf(read("/api/recipe/$id/"))?.stringAt("image"))
        }
        val guest = http.call(
            "recipe-image-remove-as-guest",
            "PUT",
            "/api/recipe/$id/image/",
            bearer = env.guestToken,
            body = EmptyMultipart,
            recordBody = obj("variant" to "emptyForm"),
        )
        observe("image.remove.guest", describe(guest, includeBody = false))
        // A JSON PATCH of the recipe with `image: null` (the field is read-only on the recipe).
        putImage("recipe-image-upload-before-patch", id, png())
        val patched = api("recipe-patch-image-null", "PATCH", "/api/recipe/$id/", obj("image" to null))
        observe("image.patchNull.status", patched.status)
        observe("image.patchNull.imageAfter", objOf(read("/api/recipe/$id/"))?.stringAt("image"))
    }

    // --- d. A change of one step only -----------------------------------------------------------------

    @Test
    @Order(40)
    fun stepOnlyChange() {
        val id = recipeId ?: return observe("stepChange.skipped", "no recipe created")
        val before = objOf(read("/api/recipe/$id/"))
        val updatedAt = before?.stringAt("updated_at")
        val step = before?.arrAt("steps")?.objects()?.firstOrNull()
        val stepId = step?.longAt("id") ?: return observe("stepChange.skipped", "no step")
        Thread.sleep(SECOND_MILLIS)
        val patched = api("step-patch-instruction", "PATCH", "/api/step/$stepId/", obj("instruction" to "Blanda allt noga."))
        observe("stepChange.step.status", patched.status)
        val after = objOf(read("/api/recipe/$id/"))
        observe("stepChange.step.updatedAtMoved", after?.stringAt("updated_at") != updatedAt)
        observe("stepChange.step.instructionSeen", after?.arrAt("steps")?.objects()?.firstOrNull()?.stringAt("instruction"))

        val ingredientId = after?.arrAt("steps")?.objects()?.firstOrNull()?.arrAt("ingredients")?.objects()?.firstOrNull()?.longAt("id")
        val updatedAt2 = after?.stringAt("updated_at")
        Thread.sleep(SECOND_MILLIS)
        val ingredient = ingredientId?.let { api("ingredient-patch-amount", "PATCH", "/api/ingredient/$it/", obj("amount" to 3)) }
        observe("stepChange.ingredient.status", ingredient?.status)
        observe("stepChange.ingredient.updatedAtMoved", objOf(read("/api/recipe/$id/"))?.stringAt("updated_at") != updatedAt2)
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
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the fas3-rester baseline:\n" + mismatches.joinToString("\n"))
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private fun putImage(name: String, id: Long, bytes: ByteArray): Exchange = http.call(
        name,
        "PUT",
        "/api/recipe/$id/image/",
        bearer = env.fas1Token,
        body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image", "bild.png", bytes.toRequestBody(PNG)).build(),
        recordBody = obj("image" to "<multipart file bild.png, image/png, ${bytes.size} bytes>"),
    )

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

    private fun step(instruction: String, order: Int): JsonObject = obj(
        "name" to "",
        "instruction" to instruction,
        "order" to order,
        "ingredients" to listOf(
            obj("food" to obj("name" to "mjöl"), "unit" to obj("name" to "dl"), "amount" to 2, "order" to 0, "note" to ""),
        ),
    )

    /** A recipe page as sites publish it (schema.org `Recipe` as JSON-LD), with a `%` in the text. */
    private fun jsonLdPage(name: String): String {
        val ld = obj(
            "@context" to "https://schema.org",
            "@type" to "Recipe",
            "name" to name,
            "description" to DESCRIPTION,
            "image" to listOf("https://recipes.invalid/bilder/kanelbullar.jpg"),
            "recipeYield" to "24",
            "prepTime" to "PT40M",
            "recipeIngredient" to listOf("5 dl mjölk", "150 g smör", "13 dl vetemjöl"),
            "recipeInstructions" to listOf(
                obj("@type" to "HowToStep", "text" to "Värm mjölken och smöret."),
                obj("@type" to "HowToStep", "text" to "Knåda degen och låt jäsa."),
            ),
        )
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>$name</title>" +
            "<script type=\"application/ld+json\">$ld</script></head><body><h1>$name</h1><p>$DESCRIPTION</p></body></html>"
    }

    private fun png(): ByteArray {
        val image = BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until IMAGE_SIZE) for (y in 0 until IMAGE_SIZE) image.setRGB(x, y, PNG_COLOR)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

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

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    /** A multipart form without parts (OkHttp's builder refuses one): only the closing boundary. */
    private object EmptyMultipart : RequestBody() {
        private const val BOUNDARY = "tandem-empty-form"
        override fun contentType() = "multipart/form-data; boundary=$BOUNDARY".toMediaType()
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("--$BOUNDARY--\r\n")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val PNG = "image/png".toMediaType()
        const val IMAGE_SIZE = 8
        const val PNG_COLOR = 0xA56E3A
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val OK = 200
        const val MAX_TEXT = 200
        const val SECOND_MILLIS = 1_100L
        const val RECIPE_NAME = "Kanelbullar från en blockerad sida"
        const val IMAGE_RECIPE_NAME = "Tandem bildtest"
        const val PAGE_URL = "https://recipes.invalid/kanelbullar"
        const val DESCRIPTION = "Saftiga bullar med 100% smör."

        /**
         * The facts the app relies on (FACTS.md "Fas 4: Fas 3:s rester", docs/BYGGE.md "Fas 3:s
         * rester"). A key ending in `.status` also matches the status of a recorded description.
         */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "webview.urlAndData.status" to 200,
            "webview.urlAndData.error" to false,
            "webview.urlAndData.name" to RECIPE_NAME,
            "webview.urlAndData.stepCount" to 2,
            "webview.urlAndData.foods" to listOf("mjölk", "smör", "vetemjöl"),
            "webview.guest.status" to 403,
            "image.create.status" to 201,
            "image.upload.emptyForm.hasImage" to true,
        )
    }
}
