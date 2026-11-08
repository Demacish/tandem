package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
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
 * Fas 5 (kok lane, plan §8.1 "Pannstorlek"): what the app rests on when it scales a recipe by its
 * round tin. `Recipe.diameter` is an integer (0 = no tin) next to a free-text `diameter_text`
 * ("cm"); the detail carries both, so the stored recipe scales offline; the list's overview does
 * not (the app scales only with the detail); a decimal or null diameter is refused, a negative one
 * stored (the app reads anything not positive as "no tin"). The scaling itself is the web's
 * (vue3 `RecipeScalingDialog.changeDiameter`, FACTS "Fas 5: pannstorlek"), nothing on the server.
 *
 * Recordings go to `build/contract-recordings/<tag>/fas5/kok/` and are committed to
 * `api-schema/fixtures/<version>/fas5/kok/`. Soft checks as in the other phases: every fact is
 * recorded and the last step compares [EXPECTED].
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase5PanContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas5/kok")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val recipes = mutableListOf<Long>()
    private var cake: Long? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun cleanUp() {
        recipes.forEach { api("cleanup", "DELETE", "/api/recipe/$it/", record = false) }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    @Test
    @Order(10)
    fun aRecipeInARoundTin() {
        val created = recipe("kaka", "Tandem kladdkaka i form", "diameter" to 24, "diameter_text" to "cm")
        observe("tin.create.status", created?.first)
        val id = created?.second ?: return
        cake = id
        val detail = api("kaka-detalj", "GET", "/api/recipe/$id/").json as? JsonObject
        observe("tin.detail.diameter", detail?.get("diameter"))
        observe("tin.detail.diameterText", detail?.get("diameter_text"))
        observe("tin.detail.diameterType", detail?.get("diameter")?.let(::kind))
    }

    @Test
    @Order(20)
    fun aRecipeWithoutTin() {
        val id = recipe("utan-form", "Tandem soppa utan form")?.second ?: return
        val detail = api("utan-form-detalj", "GET", "/api/recipe/$id/").json as? JsonObject
        observe("none.detail.diameter", detail?.get("diameter"))
        observe("none.detail.diameterText", detail?.get("diameter_text"))
    }

    @Test
    @Order(30)
    fun theListHasNoTin() {
        val id = cake ?: return
        val list = api("lista", "GET", "/api/recipe/?query=kladdkaka&page_size=5").json as? JsonObject
        val item = (list?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.longAt("id") == id }
        observe("list.found", item != null)
        observe("list.hasDiameter", item?.containsKey("diameter"))
        observe("list.hasDiameterText", item?.containsKey("diameter_text"))
    }

    @Test
    @Order(40)
    fun whatTheFieldTakes() {
        val id = cake ?: return
        val smaller = api("andra-till-20", "PATCH", "/api/recipe/$id/", obj("diameter" to 20))
        observe("patch.20.status", smaller.status)
        observe("patch.20.diameter", (smaller.json as? JsonObject)?.get("diameter"))
        observe("patch.decimal.status", api("decimal", "PATCH", "/api/recipe/$id/", obj("diameter" to 22.5)).status)
        observe("patch.null.status", api("null", "PATCH", "/api/recipe/$id/", obj("diameter" to null)).status)
        val negative = api("negativ", "PATCH", "/api/recipe/$id/", obj("diameter" to -4))
        observe("patch.negative.status", negative.status)
        observe("patch.negative.diameter", (negative.json as? JsonObject)?.get("diameter"))
        observe("patch.longText.status", api("lang-text", "PATCH", "/api/recipe/$id/", obj("diameter_text" to "x".repeat(33))).status)
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the tin size baseline:\n" + mismatches.joinToString("\n"))
    }

    /** POSTs a minimal recipe; its status and id. */
    private fun recipe(slug: String, name: String, vararg extra: Pair<String, Any?>): Pair<Int, Long?>? {
        val own = listOf<Pair<String, Any?>>(
            "name" to name,
            "description" to "",
            "servings" to 4,
            "servings_text" to "",
            "working_time" to 0,
            "waiting_time" to 0,
            "internal" to true,
            "keywords" to JsonArray(emptyList()),
            "steps" to JsonArray(emptyList()),
        )
        val created = api("recept-$slug", "POST", "/api/recipe/", obj(*(own + extra).toTypedArray()))
        val id = (created.json as? JsonObject)?.longAt("id")
        id?.let(recipes::add)
        return created.status to id
    }

    private fun api(name: String, method: String, path: String, body: JsonElement? = null, record: Boolean = true): Exchange = http.call(
        name,
        method,
        path,
        bearer = env.fas1Token,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in BODY_METHODS })?.toString()?.toRequestBody(JSON),
        recordBody = body,
        record = record,
    )

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    /** "integer", "decimal", "string", "null" or the JSON kind. */
    private fun kind(element: JsonElement): String = when {
        element is JsonNull -> "null"
        element is JsonPrimitive && element.isString -> "string"
        element is JsonPrimitive && element.content.toLongOrNull() != null -> "integer"
        element is JsonPrimitive -> "decimal"
        else -> "other"
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        /** What the app rests on. */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "tin.create.status" to 201,
            "tin.detail.diameter" to 24,
            "tin.detail.diameterText" to "cm",
            "tin.detail.diameterType" to "integer",
            "none.detail.diameter" to 0,
            "none.detail.diameterText" to "",
            "list.found" to true,
            "list.hasDiameter" to false,
            "patch.20.status" to 200,
            "patch.20.diameter" to 20,
            "patch.decimal.status" to 400,
            "patch.null.status" to 400,
            "patch.negative.status" to 200,
            "patch.negative.diameter" to -4,
            "patch.longText.status" to 400,
        )
    }
}
