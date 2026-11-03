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
 * parsed recipe for a known link or a text that holds one, `{error: true, msg}` otherwise;
 * `POST /api/ai-import/` answers the same recipe when the space has a provider and the body
 * carries a file, else 400; `GET /api/space/current/` carries [providerId] as the default
 * provider. Every other request goes to [edits] (the editor's fake) and then the fixtures.
 */
internal class FakeRecipeImportTandoor(private val edits: FakeRecipeEditTandoor, private val version: String) {
    /** The space's `ai_default_provider.id`, null for a space without one. */
    var providerId: Long? = null

    /** Every import request this fake answered, as `METHOD path` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

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
            path == SPACE && method == "GET" -> space()
            else -> return null
        }
        calls += "$method $path" to text
        return response
    }

    private fun fromSource(body: JsonObject): MockResponse {
        val url = (body["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val data = (body["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            url == KNOWN_URL || data.contains("Pannkakor") -> json(200, found(sourceUrl = url))
            url.isNotEmpty() -> json(200, error("No usable data could be found."))
            else -> json(200, error("No recipe found in the text."))
        }
    }

    private fun aiImport(text: String): MockResponse = when {
        providerId == null || !text.contains("name=\"file\"") -> json(400, error("AI provider missing"))
        else -> json(200, found(sourceUrl = ""))
    }

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

    private fun ingredient(
        amount: Double,
        unit: String?,
        food: String?,
        note: String = "",
        original: String,
    ): JsonObject = buildJsonObject {
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
        const val IMAGE = "https://example.com/bilder/pannkakor.jpg"
        private const val FROM_SOURCE = "/api/recipe-from-source/"
        private const val AI_IMPORT = "/api/ai-import/"
        private const val SPACE = "/api/space/current/"
    }
}
