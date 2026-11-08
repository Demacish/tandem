package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * Fas 5, lane handla (0.7.0, plan §8.2 "Sammanslagning i vyn"): what Tandem's merged shopping rows
 * build on, on a real server.
 *
 * - **The conversions:** `GET /api/unit-conversion/` (shape, the food-specific ones next to the
 *   general ones, the `food_id` filter, who may read it) and what Tandoor itself computes from them
 *   (`Ingredient.conversions` of a recipe read like the app reads it): a food-specific conversion
 *   only for its food and in both directions, several steps of custom conversions, the base units
 *   (`Unit.base_unit`), a unit without `base_unit` matched by its NAME as the source, and that a
 *   target needs `base_unit`.
 * - **The list:** the server never merges: the same food in three units stays three entries (the
 *   view merges, the rows stay separate), an entry has no note, and one bulk call checks and
 *   unchecks them all (what checking a merged row sends).
 * - **The web:** whether Tandoor's own shopping list merges, read from its frontend source when
 *   contract.yml checked it out (`build/tandoor-src`, not for `latest`): it shows one line per food
 *   and adds amounts of the SAME unit and state, it never converts.
 *
 * Runs after Phase1ContractTest, whose seed it builds on (units g, kg, ml, l, dl without
 * `base_unit`, 1 dl = 100 ml, 1 dl vetemjöl = 60 g). It creates and deletes its own units, food,
 * conversion, recipe and entries. Recordings: `build/contract-recordings/<tag>/fas5/handla/`, committed
 * to `api-schema/fixtures/<version>/fas5/handla/`. Soft checks as in the other phases: every fact is
 * recorded, the last step compares [EXPECTED].
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase5ConversionContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas5/handla")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val unitIds = mutableListOf<Long>()
    private var foodId: Long? = null
    private var conversionId: Long? = null
    private var recipeId: Long? = null
    private val entryIds = mutableListOf<Long>()

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        if (env.readerToken.isNotBlank()) sanitizer.fakeTokenFor(env.readerToken)
        if (env.guestToken.isNotBlank()) sanitizer.fakeTokenFor(env.guestToken)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun cleanUp() {
        entryIds.forEach { api("cleanup-entry", "DELETE", "/api/shopping-list-entry/$it/", record = false) }
        recipeId?.let { api("cleanup-recipe", "DELETE", "/api/recipe/$it/", record = false) }
        conversionId?.let { api("cleanup-conversion", "DELETE", "/api/unit-conversion/$it/", record = false) }
        foodId?.let { api("cleanup-food", "DELETE", "/api/food/$it/", record = false) }
        unitIds.forEach { api("cleanup-unit", "DELETE", "/api/unit/$it/", record = false) }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    // --- a. The conversions -------------------------------------------------------------------------

    @Test
    @Order(10)
    fun seed() {
        // Units WITHOUT base_unit: "ounce" is a key of Tandoor's base table (the name counts for the
        // source), "pint" too (but a target needs base_unit).
        for (name in listOf(OUNCE, PINT)) {
            val body = obj("name" to name, "plural_name" to null, "base_unit" to null)
            val created = api("unit-$name", "POST", "/api/unit/", body, record = false)
            observe("seed.unit.$name.status", created.status)
            (created.json as? JsonObject)?.longAt("id")?.let { unitIds += it }
            observe("seed.unit.$name.baseUnit", (created.json as? JsonObject)?.stringAt("base_unit"))
        }
        val food = api("food", "POST", "/api/food/", obj("name" to CREAM), record = false)
        observe("seed.food.status", food.status)
        foodId = (food.json as? JsonObject)?.longAt("id")
        // A food-specific conversion: 1 dl grädde = 100 g (the plan's "3 dl + 200 g grädde" → "5 dl").
        val conversion = api(
            "conversion-food",
            "POST",
            "/api/unit-conversion/",
            obj(
                "base_amount" to 1.0,
                "base_unit" to obj("name" to "dl"),
                "converted_amount" to 100.0,
                "converted_unit" to obj("name" to "g"),
                "food" to obj("name" to CREAM),
            ),
            record = false,
        )
        observe("seed.conversion.status", conversion.status)
        conversionId = (conversion.json as? JsonObject)?.longAt("id")
    }

    @Test
    @Order(20)
    fun conversionList() {
        val all = api("unit-conversion-list", "GET", "/api/unit-conversion/?page_size=100")
        observe("list.status", all.status)
        val body = all.json as? JsonObject
        observe("list.pageKeys", body?.keys?.sorted())
        val results = body?.arrAt("results").objects()
        observe("list.itemKeys", results.firstOrNull()?.keys?.sorted())
        observe("list.unitKeys", results.firstOrNull()?.objAt("base_unit")?.keys?.sorted())
        observe("list.hasGeneral", results.any { it["food"] == null || it["food"] is JsonNull })
        val own = results.firstOrNull { it.longAt("id") == conversionId }
        observe("list.foodSpecific.foodId", own?.objAt("food")?.longAt("id") == foodId && foodId != null)
        observe("list.foodSpecific.foodKeysIncludeId", own?.objAt("food")?.keys?.contains("id"))
        observe("list.foodSpecific.baseUnit", own?.objAt("base_unit")?.stringAt("name"))
        observe("list.foodSpecific.convertedUnit", own?.objAt("converted_unit")?.stringAt("name"))
        observe("list.foodSpecific.amounts", listOf(own?.doubleAt("base_amount"), own?.doubleAt("converted_amount")))
        observe("list.dl.baseUnit", unitNamed(results.mapNotNull { it.objAt("base_unit") }, "dl")?.stringAt("base_unit"))
        observe("list.ml.baseUnit", unitNamed(results.mapNotNull { it.objAt("converted_unit") }, "ml")?.stringAt("base_unit"))

        // ?food_id= gives only that food's conversions: the general ones are NOT included.
        val filtered = api("unit-conversion-list-food", "GET", "/api/unit-conversion/?food_id=$foodId&page_size=100")
        observe("list.foodFilter.status", filtered.status)
        val filteredResults = (filtered.json as? JsonObject)?.arrAt("results").objects()
        observe("list.foodFilter.count", filteredResults.size)
        observe("list.foodFilter.onlyThatFood", filteredResults.all { it.objAt("food")?.longAt("id") == foodId })

        // Who may read them: Tandoor's CustomIsUser (a member yes, a guest no). The units the same.
        if (env.readerToken.isNotBlank()) {
            val member = api("unit-conversion-list-member", "GET", "/api/unit-conversion/?page_size=1", token = env.readerToken)
            observe("list.member.status", member.status)
        }
        if (env.guestToken.isNotBlank()) {
            val guest = api("unit-conversion-list-guest", "GET", "/api/unit-conversion/?page_size=1", token = env.guestToken)
            observe("list.guest.status", guest.status)
            observe("list.guest.hasDetail", (guest.json as? JsonObject)?.containsKey("detail"))
            observe("units.guest.status", api("unit-list-guest", "GET", "/api/unit/?page_size=1", token = env.guestToken).status)
        }
    }

    @Test
    @Order(30)
    fun whatTandoorComputes() {
        val lines = listOf(
            Line("cream-3-dl", CREAM, "dl", 3.0),
            Line("cream-200-g", CREAM, "g", 200.0),
            Line("flour-2-dl", FLOUR, "dl", 2.0),
            Line("cream-1-ounce", CREAM, OUNCE, 1.0),
            Line("cream-1-l", CREAM, "l", 1.0),
        )
        val ingredients = lines.mapIndexed { order, line ->
            obj(
                "food" to obj("name" to line.food),
                "unit" to obj("name" to line.unit),
                "amount" to line.amount,
                "note" to line.key,
                "order" to order,
                "is_header" to false,
                "no_amount" to false,
            )
        }
        val step = obj(
            "name" to "",
            "instruction" to "Vispa.",
            "time" to 0,
            "order" to 0,
            "show_as_header" to false,
            "show_ingredients_table" to true,
            "ingredients" to JsonArray(ingredients),
        )
        val created = api(
            "recipe-create",
            "POST",
            "/api/recipe/",
            obj(
                "name" to "Tandem omräkning",
                "description" to "",
                "servings" to 2,
                "servings_text" to "",
                "working_time" to 0,
                "waiting_time" to 0,
                "internal" to true,
                "keywords" to JsonArray(emptyList()),
                "steps" to JsonArray(listOf(step)),
            ),
            record = false,
        )
        observe("recipe.createStatus", created.status)
        recipeId = (created.json as? JsonObject)?.longAt("id")
        val read = api("recipe-conversions", "GET", "/api/recipe/$recipeId/")
        val recipe = read.json as? JsonObject ?: return
        val byNote = recipe.arrAt("steps").objects().flatMap { it.arrAt("ingredients").objects() }.associateBy { it.stringAt("note") }
        for (line in lines) {
            val conversions = byNote[line.key]?.arrAt("conversions").objects()
            val first = conversions.firstOrNull()
            observe("conversions.${line.key}.first", first?.let { "${round(it.doubleAt("amount"))} ${it.stringAt("unit")}" })
            val byUnit = conversions.associate { (it.stringAt("unit") ?: "?") to round(it.doubleAt("amount")) }
            observe("conversions.${line.key}", byUnit)
            for (unit in WATCHED_UNITS) observe("conversions.${line.key}.$unit", byUnit[unit])
        }
        observe("conversions.shape", byNote[lines.first().key]?.arrAt("conversions").objects().firstOrNull()?.keys?.sorted())
    }

    // --- b. The list ---------------------------------------------------------------------------------

    @Test
    @Order(40)
    fun theServerNeverMerges() {
        for ((unit, amount) in listOf("dl" to 3.0, "g" to 200.0, "st" to 1.0)) {
            val created = api(
                "entry-$unit",
                "POST",
                "/api/shopping-list-entry/",
                obj("food" to obj("name" to CREAM), "unit" to obj("name" to unit), "amount" to amount),
                record = false,
            )
            observe("entry.$unit.status", created.status)
            (created.json as? JsonObject)?.longAt("id")?.let { entryIds += it }
        }
        val list = api("shopping-list-entries", "GET", "/api/shopping-list-entry/?page_size=200")
        val mine = (list.json as? JsonObject)?.arrAt("results").objects().filter { it.longAt("id") in entryIds }
        observe("list.entries.rows", mine.size)
        observe("list.entries.sameFood", mine.mapNotNull { it.objAt("food")?.longAt("id") }.distinct().size == 1)
        observe("list.entries.units", mine.mapNotNull { it.objAt("unit")?.stringAt("name") }.sorted())
        observe("list.entry.hasNote", mine.any { it.containsKey("note") })
        observe("list.entry.unitKeys", mine.firstOrNull()?.objAt("unit")?.keys?.sorted())
        observe("list.entry.gramsBaseUnit", unitNamed(mine.mapNotNull { it.objAt("unit") }, "g")?.stringAt("base_unit"))

        // Checking a merged row: one bulk call with all its ids, and unchecking the same way.
        val check = api("bulk-check-merged", "POST", "/api/shopping-list-entry/bulk/", obj("ids" to entryIds, "checked" to true))
        observe("bulk.check.status", check.status)
        observe("bulk.check.allChecked", entries().all { it.boolAt("checked") == true })
        val uncheck = api("bulk-uncheck-merged", "POST", "/api/shopping-list-entry/bulk/", obj("ids" to entryIds, "checked" to false))
        observe("bulk.uncheck.status", uncheck.status)
        observe("bulk.uncheck.noneChecked", entries().none { it.boolAt("checked") == true })
    }

    // --- c. The web ----------------------------------------------------------------------------------

    @Test
    @Order(50)
    fun theWebShoppingList() {
        val source = File(WEB_SOURCE)
        observe("web.sourceChecked", source.isFile)
        if (!source.isFile) return
        val text = source.readText()
        // The amounts of a line (`const amounts = computed(...)`): entries of one food, summed per unit and state.
        val amounts = text.substringAfter("const amounts", "").substringBefore("const infoRow", "")
        observe("web.oneLinePerFood", text.contains("shoppingListFood"))
        observe("web.sumsSameUnit", amounts.contains("ua.amount += e.amount") && amounts.contains("ua.unit.id! == unit"))
        observe("web.keepsStateApart", amounts.contains("ua.checked == e.checked") && amounts.contains("ua.delayed == isDelayed(e)"))
        observe("web.converts", Regex("conver", RegexOption.IGNORE_CASE).containsMatchIn(amounts))
        observe("web.checksAllEntriesOfLine", text.contains("setEntriesCheckedState(entries, !isChecked"))
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            val observed = facts[key]
            if (observed == null) {
                if (!key.startsWith("web.") && !key.contains(".guest.") && !key.contains(".member.")) mismatches += "$key: not observed"
                continue
            }
            if (observed != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed $observed"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the Fas 5 conversion baseline:\n" + mismatches.joinToString("\n"))
    }

    private fun entries(): List<JsonObject> {
        val list = api("entries", "GET", "/api/shopping-list-entry/?page_size=200", record = false).json as? JsonObject
        return list?.arrAt("results").objects().filter { it.longAt("id") in entryIds }
    }

    private fun unitNamed(units: List<JsonObject>, name: String): JsonObject? = units.firstOrNull { it.stringAt("name") == name }

    private fun round(value: Double?): Double? = value?.let { (it * ROUNDING).roundToLong() / ROUNDING }

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

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    private data class Line(val key: String, val food: String, val unit: String, val amount: Double)

    private companion object {
        val JSON = "application/json".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val ROUNDING = 10_000.0
        const val CREAM = "tandem-grädde"
        const val FLOUR = "vetemjöl"
        const val OUNCE = "ounce"
        const val PINT = "pint"

        /** The units whose computed amount is a fact of its own (null = Tandoor does not offer it). */
        val WATCHED_UNITS = listOf("g", "ml", "dl", "l", "kg", PINT)

        /** Where contract.yml checks out Tandoor's web frontend (the scaling golden table's step). */
        const val WEB_SOURCE = "build/tandoor-src/vue3/src/components/display/ShoppingLineItem.vue"

        /**
         * The facts Tandem's merged rows rely on (api-schema/fixtures/<version>/FACTS.md "Fas 5 –
         * omräkning och sammanslagning", docs/BYGGE.md "Sammanslagning och rutnät"). Web facts are
         * compared only when the source was checked out, guest and member facts only with their tokens.
         */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "list.status" to 200,
            "list.pageKeys" to listOf("count", "next", "previous", "results", "timestamp"),
            "list.hasGeneral" to true,
            "list.foodSpecific.foodId" to true,
            "list.foodSpecific.baseUnit" to "dl",
            "list.foodSpecific.convertedUnit" to "g",
            "list.foodSpecific.amounts" to listOf(1.0, 100.0),
            "list.dl.baseUnit" to null,
            "list.ml.baseUnit" to "ml",
            "list.foodFilter.status" to 200,
            "list.foodFilter.count" to 1,
            "list.foodFilter.onlyThatFood" to true,
            "list.member.status" to 200,
            "list.guest.status" to 403,
            "list.guest.hasDetail" to true,
            // The food-specific conversion, then the general dl = 100 ml, then the base units.
            "conversions.cream-3-dl.first" to "3.0 dl",
            "conversions.cream-200-g.first" to "200.0 g",
            // A food-specific conversion, also backwards, and the general one after it (several steps).
            "conversions.cream-3-dl.g" to 300.0,
            "conversions.cream-3-dl.ml" to 300.0,
            "conversions.cream-3-dl.l" to 0.3,
            "conversions.cream-200-g.dl" to 2.0,
            "conversions.cream-200-g.ml" to 200.0,
            "conversions.cream-200-g.kg" to 0.2,
            // Only for its food: vetemjöl keeps its own 1 dl = 60 g.
            "conversions.flour-2-dl.g" to 120.0,
            // A unit without base_unit counts by its NAME as the source ("ounce" is a key of the table).
            "conversions.cream-1-ounce.g" to 28.3495,
            // A target needs base_unit ("pint" has none), and base units are not followed back into
            // custom conversions (l → ml is a base step, ml → dl a custom one: no dl).
            "conversions.cream-1-l.ml" to 1000.0,
            "conversions.cream-1-l.pint" to null,
            "conversions.cream-1-l.dl" to null,
            "list.entries.rows" to 3,
            "list.entries.sameFood" to true,
            "list.entries.units" to listOf("dl", "g", "st"),
            "list.entry.hasNote" to false,
            "list.entry.gramsBaseUnit" to "g",
            "bulk.check.status" to 200,
            "bulk.check.allChecked" to true,
            "bulk.uncheck.status" to 200,
            "bulk.uncheck.noneChecked" to true,
            "web.sumsSameUnit" to true,
            "web.keepsStateApart" to true,
            "web.converts" to false,
            "web.checksAllEntriesOfLine" to true,
        )
    }
}
