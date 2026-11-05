package casa.moller.tandem.contract

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * Fas 4 (fas4-kvar lane, plan §8.3 "Autoplan", feature #31): what Tandoor's `POST /api/auto-plan/`
 * does, so the app can decide whether "Föreslå veckans matsedel" uses it (docs/BYGGE.md "Smarta
 * förslag", the decision and its reasons):
 *
 * - a. A week (7 days) for a meal type from ONE keyword with three tagged recipes: the status, the
 *   answer (an echo of the request or the created plans?), how many meal plans the server creates,
 *   whether a recipe repeats (fewer recipes than days), the servings and the meal type.
 * - b. The same week again: whether existing meals are skipped or doubled.
 * - c. Two keywords: AND (only recipes with both) or OR.
 * - d. A keyword without recipes; a date without time; the guest.
 *
 * Seeds its own keywords, recipes and meal type (names starting with "Tandem-autoplan") on a week
 * far ahead, and deletes everything again. Recordings go to
 * `build/contract-recordings/<tag>/fas4/autoplan/` and are committed to
 * `api-schema/fixtures/<version>/fas4/autoplan/`. Checks are soft as in the other phases: the last
 * step compares [EXPECTED] and fails with all mismatches together.
 *
 * Skipped unless TANDOOR_GUEST_TOKEN is set. Never run against a production server.
 */
@Suppress("LongMethod")
@EnabledIfEnvironmentVariable(named = "TANDOOR_GUEST_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase4AutoPlanContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val recordDir = File(env.recordDir, "fas4/autoplan")
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    /** A Monday far ahead, so nothing else in the space is planned that week. */
    private val monday: LocalDate = today.plusWeeks(WEEKS_AHEAD).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val sunday: LocalDate = monday.plusDays(6)

    private var keywordId: Long? = null
    private var quickKeywordId: Long? = null
    private var emptyKeywordId: Long? = null
    private var mealTypeId: Long? = null
    private var mealTypeCreated = false
    private val recipeIds = mutableListOf<Long>()

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        assertTrue(env.guestToken.isNotBlank(), "TANDOOR_GUEST_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        sanitizer.fakeTokenFor(env.guestToken)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun writeFacts() {
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    // --- Seed ---------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun seed() {
        keywordId = createKeyword("$PREFIX Vardag")
        quickKeywordId = createKeyword("$PREFIX Snabbt")
        emptyKeywordId = createKeyword("$PREFIX Tom")
        val created = api(
            "meal-type-create",
            "POST",
            "/api/meal-type/",
            obj("name" to "$PREFIX middag", "order" to MEAL_TYPE_ORDER, "time" to "18:00:00", "color" to "#003E7E"),
        )
        observe("seed.mealType.create", describe(created))
        mealTypeId = objOf(created)?.longAt("id")
        mealTypeCreated = mealTypeId != null
        if (mealTypeId == null) mealTypeId = pageOrArray(read("/api/meal-type/").json).firstOrNull()?.longAt("id")
        for ((index, name) in RECIPES.withIndex()) {
            // The first recipe is also "Snabbt" (c. two keywords).
            val keywords = listOfNotNull(keywordId, quickKeywordId.takeIf { index == 0 })
                .map { id -> obj("id" to id, "name" to keywordName(id)) }
            val recipe = api(
                "recipe-create",
                "POST",
                "/api/recipe/",
                obj("name" to name, "servings" to 4, "internal" to true, "keywords" to keywords, "steps" to JsonArray(emptyList())),
                record = false,
            )
            objOf(recipe)?.longAt("id")?.let { recipeIds += it }
        }
        observe("seed.recipes", recipeIds.size)
    }

    // --- a. One keyword, a week ----------------------------------------------------------------------

    @Test
    @Order(10)
    fun aWeekFromOneKeyword() {
        val body = autoPlan(listOfNotNull(keywordId), servings = SERVINGS)
        val answer = api("auto-plan-week", "POST", "/api/auto-plan/", body)
        observe("autoplan.week", describe(answer))
        observe("autoplan.week.answerIsEcho", objOf(answer)?.let { it.containsKey("start_date") && !it.containsKey("id") })
        val plans = plansOfWeek("meal-plans-after-auto-plan")
        observe("autoplan.week.created", plans.size)
        val recipes = plans.mapNotNull { it.objAt("recipe")?.longAt("id") }
        observe("autoplan.week.distinctRecipes", recipes.distinct().size)
        observe("autoplan.week.repeatsRecipes", recipes.size > recipes.distinct().size)
        observe("autoplan.week.onlyKeywordRecipes", recipes.all { it in recipeIds })
        observe("autoplan.week.days", plans.mapNotNull { it.stringAt("from_date")?.take(DATE_LENGTH) }.distinct().size)
        observe("autoplan.week.servings", plans.mapNotNull { it.doubleAt("servings") }.distinct())
        observe("autoplan.week.mealTypeIsTheChosen", plans.all { it.objAt("meal_type")?.longAt("id") == mealTypeId })
        observe("autoplan.week.shared", plans.map { it.arrAt("shared").size }.distinct())
        observe("autoplan.week.addedToShopping", plans.map { it.boolAt("shopping") }.distinct())
    }

    // --- b. The same week again -----------------------------------------------------------------------

    @Test
    @Order(20)
    fun theSameWeekAgain() {
        val body = autoPlan(listOfNotNull(keywordId), servings = SERVINGS)
        val answer = api("auto-plan-again", "POST", "/api/auto-plan/", body, record = false)
        observe("autoplan.again.status", answer.status)
        observe("autoplan.again.total", plansOfWeek(null).size)
        deletePlansOfWeek()
    }

    // --- c. Two keywords --------------------------------------------------------------------------------

    @Test
    @Order(30)
    fun twoKeywords() {
        val answer = api("auto-plan-two-keywords", "POST", "/api/auto-plan/", autoPlan(listOfNotNull(keywordId, quickKeywordId), SERVINGS))
        observe("autoplan.twoKeywords.status", answer.status)
        val plans = plansOfWeek(null)
        observe("autoplan.twoKeywords.created", plans.size)
        observe("autoplan.twoKeywords.distinctRecipes", plans.mapNotNull { it.objAt("recipe")?.longAt("id") }.distinct().size)
        deletePlansOfWeek()
    }

    // --- d. No recipes, date only, guest -----------------------------------------------------------------

    @Test
    @Order(40)
    fun edges() {
        val none = api("auto-plan-no-recipes", "POST", "/api/auto-plan/", autoPlan(listOfNotNull(emptyKeywordId), SERVINGS))
        observe("autoplan.noRecipes", describe(none))
        observe("autoplan.noRecipes.created", plansOfWeek(null).size)
        deletePlansOfWeek()

        val dateOnly = api(
            "auto-plan-date-only",
            "POST",
            "/api/auto-plan/",
            autoPlan(listOfNotNull(keywordId), SERVINGS, start = monday.toString(), end = sunday.toString()),
        )
        observe("autoplan.dateOnly", describe(dateOnly))
        observe("autoplan.dateOnly.created", plansOfWeek(null).size)
        deletePlansOfWeek()

        val guest = api("auto-plan-guest", "POST", "/api/auto-plan/", autoPlan(listOfNotNull(keywordId), SERVINGS), token = env.guestToken)
        observe("autoplan.guest", describe(guest))
        observe("autoplan.guest.created", plansOfWeek(null).size)
        deletePlansOfWeek()
    }

    // --- Clean up and baseline ------------------------------------------------------------------------

    @Test
    @Order(90)
    fun cleanUp() {
        deletePlansOfWeek()
        recipeIds.forEach { api("cleanup", "DELETE", "/api/recipe/$it/", record = false) }
        listOfNotNull(keywordId, quickKeywordId, emptyKeywordId).forEach { api("cleanup", "DELETE", "/api/keyword/$it/", record = false) }
        if (mealTypeCreated) mealTypeId?.let { api("cleanup", "DELETE", "/api/meal-type/$it/", record = false) }
    }

    @Test
    @Order(99)
    fun everyFactMatchesTheBaseline() {
        for ((key, expected) in EXPECTED) {
            val described = (facts[key.removeSuffix(".status")] as? JsonObject)?.get("status")
            val observed = facts[key] ?: described.takeIf { key.endsWith(".status") }
            if (observed != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed $observed"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the Fas 4 auto-plan baseline:\n" + mismatches.joinToString("\n"))
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private val keywordNames = mutableMapOf<Long, String>()

    private fun keywordName(id: Long): String? = keywordNames[id]

    private fun createKeyword(name: String): Long? {
        val created = api("keyword-create", "POST", "/api/keyword/", obj("name" to name), record = false)
        val id = objOf(created)?.longAt("id") ?: return null
        keywordNames[id] = objOf(created)?.stringAt("name") ?: name
        return id
    }

    private fun autoPlan(
        keywords: List<Long>,
        servings: Int,
        start: String = "${monday}T00:00:00",
        end: String = "${sunday}T00:00:00",
    ): JsonObject = obj(
        "start_date" to start,
        "end_date" to end,
        "meal_type_id" to mealTypeId,
        "keywords" to keywords,
        "keyword_mode" to "and",
        "servings" to servings,
        "shared" to JsonArray(emptyList()),
        "addshopping" to false,
    )

    /** The meal plans of the far week (recorded as [name] when given). */
    private fun plansOfWeek(name: String?): List<JsonObject> {
        val path = "/api/meal-plan/?from_date=$monday&to_date=$sunday"
        val answer = if (name == null) read(path) else api(name, "GET", path)
        return pageOrArray(answer.json)
    }

    private fun deletePlansOfWeek() {
        plansOfWeek(null).mapNotNull { it.longAt("id") }.forEach { api("cleanup", "DELETE", "/api/meal-plan/$it/", record = false) }
    }

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
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val MAX_TEXT = 300
        const val PREFIX = "Tandem-autoplan"
        const val WEEKS_AHEAD = 60L
        const val MEAL_TYPE_ORDER = 99
        const val SERVINGS = 3
        const val DATE_LENGTH = 10
        val RECIPES = listOf("$PREFIX köttbullar", "$PREFIX fiskgratäng", "$PREFIX linsgryta")

        /**
         * The facts the app relies on (api-schema/fixtures/<version>/FACTS.md "Fas 4 – auto-plan",
         * docs/BYGGE.md "Smarta förslag"). A key ending in `.status` also matches the status of a
         * recorded response description. Every other fact is only recorded.
         */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "seed.recipes" to 3,
            "autoplan.week.status" to 200,
            "autoplan.week.answerIsEcho" to true,
            "autoplan.week.created" to 7,
            "autoplan.week.distinctRecipes" to 3,
            "autoplan.week.repeatsRecipes" to true,
            "autoplan.week.onlyKeywordRecipes" to true,
            "autoplan.week.days" to 7,
            "autoplan.week.servings" to listOf(3.0),
            "autoplan.week.mealTypeIsTheChosen" to true,
            "autoplan.again.total" to 14,
            "autoplan.twoKeywords.distinctRecipes" to 1,
            "autoplan.guest.status" to 403,
            "autoplan.guest.created" to 0,
        )
    }
}
