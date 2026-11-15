package casa.moller.tandem.contract

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * Fas 5, komplettering efter enhetstestet (0.8.0, F1): Idag's row "Nya" asks
 * `GET /api/recipe/?new=true&sort_order=-created_at&page_size=20` (SyncEngine, home section NEW) and
 * shows the answer as it comes, newest first, without filtering on `new`. This class locks that
 * the server really answers newest first: three recipes created one after the other come first,
 * newest on top; the whole list is ordered by `created_at` descending; the back-dated recipe of the
 * Fas 1 seed (30 days old, `new: false`) comes after every new one; `page_size` is honoured; and
 * `sort_order=-created_at` alone gives the same top three (so the order does not rest on `new`).
 *
 * Recordings go to `build/contract-recordings/<tag>/fas5/nya/` and are committed to
 * `api-schema/fixtures/<version>/fas5/nya/`. Soft checks as in the other phases: every fact is
 * recorded and the last step compares [EXPECTED].
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase5NyaContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas5/nya")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val recipes = mutableListOf<Long>()

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
    fun threeRecipesOneAfterTheOther() {
        val statuses = NEW_RECIPES.mapIndexed { index, name -> recipe("recept-${index + 1}", name) }
        observe("create.statuses", statuses)
    }

    @Test
    @Order(20)
    fun theAppsRequestAnswersNewestFirst() {
        val page = api("nya", "GET", "/api/recipe/?new=true&sort_order=-created_at&page_size=$APP_PAGE_SIZE")
        observe("nya.status", page.status)
        val results = resultsOf(page.json)
        observe("nya.firstThree", results.take(NEW_RECIPES.size).map { it.stringAt("name") })
        observe("nya.ownAreNew", results.filter { it.stringAt("name") in NEW_RECIPES }.map { it.boolAt("new") }.distinct())
        observe("nya.pageSizeHonoured", results.size <= APP_PAGE_SIZE)
        observe("nya.hasCreatedAt", results.isNotEmpty() && results.all { it.containsKey("created_at") })
        observe("nya.sortedByCreatedDesc", sortedNewestFirst(results))
    }

    @Test
    @Order(30)
    fun theWholeListAndTheOldRecipe() {
        val all = api("nya-alla", "GET", "/api/recipe/?new=true&sort_order=-created_at&page_size=100")
        val results = resultsOf(all.json)
        val count = (all.json as? JsonObject)?.longAt("count")
        observe("all.complete", count != null && count == results.size.toLong())
        observe("all.sortedByCreatedDesc", sortedNewestFirst(results))
        val oldIndex = results.indexOfFirst { it.stringAt("name") == OLD_RECIPE }
        observe("all.oldRecipeFound", oldIndex >= 0)
        observe("all.oldRecipeNew", results.getOrNull(oldIndex)?.boolAt("new"))
        val lastNew = results.indexOfLast { it.boolAt("new") == true }
        observe("all.oldRecipeAfterEveryNew", oldIndex >= 0 && oldIndex > lastNew)
    }

    @Test
    @Order(40)
    fun theSortAloneGivesTheSameTop() {
        val sorted = api("bara-sortering", "GET", "/api/recipe/?sort_order=-created_at&page_size=$APP_PAGE_SIZE")
        observe("sortOnly.firstThree", resultsOf(sorted.json).take(NEW_RECIPES.size).map { it.stringAt("name") })
        // Only recorded (not in EXPECTED): what `new=true` alone does with the same recipes.
        val newOnly = api("bara-new", "GET", "/api/recipe/?new=true&page_size=$APP_PAGE_SIZE")
        observe("newOnly.firstThree", resultsOf(newOnly.json).take(NEW_RECIPES.size).map { it.stringAt("name") })
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the Nya baseline:\n" + mismatches.joinToString("\n"))
    }

    /** True when every `created_at` is at or before the one above it; null when one cannot be read. */
    private fun sortedNewestFirst(results: List<JsonObject>): Boolean? {
        val times = results.map { it.stringAt("created_at")?.let(::instantOf) ?: return null }
        return times.zipWithNext().all { (above, below) -> !below.isAfter(above) }
    }

    private fun instantOf(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

    private fun resultsOf(json: JsonElement?): List<JsonObject> = (json as? JsonObject)?.get("results").objects()

    /** POSTs a minimal recipe; its status. */
    private fun recipe(slug: String, name: String): Int {
        val body = obj(
            "name" to name,
            "description" to "",
            "servings" to 2,
            "servings_text" to "",
            "working_time" to 0,
            "waiting_time" to 0,
            "internal" to true,
            "keywords" to JsonArray(emptyList()),
            "steps" to JsonArray(emptyList()),
        )
        val created = api(slug, "POST", "/api/recipe/", body)
        (created.json as? JsonObject)?.longAt("id")?.let(recipes::add)
        return created.status
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

    private companion object {
        val JSON = "application/json".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        /** The page size of Idag's row Nya (SyncEngine, home section NEW). */
        const val APP_PAGE_SIZE = 20

        /** Created in this order, so the last one is the newest. */
        val NEW_RECIPES = listOf("Tandem nya ett", "Tandem nya två", "Tandem nya tre")

        /** The Fas 1 seed's back-dated recipe (contract.yml: created_at 30 days back). */
        const val OLD_RECIPE = "Pytt i panna"

        /** What the app rests on. */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "create.statuses" to listOf(201, 201, 201),
            "nya.status" to 200,
            "nya.firstThree" to NEW_RECIPES.reversed(),
            "nya.ownAreNew" to listOf(true),
            "nya.pageSizeHonoured" to true,
            "nya.hasCreatedAt" to true,
            "nya.sortedByCreatedDesc" to true,
            "all.complete" to true,
            "all.sortedByCreatedDesc" to true,
            "all.oldRecipeFound" to true,
            "all.oldRecipeNew" to false,
            "all.oldRecipeAfterEveryNew" to true,
            "sortOnly.firstThree" to NEW_RECIPES.reversed(),
        )
    }
}
