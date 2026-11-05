package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.sync.recipeedit.FakeRecipeEditTandoor
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * Tandoor's import for the tests (FACTS "Fas 3 e-f"): `POST /api/recipe-from-source/` answers a
 * parsed recipe for a known link or an HTML page that holds one, `{error: true, msg}` with 200
 * for a page without a recipe, 400 for a link it cannot fetch and the 500 error page for plain
 * text; `POST /api/ai-import/` answers the same recipe when the space has a provider and the body
 * carries a file, the 500 page without a provider; `GET /api/space/current/` carries [providerId] as the default
 * provider. Every other request goes to [edits] (the editor's fake) and then the fixtures.
 */
internal class FakeRecipeImportTandoor(private val edits: FakeRecipeEditTandoor, private val version: String) {
    /** The space's `ai_default_provider.id`, null for a space without one. */
    var providerId: Long? = null

    /** Every import request this fake answered (not `space/current`, which the sync reads too), as `METHOD path` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** Runs while an import request is being answered (to look at the sync guard meanwhile). */
    var duringCall: (() -> Unit)? = null

    /** `ai-step-sort` answers a recipe with one step less (an AI that drops a step). */
    var stepSortDropsAStep: Boolean = false

    fun install(server: FakeTandoorServer) {
        server.handler = { request -> answer(request) ?: edits.answer(request) }
    }

    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val response = when {
            path == FROM_SOURCE && method == "POST" -> fromSource(parse(text))
            path == AI_IMPORT && method == "POST" -> aiImport(text)
            path == STEP_SORT && method == "POST" -> stepSort(request.url.queryParameter("provider"), parse(text))
            path == SPACE && method == "GET" -> return space()
            else -> return null
        }
        duringCall?.invoke()
        calls += "$method $path" to text
        return response
    }

    private fun fromSource(body: JsonObject): MockResponse {
        val url = (body["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val data = (body["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            // With `data` the link is not fetched and `source_url` stays empty (FACTS "Fas 4: Fas 3:s rester" a).
            data.contains("<html") -> json(200, found(sourceUrl = ""))
            url == KNOWN_URL -> json(200, found(sourceUrl = url))
            url == NO_RECIPE_URL -> json(200, error("No usable data could be found."))
            url.isNotEmpty() -> json(400, error("Connection Refused."))
            data.isNotEmpty() -> html500()
            else -> json(400, error("Nothing to do."))
        }
    }

    private fun aiImport(text: String): MockResponse = when {
        providerId == null -> html500()
        !text.contains("name=\"file\"") -> json(400, error("file missing"))
        else -> json(200, found(sourceUrl = ""))
    }

    /**
     * `ai-step-sort` (#40): 400 `{error, msg}` without a provider (FACTS "Fas 4: Fas 3:s rester" b);
     * with one, the recipe back with the LAST line of the first step moved to the second step
     * (what a sorting AI does), or with the last step dropped ([stepSortDropsAStep]).
     */
    private fun stepSort(provider: String?, recipe: JsonObject): MockResponse {
        val lines = recipe["steps"]?.let { it as? JsonArray }.orEmpty()
            .flatMap { it.jsonObject["ingredients"] as? JsonArray ?: JsonArray(emptyList()) }
        if (lines.any { it.jsonObject["order"] == null }) return json(400, error("order: This field may not be null."))
        if (provider == null || providerId?.toString() != provider) return json(400, error("You must select an AI provider."))
        val steps = recipe["steps"]?.let { it as? JsonArray }.orEmpty().map { it.jsonObject }.toMutableList()
        if (stepSortDropsAStep) return json(200, JsonObject(recipe + ("steps" to JsonArray(steps.dropLast(1)))))
        if (steps.size >= 2) {
            val first = steps[0]["ingredients"]?.let { it as? JsonArray }.orEmpty()
            val second = steps[1]["ingredients"]?.let { it as? JsonArray }.orEmpty()
            if (first.isNotEmpty()) {
                steps[0] = JsonObject(steps[0] + ("ingredients" to JsonArray(first.dropLast(1))))
                steps[1] = JsonObject(steps[1] + ("ingredients" to JsonArray(listOf(first.last()) + second)))
            }
        }
        return json(200, JsonObject(recipe + ("steps" to JsonArray(steps))))
    }

    /** Tandoor's unhandled error page (plain text to recipe-from-source, ai-import without a provider; FACTS "Fas 3 e-f"). */
    private fun html500(): MockResponse = MockResponse.Builder()
        .code(500)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(ERROR_PAGE)
        .build()

    private fun error(message: String): JsonObject = buildJsonObject {
        put("error", true)
        put("msg", message)
    }

    private fun space(): MockResponse {
        val fixture = Json.parseToJsonElement(TandoorFixtures.exchange("fas1/space-current", version).body!!).jsonObject
        val provider = providerId?.let { id ->
            buildJsonObject {
                put("id", id)
                put("name", "Test AI")
                put("model_name", "test")
            }
        } ?: JsonNull
        return json(200, JsonObject(fixture + ("ai_default_provider" to provider)))
    }

    /** The recipe both imports answer (shape `RecipeFromSourceResponse`). */
    private fun found(sourceUrl: String): JsonObject = buildJsonObject {
        put(
            "recipe",
            buildJsonObject {
                put("name", "Pannkakor")
                put("description", "Klassiska tunna pannkakor.")
                put("servings", 4)
                put("servings_text", "portioner")
                put("working_time", 25)
                put("waiting_time", 0)
                put("source_url", sourceUrl)
                put("image_url", IMAGE)
                put("internal", true)
                put(
                    "keywords",
                    buildJsonArray {
                        add(keyword(5, "Husmanskost", imported = true))
                        add(keyword(null, "Mellanmål", imported = true))
                        add(keyword(null, "Skippad", imported = false))
                    },
                )
                put(
                    "steps",
                    buildJsonArray {
                        add(
                            step(
                                "Vispa ihop allt till en slät smet.",
                                ingredient(2.5, "dl", "vetemjöl", original = "2,5 dl vetemjöl"),
                                ingredient(0.0, null, "salt", original = "salt"),
                                ingredient(0.0, null, null, original = "en nypa kärlek"),
                            ),
                        )
                        add(step("Grädda tunna pannkakor.", ingredient(2.0, "msk", "smör", "till stekning", "2 msk smör")))
                    },
                )
            },
        )
        put(
            "images",
            buildJsonArray {
                add(JsonPrimitive(IMAGE))
                add(JsonPrimitive("$IMAGE?2"))
            },
        )
        put("error", false)
        put("msg", "")
        put(
            "duplicates",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", 1)
                        put("name", "Pannkakor")
                    },
                )
            },
        )
    }

    private fun keyword(id: Long?, name: String, imported: Boolean): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        put("label", name)
        put("name", name)
        put("import_keyword", imported)
    }

    private fun step(instruction: String, vararg ingredients: JsonObject): JsonObject = buildJsonObject {
        put("instruction", instruction)
        put("ingredients", JsonArray(ingredients.toList()))
        put("show_ingredients_table", true)
    }

    private fun ingredient(amount: Double, unit: String?, food: String?, note: String = "", original: String): JsonObject =
        buildJsonObject {
            put("amount", amount)
            put("food", if (food == null) JsonNull else buildJsonObject { put("name", food) })
            put("unit", if (unit == null) JsonNull else buildJsonObject { put("name", unit) })
            put("note", note)
            put("original_text", original)
        }

    private fun parse(text: String): JsonObject = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

    private fun json(code: Int, body: JsonElement): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body.toString())
        .build()

    companion object {
        const val KNOWN_URL = "https://example.com/recept/pannkakor"
        const val NO_RECIPE_URL = "https://example.com/annat"
        private const val ERROR_PAGE =
            "<!doctype html><html lang=\"en\"><head><title>Server Error (500)</title></head><body><h1>Server Error (500)</h1></body></html>"
        const val IMAGE = "https://example.com/bilder/pannkakor.jpg"
        private const val FROM_SOURCE = "/api/recipe-from-source/"
        private const val AI_IMPORT = "/api/ai-import/"
        private const val SPACE = "/api/space/current/"
        private const val STEP_SORT = "/api/ai-step-sort/"
    }
}
