package casa.moller.tandem.contract

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Establishes and records the server facts Fas S ("Skafferi & drycker", release 0.4.0) depends on
 * (plan §8.8, §10 Fas S, features #23, #26-#28, #43-#46):
 *
 * - a. `recipe?makenow=N` as an INTEGER (at most N missing foods): on hand, inventory entries with
 *   amount > 0 in the household, substitutes and `ignore_shopping` foods; `true` = 0; invalid values;
 * - b. `inventory-location`, `inventory-entry` and `inventory-log`: CRUD, the nested writes, the
 *   filters, ordering by `expires`, the log rows, household scoping and the guest;
 * - c. keywords: POST with/without `parent`, `keyword/{id}/move/{parent}/`, `keywords_or` with
 *   `include_children`, a household member PATCHing the keywords of the partner's recipe and that
 *   the PATCH replaces the whole list;
 * - d. `automation` of type `FOOD_ALIAS`: CRUD, case-insensitive `param_1`, 128 characters, the
 *   ingredient parser applying it, the guest;
 * - e. foods: PATCH `food_onhand`/`ignore_shopping` and `food/batch_update/` (`on_hand`, `ignore_shopping`);
 * - f. a minimal drink recipe (no step / one empty step), its image, `recipe/flat` and search.
 *
 * Runs after the Fas 2 steps against the same throwaway server. contract.yml first adds a fourth
 * member ([ContractEnv.outsiderToken], group `user`) in a SECOND household, so household scoping
 * can be seen. Requests claim `Host: tandoor.example.com`; recordings go to
 * `build/contract-recordings/<tag>/fas-s/` with the date shift of Fas 1/2 (the run day is
 * 2000-01-15) and are committed to `api-schema/fixtures/<version>/fas-s/`.
 *
 * Checks are soft as in Fas 1/2: [observe] records every fact and the last step compares the facts
 * in `EXPECTED` (what the app relies on, docs/BYGGE.md "Fas S – kontrakt") and fails with all
 * mismatches together; tandoor-watch compares every other fact between versions nightly. Every
 * step tolerates the failure of an earlier one. No ids are recorded as facts (they differ between
 * versions), only names, statuses and booleans.
 *
 * Skipped unless TANDOOR_OUTSIDER_TOKEN is set. Never run against a production server.
 */
@Suppress("LargeClass", "LongMethod", "CyclomaticComplexMethod")
@EnabledIfEnvironmentVariable(named = "TANDOOR_OUTSIDER_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PhaseSContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val http = TandoorHttp(env.baseUrl, sanitizer, env.fasSRecordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    /** The contract user's household as `{id, name}` (from `user-space/all_personal`). */
    private var household: JsonObject? = null
    private val foodIds = LinkedHashMap<String, Long>()
    private val recipeIds = LinkedHashMap<String, Long>()

    /** Locations as the server returned them (the full object is what a nested write needs). */
    private val locations = LinkedHashMap<String, JsonObject>()
    private val entryIds = LinkedHashMap<String, Long>()
    private var drinkId: Long? = null
    private var drinkKeywordId: Long? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        assertTrue(env.readerToken.isNotBlank(), "TANDOOR_READER_TOKEN must be set")
        assertTrue(env.guestToken.isNotBlank(), "TANDOOR_GUEST_TOKEN must be set")
        assertTrue(env.outsiderToken.isNotBlank(), "TANDOOR_OUTSIDER_TOKEN must be set")
        // Same fake numbering as Fas 1/2: ...0001 owner, ...0002 member, ...0003 guest; ...0004 the outsider.
        sanitizer.fakeTokenFor(env.fas1Token)
        sanitizer.fakeTokenFor(env.readerToken)
        sanitizer.fakeTokenFor(env.guestToken)
        sanitizer.fakeTokenFor(env.outsiderToken)
        env.fasSRecordDir.deleteRecursively()
        env.fasSRecordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun writeFacts() {
        facts["mismatches"] = mismatches
        facts.write(File(env.fasSRecordDir, "observed-facts.json"), sanitizer)
    }

    // --- 0. Who is who: households -------------------------------------------------------------

    @Test
    @Order(5)
    fun households() {
        for ((who, token) in listOf("owner" to env.fas1Token, "reader" to env.readerToken, "outsider" to env.outsiderToken)) {
            val space = api("user-space-$who", "GET", "/api/user-space/all_personal/", token = token, record = who == "outsider")
            val first = (space.json as? JsonArray)?.firstOrNull() as? JsonObject
            val own = first?.objAt("household")
            if (who == "owner") household = own?.let { obj("id" to it.longAt("id"), "name" to it.stringAt("name")) }
            observe("household.$who.present", own != null)
            if (who != "owner") observe("household.$who.sameAsOwner", own?.longAt("id") == household?.longAt("id"))
        }
    }

    // --- 1. Seeding: foods through recipes (the foods are created by the recipe's ingredients) -------

    @Test
    @Order(10)
    fun seedRecipes() {
        for ((name, foods) in MAKENOW_RECIPES) {
            val created = api("seed-$name", "POST", "/api/recipe/", recipeBody(name, foods), record = false)
            assertEquals(CREATED, created.status, created.toString())
            idOf(created)?.let { recipeIds[name] = it }
            val ingredients = objOf(created)?.arrAt("steps").objects().flatMap { it.arrAt("ingredients").objects() }
            for (food in ingredients.mapNotNull { it.objAt("food") }) {
                val foodName = food.stringAt("name") ?: continue
                food.longAt("id")?.let { foodIds[foodName] = it }
            }
        }
        // Foods that are in no recipe: the substitute and a food only switched on in batch.
        for (name in listOf(SUBSTITUTE, BATCH_FOOD)) {
            val food = api("seed-food-$name", "POST", "/api/food/", obj("name" to name), record = false)
            idOf(food)?.let { foodIds[name] = it }
        }
        observe("seed.foods", foodIds.keys.sorted())
    }

    // --- e. Foods: on hand, ignore_shopping, batch_update -----------------------------------------

    @Test
    @Order(20)
    fun foods() {
        val tahini = foodIds["tahini"]
        if (tahini != null) {
            val onHand = api("food-patch-onhand", "PATCH", "/api/food/$tahini/", obj("food_onhand" to true))
            observe("food.patchOnHand", describe(onHand, includeBody = false))
            observe("food.patchOnHand.value", objOf(onHand)?.get("food_onhand"))
            observe("food.patchOnHand.reader", foodField(tahini, "food_onhand", env.readerToken))
            observe("food.patchOnHand.outsider", foodField(tahini, "food_onhand", env.outsiderToken))
            val guest = api("food-patch-onhand-as-guest", "PATCH", "/api/food/$tahini/", obj("food_onhand" to true), token = env.guestToken)
            observe("food.patchOnHand.guest", describe(guest, includeBody = false))
        }
        val oil = foodIds["olivolja"]
        if (oil != null) {
            val ignore = api("food-patch-ignore-shopping", "PATCH", "/api/food/$oil/", obj("ignore_shopping" to true))
            observe("food.patchIgnoreShopping", describe(ignore, includeBody = false))
            observe("food.patchIgnoreShopping.value", objOf(ignore)?.get("ignore_shopping"))
        }
        // batch_update: on hand for the oats and the substitute, the oat drink gets the substitute.
        val oats = foodIds["havregryn"]
        val milk = foodIds[SUBSTITUTE]
        val batchFood = foodIds[BATCH_FOOD]
        val oatDrink = foodIds["havredryck"]
        if (oats != null && milk != null && batchFood != null) {
            val ids = listOf(oats, milk, batchFood)
            val on = api("food-batch-update-on-hand", "PUT", "/api/food/batch_update/", obj("foods" to ids, "on_hand" to true))
            observe("food.batchOnHand", describe(on))
            observe("food.batchOnHand.values", ids.map { foodField(it, "food_onhand") })
            observe("food.batchOnHand.reader", foodField(oats, "food_onhand", env.readerToken))
            observe("food.batchOnHand.outsider", foodField(oats, "food_onhand", env.outsiderToken))
            val off = api("food-batch-update-off", "PUT", "/api/food/batch_update/", obj("foods" to listOf(batchFood), "on_hand" to false))
            observe("food.batchOff.status", off.status)
            observe("food.batchOff.value", foodField(batchFood, "food_onhand"))
            val ignoreOn = api(
                "food-batch-update-ignore-shopping",
                "PUT",
                "/api/food/batch_update/",
                obj("foods" to listOf(batchFood), "ignore_shopping" to true),
                record = false,
            )
            observe("food.batchIgnoreShopping.status", ignoreOn.status)
            observe("food.batchIgnoreShopping.value", foodField(batchFood, "ignore_shopping"))
            val guest = api(
                "food-batch-update-as-guest",
                "PUT",
                "/api/food/batch_update/",
                obj("foods" to listOf(batchFood), "on_hand" to true),
                token = env.guestToken,
            )
            observe("food.batchOnHand.guest", describe(guest, includeBody = false))
            val minimal = api("food-batch-minimal", "PUT", "/api/food/batch_update/", obj("foods" to listOf(batchFood)), record = false)
            observe("food.batchMinimal.status", minimal.status)
        }
        if (oatDrink != null && milk != null) {
            val sub = api(
                "food-batch-update-substitute",
                "PUT",
                "/api/food/batch_update/",
                obj("foods" to listOf(oatDrink), "substitute_add" to listOf(milk)),
            )
            observe("food.batchSubstitute.status", sub.status)
            val detail = objOf(api("food-get-substitute", "GET", "/api/food/$oatDrink/"))
            observe("food.substitute.names", detail?.arrAt("substitute").objects().map { it.stringAt("name") })
            observe("food.substituteOnhand", detail?.get("substitute_onhand"))
            detail?.let { observe("food.fieldTypes", Facts.fieldTypes(it)) }
        }
    }

    // --- b. Inventory: locations ---------------------------------------------------------------

    @Test
    @Order(30)
    fun inventoryLocations() {
        val own = household ?: return observe("inventoryLocation.skipped", "no household")
        val before = api("inventory-location-list-empty", "GET", "/api/inventory-location/")
        observe("inventoryLocation.list", describe(before, includeBody = false))
        observe("inventoryLocation.list.keys", objOf(before)?.keys?.toList())
        val householdsBefore = householdCount()

        val noHousehold = obj("name" to "Utan hushåll")
        val withoutHousehold = api("inventory-location-create-no-household", "POST", "/api/inventory-location/", noHousehold)
        observe("inventoryLocation.createNoHousehold", describe(withoutHousehold))

        for ((name, freezer) in listOf("Kyl" to false, "Frys" to true, "Skafferi" to false)) {
            val created = api(
                if (name == "Kyl") "inventory-location-create" else "inventory-location-create-$name",
                "POST",
                "/api/inventory-location/",
                obj("name" to name, "is_freezer" to freezer, "household" to own),
                record = name != "Skafferi",
            )
            if (name == "Kyl") {
                observe("inventoryLocation.create", describe(created, includeBody = false))
                objOf(created)?.let { observe("inventoryLocation.fieldTypes", Facts.fieldTypes(it)) }
                observe("inventoryLocation.create.sameHousehold", objOf(created)?.objAt("household")?.longAt("id") == own.longAt("id"))
            }
            if (name == "Frys") observe("inventoryLocation.create.isFreezer", objOf(created)?.get("is_freezer"))
            objOf(created)?.let { locations[name] = it }
        }
        observe("inventoryLocation.create.newHouseholdCreated", householdCount()?.let { it != householdsBefore })

        val pantry = locations["Skafferi"]?.longAt("id")
        if (pantry != null) {
            val rename = api("inventory-location-patch", "PATCH", "/api/inventory-location/$pantry/", obj("name" to "Skafferiet"))
            observe("inventoryLocation.patch", describe(rename, includeBody = false))
            observe("inventoryLocation.patch.name", objOf(rename)?.stringAt("name"))
            observe("inventoryLocation.patch.householdKept", objOf(rename)?.objAt("household")?.longAt("id") == own.longAt("id"))
        }
        val list = api("inventory-location-list", "GET", "/api/inventory-location/")
        observe("inventoryLocation.list.names", pageOrArray(list.json).mapNotNull { it.stringAt("name") }.sorted())
        val asReader = api("inventory-location-list-as-reader", "GET", "/api/inventory-location/", token = env.readerToken, record = false)
        observe("inventoryLocation.reader.names", pageOrArray(asReader.json).mapNotNull { it.stringAt("name") }.sorted())
        val asOutsider = api("inventory-location-list-as-outsider", "GET", "/api/inventory-location/", token = env.outsiderToken)
        observe("inventoryLocation.outsider", describe(asOutsider, includeBody = false))
        observe("inventoryLocation.outsider.names", pageOrArray(asOutsider.json).mapNotNull { it.stringAt("name") }.sorted())
        val asGuest = api("inventory-location-list-as-guest", "GET", "/api/inventory-location/", token = env.guestToken)
        observe("inventoryLocation.guest.list", describe(asGuest, includeBody = false))
        val guestCreate = api(
            "inventory-location-create-as-guest",
            "POST",
            "/api/inventory-location/",
            obj("name" to "Gästens kyl", "is_freezer" to false, "household" to own),
            token = env.guestToken,
        )
        observe("inventoryLocation.guest.create", describe(guestCreate, includeBody = false))
    }

    // --- b. Inventory: entries and the log --------------------------------------------------------

    @Test
    @Order(40)
    fun inventoryEntries() {
        val fridge = locations["Kyl"] ?: return observe("inventoryEntry.skipped", "no location")
        val freezer = locations["Frys"]
        val fridgeId = fridge.longAt("id")

        // Chickpeas in the fridge (expires in 2 days), lemon juice with amount 0, cream without a date.
        val chickpeas = api("inventory-entry-create", "POST", "/api/inventory-entry/", entryBody(fridge, "kikärtor", 2.0, "burk", 2))
        observe("inventoryEntry.create", describe(chickpeas, includeBody = false))
        objOf(chickpeas)?.let { created ->
            observe("inventoryEntry.fieldTypes", Facts.fieldTypes(created))
            observe("inventoryEntry.create.code.shape", created.stringAt("code")?.replace(Regex("[0-9A-F]"), "X"))
            observe("inventoryEntry.create.label.shape", created.stringAt("label")?.replace(Regex("[0-9A-F]+"), "N"))
            observe("inventoryEntry.create.expires", created.stringAt("expires"))
            observe("inventoryEntry.create.expiresInDays", daysFromToday(created.stringAt("expires")))
            observe("inventoryEntry.create.amount", created["amount"])
            observe("inventoryEntry.create.subLocation", created.stringAt("sub_location"))
            observe("inventoryEntry.create.locationName", created.objAt("inventory_location")?.stringAt("name"))
            observe("inventoryEntry.create.food.keys", created.objAt("food")?.keys?.sorted())
        }
        idOf(chickpeas)?.let { entryIds["kikärtor"] = it }
        api("inventory-entry-create-zero", "POST", "/api/inventory-entry/", entryBody(fridge, "citronsaft", 0.0, "dl", 5)).let {
            observe("inventoryEntry.createZero.status", it.status)
            idOf(it)?.let { id -> entryIds["citronsaft"] = id }
        }
        api("inventory-entry-create-no-date", "POST", "/api/inventory-entry/", entryBody(fridge, "persilja", 1.0, "kruka", null)).let {
            observe("inventoryEntry.createNoExpires.status", it.status)
            observe("inventoryEntry.createNoExpires.expires", objOf(it)?.get("expires"))
            idOf(it)?.let { id -> entryIds["persilja"] = id }
        }
        if (freezer != null) {
            val berriesBody = entryBody(freezer, "blåbär", 1.0, "påse", 60)
            val berries = api("inventory-entry-create-freezer", "POST", "/api/inventory-entry/", berriesBody)
            observe("inventoryEntry.createFreezer.status", berries.status)
            idOf(berries)?.let { entryIds["blåbär"] = it }
        }

        // The nested writes: a reference with only the location id, no unit, unit null, a renamed location.
        val idOnly = JsonObject(entryBody(fridge, "spiskummin", 1.0, "burk", null) + ("inventory_location" to obj("id" to fridgeId)))
        val createdIdOnly = api("inventory-entry-create-location-id-only", "POST", "/api/inventory-entry/", idOnly)
        observe("inventoryEntry.createLocationIdOnly", describe(createdIdOnly))
        val noUnit = JsonObject(entryBody(fridge, "spiskummin", 1.0, "burk", null) - "unit")
        val createdNoUnit = api("inventory-entry-create-no-unit", "POST", "/api/inventory-entry/", noUnit)
        observe("inventoryEntry.createNoUnit", describe(createdNoUnit, includeBody = createdNoUnit.status != CREATED))
        val nullUnit = JsonObject(entryBody(fridge, "spiskummin", 1.0, "burk", null) + ("unit" to JsonNull))
        val createdNullUnit = api("inventory-entry-create-null-unit", "POST", "/api/inventory-entry/", nullUnit)
        observe("inventoryEntry.createNullUnit", describe(createdNullUnit, includeBody = createdNullUnit.status != CREATED))
        for (extra in listOf(createdIdOnly, createdNoUnit, createdNullUnit)) idOf(extra)?.let { delete("inventory-entry", it) }
        val renamed = JsonObject(fridge + ("name" to JsonPrimitive("Kylen (omdöpt)")))
        val renaming = api(
            "inventory-entry-create-renamed-location",
            "POST",
            "/api/inventory-entry/",
            JsonObject(entryBody(fridge, "spiskummin", 1.0, "burk", null) + ("inventory_location" to renamed)),
            record = false,
        )
        observe("inventoryEntry.createRenamedLocation.status", renaming.status)
        val fridgeAfter = fridgeId?.let { objOf(api("inventory-location-get", "GET", "/api/inventory-location/$it/", record = false)) }
        observe("inventoryEntry.createRenamedLocation.locationRenamed", fridgeAfter?.stringAt("name") == "Kylen (omdöpt)")
        idOf(renaming)?.let { delete("inventory-entry", it) }
        fridgeId?.let { api("inventory-location-restore", "PATCH", "/api/inventory-location/$it/", obj("name" to "Kyl"), record = false) }

        // Lists, filters and ordering.
        val list = api("inventory-entry-list", "GET", "/api/inventory-entry/?page_size=100")
        observe("inventoryEntry.list.keys", objOf(list)?.keys?.toList())
        observe("inventoryEntry.list.foodsInOrder", foodsOf(list))
        observe("inventoryEntry.list.empty=true", foodsOf(read("/api/inventory-entry/?empty=true&page_size=100")))
        observe("inventoryEntry.list.empty=false", foodsOf(read("/api/inventory-entry/?empty=false&page_size=100")))
        foodIds["kikärtor"]?.let { food ->
            observe("inventoryEntry.list.foodId", foodsOf(api("inventory-entry-list-food", "GET", "/api/inventory-entry/?food_id=$food")))
        }
        freezer?.longAt("id")?.let { location ->
            val path = "/api/inventory-entry/?inventory_location_id=$location"
            observe("inventoryEntry.list.locationId", foodsOf(api("inventory-entry-list-location", "GET", path)))
        }
        val code = entryIds["kikärtor"]?.let { objOf(api("ie-get", "GET", "/api/inventory-entry/$it/", record = false))?.stringAt("code") }
        code?.let { observe("inventoryEntry.list.code", foodsOf(read("/api/inventory-entry/?code=${enc(it)}"))) }
        observe("inventoryEntry.list.reader", foodsOf(read("/api/inventory-entry/?page_size=100", env.readerToken)))
        observe("inventoryEntry.list.outsider", foodsOf(read("/api/inventory-entry/?page_size=100", env.outsiderToken)))
        val guestList = api("inventory-entry-list-as-guest", "GET", "/api/inventory-entry/", token = env.guestToken)
        observe("inventoryEntry.guest.list", describe(guestList, includeBody = false))
        val honey = entryBody(fridge, "honung", 1.0, "burk", null)
        val guestCreate = api("inventory-entry-create-as-guest", "POST", "/api/inventory-entry/", honey, token = env.guestToken)
        observe("inventoryEntry.guest.create", describe(guestCreate, includeBody = false))
        val readerCreate = api(
            "inventory-entry-create-as-reader",
            "POST",
            "/api/inventory-entry/",
            entryBody(fridge, "honung", 1.0, "burk", null),
            token = env.readerToken,
            record = false,
        )
        observe("inventoryEntry.reader.create.status", readerCreate.status)
        val ownerList = idsOf(read("/api/inventory-entry/?page_size=100"))
        observe("inventoryEntry.reader.create.ownerSees", idOf(readerCreate)?.let { id -> id in ownerList })
        idOf(readerCreate)?.let { delete("inventory-entry", it) }

        // Changes and the log: -1, "Slut" (0), +1 and a move to the freezer.
        val entry = entryIds["kikärtor"]
        if (entry != null) {
            val path = "/api/inventory-entry/$entry/"
            val minusOne = api("inventory-entry-patch-amount", "PATCH", path, obj("amount" to 1.0))
            observe("inventoryEntry.patchAmount", describe(minusOne, includeBody = false))
            observe("inventoryEntry.patchAmount.amount", objOf(minusOne)?.get("amount"))
            val zero = api("inventory-entry-patch-zero", "PATCH", path, obj("amount" to 0.0))
            observe("inventoryEntry.patchZero.status", zero.status)
            observe("inventoryEntry.patchZero.listed", entry in idsOf(read("/api/inventory-entry/?page_size=100")))
            api("inventory-entry-patch-up", "PATCH", path, obj("amount" to 2.0), record = false)
            val note = api("inventory-entry-patch-note", "PATCH", path, obj("note" to "Öppnad", "expires" to today.plusDays(1).toString()))
            observe("inventoryEntry.patchNoteExpires.status", note.status)
            observe("inventoryEntry.patchNoteExpires.expires", objOf(note)?.stringAt("expires"))
            observe("inventoryEntry.patchNoteExpires.expiresInDays", daysFromToday(objOf(note)?.stringAt("expires")))
            if (freezer != null) {
                val move = api("inventory-entry-patch-move", "PATCH", path, obj("inventory_location" to freezer))
                observe("inventoryEntry.patchMove.status", move.status)
                observe("inventoryEntry.patchMove.location", objOf(move)?.objAt("inventory_location")?.stringAt("name"))
                // Back to the fridge for the makenow checks.
                api("inventory-entry-patch-back", "PATCH", path, obj("inventory_location" to fridge), record = false)
            }
            val log = api("inventory-log-list-entry", "GET", "/api/inventory-log/?entry_id=$entry&page_size=100")
            val rows = pageOrArray(log.json)
            observe("inventoryLog.list.keys", objOf(log)?.keys?.toList())
            rows.firstOrNull()?.let { observe("inventoryLog.fieldTypes", Facts.fieldTypes(it)) }
            observe(
                "inventoryLog.entry.rowsNewestFirst",
                rows.map { "${it.stringAt("booking_type")}:${it["old_amount"]}->${it["new_amount"]}" },
            )
            observe(
                "inventoryLog.entry.locations",
                rows.map { row -> locationName(row, "old") + "->" + locationName(row, "new") },
            )
            foodIds["kikärtor"]?.let { food ->
                observe("inventoryLog.foodId.count", pageOrArray(read("/api/inventory-log/?food_id=$food&page_size=100").json).size)
            }
            val writeLog = api("inventory-log-create", "POST", "/api/inventory-log/", obj("note" to "x"), record = false)
            observe("inventoryLog.create.status", writeLog.status)
        }

        // Delete: the entry and what the log keeps.
        val doomed = api("ie-doomed", "POST", "/api/inventory-entry/", entryBody(fridge, "honung", 1.0, "burk", null), record = false)
        idOf(doomed)?.let { id ->
            observe("inventoryEntry.delete.status", api("inventory-entry-delete", "DELETE", "/api/inventory-entry/$id/").status)
            observe("inventoryEntry.deleted.get", describe(api("inventory-entry-deleted-get", "GET", "/api/inventory-entry/$id/")))
            val deletedPatch = api("ie-deleted-patch", "PATCH", "/api/inventory-entry/$id/", obj("amount" to 1), record = false)
            observe("inventoryEntry.deleted.patch.status", deletedPatch.status)
            val logs = api("il-deleted", "GET", "/api/inventory-log/?entry_id=$id", record = false)
            observe("inventoryLog.afterEntryDelete.count", pageOrArray(logs.json).size)
        }
        // A location with entries: what DELETE does (CASCADE in the model).
        val pantry = locations["Skafferi"]
        if (pantry != null) {
            val inPantry = api("ie-pantry", "POST", "/api/inventory-entry/", entryBody(pantry, "honung", 1.0, "burk", null), record = false)
            val pantryId = pantry.longAt("id")
            val deleted = api("inventory-location-delete", "DELETE", "/api/inventory-location/$pantryId/")
            observe("inventoryLocation.delete.status", deleted.status)
            idOf(inPantry)?.let { id ->
                observe("inventoryLocation.delete.entryGet.status", read("/api/inventory-entry/$id/").status)
            }
        }
    }

    // --- a. makenow as an integer -----------------------------------------------------------------

    @Test
    @Order(50)
    fun makeNow() {
        // State now: tahini on hand (PATCH), olive oil ignore_shopping, oats and milk on hand (batch),
        // oat drink -> substitute milk, chickpeas 2 in the fridge, lemon juice 0, parsley 1, blueberries 1.
        // Hummus: lemon juice missing (amount 0) -> 1. Tahinidressing: tahini + oil -> 0.
        // Gröt: oats on hand, oat drink by substitute, blueberries in the freezer -> 0.
        // Falafel: chickpeas + parsley in the fridge, cumin and chickpea flour missing -> 2.
        for (value in listOf("0", "1", "2", "3", "true")) {
            val path = "/api/recipe/?makenow=$value&page_size=100"
            observe("makenow.$value", ours(api("recipe-makenow-$value", "GET", path, record = value == "1")))
        }
        val all = ours(api("recipe-all", "GET", "/api/recipe/?page_size=100", record = false))
        observe("makenow.none", all)
        for (value in listOf("false", "abc", "-1", "1.5", "")) {
            val ex = api("recipe-makenow-invalid", "GET", "/api/recipe/?makenow=${enc(value)}&page_size=100", record = false)
            observe("makenow.invalid.$value", mapOf("status" to ex.status, "recipes" to ours(ex)))
        }
        // Lemon juice 0 -> 1: Hummus moves to 0 (amount > 0 counts, 0 does not).
        val lemon = entryIds["citronsaft"]
        if (lemon != null) {
            api("ie-lemon", "PATCH", "/api/inventory-entry/$lemon/", obj("amount" to 1.0), record = false)
            observe("makenow.0.afterLemonJuice", ours(api("mn0b", "GET", "/api/recipe/?makenow=0&page_size=100", record = false)))
            api("ie-lemon-back", "PATCH", "/api/inventory-entry/$lemon/", obj("amount" to 0.0), record = false)
        }
        // Without the substitute's on-hand: Gröt then misses the oat drink.
        val milk = foodIds[SUBSTITUTE]
        if (milk != null) {
            api("food-milk-off", "PATCH", "/api/food/$milk/", obj("food_onhand" to false), record = false)
            observe("makenow.0.withoutSubstitute", ours(api("mn0c", "GET", "/api/recipe/?makenow=0&page_size=100", record = false)))
            observe("makenow.1.withoutSubstitute", ours(api("mn1c", "GET", "/api/recipe/?makenow=1&page_size=100", record = false)))
            api("food-milk-on", "PATCH", "/api/food/$milk/", obj("food_onhand" to true), record = false)
        }
        // Who sees what: the other member of the household, the outsider (another household), the guest.
        for ((who, token) in listOf("reader" to env.readerToken, "outsider" to env.outsiderToken, "guest" to env.guestToken)) {
            val ex = api("recipe-makenow-0-as-$who", "GET", "/api/recipe/?makenow=0&page_size=100", token = token, record = false)
            observe("makenow.0.$who", mapOf("status" to ex.status, "recipes" to ours(ex)))
        }
        // "Använd upp" (#28): makenow together with foods_or.
        foodIds["kikärtor"]?.let { food ->
            val path = "/api/recipe/?makenow=2&foods_or=$food&page_size=100"
            observe("makenow.2.foodsOrChickpeas", ours(api("recipe-makenow-foods-or", "GET", path)))
        }
        val random = api("recipe-makenow-random", "GET", "/api/recipe/?makenow=1&random=true&page_size=100", record = false)
        observe("makenow.1.random", mapOf("status" to random.status, "recipes" to ours(random).sorted()))
    }

    // --- c. Keywords: pairing tree, move, keywords_or + include_children, the partner's recipe ------

    @Test
    @Order(60)
    fun keywords() {
        val parent = api("keyword-create-root", "POST", "/api/keyword/", obj("name" to PAIRS_WITH, "description" to ""))
        observe("keyword.create", describe(parent, includeBody = false))
        objOf(parent)?.let { observe("keyword.fieldTypes", Facts.fieldTypes(it)) }
        val parentId = idOf(parent)
        val again = api("keyword-create-duplicate", "POST", "/api/keyword/", obj("name" to PAIRS_WITH), record = false)
        observe("keyword.createDuplicate.status", again.status)
        observe("keyword.createDuplicate.sameId", idOf(again) != null && idOf(again) == parentId)
        val caseOnly = api("keyword-create-case", "POST", "/api/keyword/", obj("name" to PAIRS_WITH.lowercase()), record = false)
        observe("keyword.createOtherCase.sameId", idOf(caseOnly) != null && idOf(caseOnly) == parentId)
        caseOnly.takeIf { idOf(it) != parentId }?.let { idOf(it)?.let { id -> delete("keyword", id) } }

        val withParent = api("keyword-create-with-parent", "POST", "/api/keyword/", obj("name" to PAIRING_TYPE, "parent" to parentId))
        observe("keyword.createWithParent.status", withParent.status)
        observe("keyword.createWithParent.parentSet", objOf(withParent)?.longAt("parent") == parentId)
        val childId = idOf(withParent)
        if (childId != null && parentId != null) {
            val move = api("keyword-move", "PUT", "/api/keyword/$childId/move/$parentId/", obj("name" to PAIRING_TYPE))
            observe("keyword.move", describe(move))
            val child = objOf(api("keyword-get-child", "GET", "/api/keyword/$childId/"))
            observe("keyword.move.parentSet", child?.longAt("parent") == parentId)
            observe("keyword.child.fullName", child?.stringAt("full_name"))
            observe("keyword.child.label", child?.stringAt("label"))
            observe("keyword.parent.numchild", objOf(api("kw-parent", "GET", "/api/keyword/$parentId/", record = false))?.get("numchild"))
            val emptyBody = api("keyword-move-empty-body", "PUT", "/api/keyword/$childId/move/$parentId/", null, record = false)
            observe("keyword.moveEmptyBody.status", emptyBody.status)
            val root = api("keyword-root-children", "GET", "/api/keyword/?root=$parentId", record = false)
            observe("keyword.rootChildren.names", pageOrArray(root.json).mapNotNull { it.stringAt("name") })
            observe(
                "keyword.query.child",
                pageOrArray(read("/api/keyword/?query=${enc(PAIRING_TYPE)}").json).mapNotNull { it.stringAt("name") },
            )
        }
        val guestKeyword = api("keyword-create-as-guest", "POST", "/api/keyword/", obj("name" to "Gästens ord"), token = env.guestToken)
        observe("keyword.guest.create", describe(guestKeyword, includeBody = false))
        val readerKeyword = api(
            "keyword-create-as-reader",
            "POST",
            "/api/keyword/",
            obj("name" to "Medlemmens ord"),
            token = env.readerToken,
            record = false,
        )
        observe("keyword.reader.create.status", readerKeyword.status)
        idOf(readerKeyword)?.let { delete("keyword", it) }

        // The pairing on the owner's recipe (Hummus), PATCHed by the other member, and the drink type on Falafel.
        val hummus = recipeIds["Hummus"]
        val falafel = recipeIds["Falafel"]
        if (hummus != null && childId != null && parentId != null) {
            val path = "/api/recipe/$hummus/"
            val existing = keywordRefs(objOf(api("recipe-get-hummus", "GET", path, record = false)))
            observe("pairing.before.keywords", existing.map { it.stringAt("name") })
            val withPairing = existing + obj("id" to childId, "name" to PAIRING_TYPE)
            val patch = api("recipe-patch-keywords-as-member", "PATCH", path, obj("keywords" to withPairing), token = env.readerToken)
            observe("pairing.memberPatch.status", patch.status)
            observe("pairing.memberPatch.keywords", keywordRefs(objOf(patch)).mapNotNull { it.stringAt("name") }.sorted())
            // Only the new one: does the PATCH replace the list (the client must read before PATCH)?
            val onlyPairing = obj("keywords" to listOf(obj("id" to childId, "name" to PAIRING_TYPE)))
            val replace = api("recipe-patch-keywords-replace", "PATCH", path, onlyPairing)
            observe("pairing.patchReplaces.keywords", keywordRefs(objOf(replace)).mapNotNull { it.stringAt("name") }.sorted())
            api("recipe-restore-keywords", "PATCH", path, obj("keywords" to withPairing), record = false)
            // A keyword given only by id.
            val idOnly = obj("keywords" to (existing + obj("id" to childId)))
            val byId = api("recipe-patch-keywords-id-only", "PATCH", path, idOnly, record = false)
            observe("pairing.patchIdOnly", describe(byId, includeBody = byId.status != OK))
            api("recipe-restore-keywords-2", "PATCH", path, obj("keywords" to withPairing), record = false)
            val pairingBody = obj("keywords" to withPairing)
            val guest = api("recipe-patch-keywords-as-guest", "PATCH", path, pairingBody, token = env.guestToken, record = false)
            observe("pairing.guestPatch.status", guest.status)
            val outsider = api("recipe-patch-keywords-as-outsider", "PATCH", path, pairingBody, token = env.outsiderToken, record = false)
            observe("pairing.outsiderPatch.status", outsider.status)
            api("recipe-restore-keywords-3", "PATCH", path, obj("keywords" to withPairing), record = false)
        }
        if (falafel != null && parentId != null) {
            val path = "/api/recipe/$falafel/"
            val existing = keywordRefs(objOf(api("recipe-get-falafel", "GET", path, record = false)))
            val withParent = obj("keywords" to (existing + obj("id" to parentId, "name" to PAIRS_WITH)))
            api("recipe-patch-parent-keyword", "PATCH", path, withParent, record = false)
        }
        if (parentId != null) {
            for (children in listOf("true", "false")) {
                val ex = api(
                    "recipe-keywords-or-children-$children",
                    "GET",
                    "/api/recipe/?keywords_or=$parentId&include_children=$children&page_size=100",
                    record = children == "true",
                )
                observe("pairing.keywordsOrParent.includeChildren=$children", ours(ex))
            }
            observe("pairing.keywordsOrParent.default", ours(read("/api/recipe/?keywords_or=$parentId&page_size=100")))
        }
        if (childId != null) {
            val other = read("/api/recipe/?keywords_or=$childId&page_size=100", env.readerToken)
            observe("pairing.keywordsOrChild.reader", ours(other))
        }
        drinkKeywordId = childId
    }

    // --- f. A drink: a minimal recipe, its image, flat and search ---------------------------------

    @Test
    @Order(70)
    fun drink() {
        val keywords = listOf(obj("name" to DRINK), obj("name" to "Vin"))
        val base = obj("name" to DRINK_NAME, "description" to "Fruktigt och kryddigt. Ca 120 kr.", "keywords" to keywords, "servings" to 1)
        val noSteps = api("recipe-create-drink-no-steps", "POST", "/api/recipe/", base)
        observe("drink.createNoSteps", describe(noSteps, includeBody = noSteps.status != CREATED))
        idOf(noSteps)?.let { delete("recipe", it) }
        // `internal: true` as the web's own editor creates recipes (without it the server stores false).
        val withEmptySteps = JsonObject(base + ("steps" to JsonArray(emptyList())) + ("internal" to JsonPrimitive(true)))
        val emptySteps = api("recipe-create-drink-empty-steps", "POST", "/api/recipe/", withEmptySteps)
        observe("drink.createEmptySteps", describe(emptySteps, includeBody = emptySteps.status != CREATED))
        val emptyStep = obj("instruction" to "", "ingredients" to emptyList<Any>())
        val oneStep = api(
            "recipe-create-drink-one-empty-step",
            "POST",
            "/api/recipe/",
            JsonObject(base + ("name" to JsonPrimitive("$DRINK_NAME (ett steg)")) + ("steps" to JsonArray(listOf(emptyStep)))),
        )
        observe("drink.createOneEmptyStep", describe(oneStep, includeBody = oneStep.status != CREATED))
        idOf(oneStep)?.let { delete("recipe", it) }
        val id = idOf(emptySteps)?.takeIf { emptySteps.status == CREATED } ?: return observe("drink.skipped", "no drink created")
        val created = emptySteps
        drinkId = id
        objOf(created)?.let { drink ->
            observe("drink.create.keywords", keywordRefs(drink).mapNotNull { it.stringAt("name") }.sorted())
            observe("drink.create.servings", drink["servings"])
            observe("drink.create.internal", drink["internal"])
            observe("drink.create.private", drink["private"])
            observe("drink.create.steps", drink.arrAt("steps").size)
        }
        val guest = api("recipe-create-as-guest", "POST", "/api/recipe/", withEmptySteps, token = env.guestToken)
        observe("drink.guest.create", describe(guest, includeBody = false))
        idOf(guest)?.let { delete("recipe", it) }

        // Image (multipart PUT, as in Fas 1).
        val png = tinyPng(DRINK_COLOR)
        val body = imageForm(png)
        val recordBody = buildJsonObject { put("image", "<multipart file tandem-dryck.png, image/png, ${png.size} bytes>") }
        val upload = http.call(
            "recipe-image-put-drink",
            "PUT",
            "/api/recipe/$id/image/",
            bearer = env.fas1Token,
            body = body,
            recordBody = recordBody,
        )
        observe("drink.image.status", upload.status)
        val postImage = http.call(
            "recipe-image-post-drink",
            "POST",
            "/api/recipe/$id/image/",
            bearer = env.fas1Token,
            body = imageForm(png),
            recordBody = recordBody,
            record = false,
        )
        observe("drink.imagePost.status", postImage.status)

        val flat = api("recipe-flat", "GET", "/api/recipe/flat/", record = false)
        val inFlat = pageOrArray(flat.json).firstOrNull { it.longAt("id") == id }
        observe("drink.flat.listed", inFlat != null)
        observe("drink.flat.hasImage", inFlat?.stringAt("image") != null)
        val search = api("recipe-search-drink", "GET", "/api/recipe/?query=${enc("Rioja")}&page_size=100")
        observe("drink.search.names", pageOrArray(search.json).mapNotNull { it.stringAt("name") })
        val drinkKeyword = keywordRefs(objOf(created)).firstOrNull { it.stringAt("name") == DRINK }?.longAt("id")
        drinkKeyword?.let { kw ->
            val byKeyword = api("recipe-keywords-or-drink", "GET", "/api/recipe/?keywords_or=$kw&page_size=100", record = false)
            observe("drink.keywordsOr.names", pageOrArray(byKeyword.json).mapNotNull { it.stringAt("name") })
        }
        // A recipe without ingredients misses nothing: it is always in makenow=0.
        val mn0 = api("mn0-drink", "GET", "/api/recipe/?makenow=0&page_size=100", record = false)
        observe("drink.makenow0.listed", id in idsOf(mn0))

        // Pairing from the drink's side: the drink gets the pairing keyword too.
        val pairing = drinkKeywordId
        if (pairing != null) {
            val existing = keywordRefs(objOf(api("recipe-get-drink", "GET", "/api/recipe/$id/", record = false)))
            val patch = api(
                "recipe-patch-drink-pairing",
                "PATCH",
                "/api/recipe/$id/",
                obj("keywords" to (existing + obj("id" to pairing, "name" to PAIRING_TYPE))),
                token = env.readerToken,
                record = false,
            )
            observe("drink.pairingPatch.status", patch.status)
            val both = api("recipe-keywords-or-pairing", "GET", "/api/recipe/?keywords_or=$pairing&page_size=100", record = false)
            observe("drink.pairing.bothSides", pageOrArray(both.json).mapNotNull { it.stringAt("name") }.sorted())
        }
    }

    // --- d. Automations: FOOD_ALIAS ------------------------------------------------------------------

    @Test
    @Order(80)
    fun foodAliases() {
        val barcode = alias(BARCODE_ALIAS, BARCODE, "kikärtor")
        val create = api("automation-create-barcode", "POST", "/api/automation/", barcode)
        observe("automation.create", describe(create, includeBody = false))
        objOf(create)?.let { observe("automation.fieldTypes", Facts.fieldTypes(it)) }
        val defaults = objOf(create)?.let { created ->
            mapOf("order" to created["order"], "disabled" to created["disabled"], "description" to created["description"])
        }
        observe("automation.create.defaults", defaults)
        val barcodeId = idOf(create)
        val receipt = api("automation-create-receipt", "POST", "/api/automation/", alias(RECEIPT_ALIAS, RECEIPT_LINE, "kikärtor"))
        observe("automation.createReceipt.status", receipt.status)
        val receiptId = idOf(receipt)

        val list = api("automation-list-food-alias", "GET", "/api/automation/?type=FOOD_ALIAS&page_size=100")
        observe("automation.list.keys", objOf(list)?.keys?.toList())
        observe("automation.list.params", pageOrArray(list.json).map { it.stringAt("param_1") })
        observe("automation.list.otherType", pageOrArray(read("/api/automation/?type=UNIT_ALIAS").json).size)
        val asReader = read("/api/automation/?type=FOOD_ALIAS&page_size=100", env.readerToken)
        observe("automation.reader.params", pageOrArray(asReader.json).mapNotNull { it.stringAt("param_1") }.sorted())
        val asOutsider = read("/api/automation/?type=FOOD_ALIAS&page_size=100", env.outsiderToken)
        observe("automation.outsider.params", pageOrArray(asOutsider.json).map { it.stringAt("param_1") })
        val asGuest = api("automation-list-as-guest", "GET", "/api/automation/?type=FOOD_ALIAS", token = env.guestToken)
        observe("automation.guest.list", describe(asGuest, includeBody = false))
        val guestAlias = alias(BARCODE_ALIAS, "2000000000022", "honung")
        val guestCreate = api("automation-create-as-guest", "POST", "/api/automation/", guestAlias, token = env.guestToken)
        observe("automation.guest.create", describe(guestCreate, includeBody = false))
        val readerAlias = alias(BARCODE_ALIAS, "2000000000039", "honung")
        val readerCreate = api("al-reader", "POST", "/api/automation/", readerAlias, token = env.readerToken, record = false)
        observe("automation.reader.create.status", readerCreate.status)
        idOf(readerCreate)?.let { delete("automation", it) }
        // The same param_1 twice: no unique constraint?
        val twice = api("automation-create-duplicate", "POST", "/api/automation/", alias(BARCODE_ALIAS, BARCODE, "honung"), record = false)
        observe("automation.createDuplicate.status", twice.status)
        idOf(twice)?.let { delete("automation", it) }

        // Length: 128 is allowed, 129 is not.
        val maxAlias = alias(RECEIPT_ALIAS, "A".repeat(MAX_PARAM), "honung")
        val max = api("automation-create-128", "POST", "/api/automation/", maxAlias, record = false)
        observe("automation.param128.status", max.status)
        idOf(max)?.let { delete("automation", it) }
        val tooLong = api("automation-create-129", "POST", "/api/automation/", alias(RECEIPT_ALIAS, "A".repeat(MAX_PARAM + 1), "honung"))
        observe("automation.param129", describe(tooLong))
        val longNameAlias = JsonObject(alias(RECEIPT_ALIAS, "X1", "honung") + ("name" to JsonPrimitive("N".repeat(MAX_PARAM + 1))))
        val longName = api("automation-create-long-name", "POST", "/api/automation/", longNameAlias, record = false)
        observe("automation.name129.status", longName.status)
        idOf(longName)?.let { delete("automation", it) }

        // The ingredient parser: a FOOD_ALIAS rewrites the food (case-insensitive), a misspelled name.
        val misspelledAlias = alias(RECEIPT_ALIAS, "KIKÄRTER", "kikärtor")
        val misspelled = api("automation-create-misspelling", "POST", "/api/automation/", misspelledAlias, record = false)
        val misspelledId = idOf(misspelled)
        for ((label, text) in listOf("lower" to "2 kikärter", "upper" to "2 KIKÄRTER", "barcode" to "1 $BARCODE")) {
            val parserBody = obj("ingredient" to text)
            val parserPath = "/api/ingredient-parser/post/"
            val parsed = api("ingredient-parser-alias-$label", "POST", parserPath, parserBody, record = label == "lower")
            observe("automation.parser.$label", objOf(parsed)?.objAt("ingredient")?.objAt("food")?.stringAt("name"))
        }

        // Update and delete.
        if (barcodeId != null) {
            val patch = api("automation-patch", "PATCH", "/api/automation/$barcodeId/", obj("param_2" to "kikärtor eko"))
            observe("automation.patch", describe(patch, includeBody = false))
            observe("automation.patch.param2", objOf(patch)?.stringAt("param_2"))
            val guestPatch = api(
                "automation-patch-as-guest",
                "PATCH",
                "/api/automation/$barcodeId/",
                obj("param_2" to "x"),
                token = env.guestToken,
                record = false,
            )
            observe("automation.guest.patch.status", guestPatch.status)
            observe("automation.delete.status", api("automation-delete", "DELETE", "/api/automation/$barcodeId/").status)
            observe("automation.deleted.get", describe(api("automation-deleted-get", "GET", "/api/automation/$barcodeId/")))
        }
        receiptId?.let { delete("automation", it) }
        misspelledId?.let { delete("automation", it) }
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
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the Fas S baseline:\n" + mismatches.joinToString("\n"))
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

    private fun delete(kind: String, id: Long) {
        api("cleanup-$kind", "DELETE", "/api/$kind/$id/", record = false)
    }

    private fun householdCount(): Int? = read("/api/household/").json?.let { pageOrArray(it).size }

    /** An unrecorded GET. */
    private fun read(path: String, token: String = env.fas1Token): Exchange = api("read", "GET", path, token = token, record = false)

    private fun locationName(row: JsonObject, side: String): String? = row.objAt("${side}_inventory_location")?.stringAt("name")

    private fun imageForm(png: ByteArray): MultipartBody =
        MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image", "tandem-dryck.png", png.toRequestBody(PNG)).build()

    private fun foodField(id: Long, field: String, token: String = env.fas1Token): JsonElement? =
        objOf(api("food-field", "GET", "/api/food/$id/", token = token, record = false))?.get(field)

    /** The Fas S recipes in a recipe list (the space holds the recipes of Fas 1 and 2 too), sorted. */
    private fun ours(ex: Exchange): List<String> {
        val names = MAKENOW_RECIPES.map { it.first }.toSet() + DRINK_NAME
        return pageOrArray(ex.json).mapNotNull { it.stringAt("name") }.filter { it in names }.sorted()
    }

    /** Days from the run day to a `yyyy-MM-dd` date (dates are shifted in the recordings, day counts are not). */
    private fun daysFromToday(date: String?): Long? =
        date?.let { runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(it)) }.getOrNull() }

    private fun foodsOf(ex: Exchange): List<String?> = pageOrArray(ex.json).map { it.objAt("food")?.stringAt("name") }

    private fun keywordRefs(recipe: JsonObject?): List<JsonObject> =
        recipe?.arrAt("keywords").objects().map { obj("id" to it.longAt("id"), "name" to it.stringAt("name")) }

    private fun entryBody(location: JsonObject, food: String, amount: Double, unit: String, expiresInDays: Long?): JsonObject = obj(
        "inventory_location" to location,
        "food" to obj("name" to food),
        "unit" to obj("name" to unit),
        "amount" to amount,
        "expires" to expiresInDays?.let { today.plusDays(it).toString() },
        "note" to "",
        "sub_location" to if (food == "kikärtor") "Översta hyllan" else null,
    )

    private fun alias(name: String, param: String, food: String): JsonObject = obj(
        "type" to "FOOD_ALIAS",
        "name" to name,
        "description" to "",
        "param_1" to param,
        "param_2" to food,
    )

    private fun recipeBody(name: String, foods: List<String>): JsonObject {
        val ingredients = foods.mapIndexed { index, food ->
            obj(
                "food" to obj("name" to food),
                "unit" to obj("name" to "st"),
                "amount" to 1,
                "note" to "",
                "order" to index,
                "is_header" to false,
                "no_amount" to false,
            )
        }
        val step = obj("name" to "", "instruction" to "Blanda.", "order" to 0, "show_as_header" to false, "ingredients" to ingredients)
        return obj(
            "name" to name,
            "description" to "",
            "servings" to 2,
            "servings_text" to "portioner",
            "internal" to true,
            "keywords" to emptyList<Any>(),
            "steps" to listOf(step),
        )
    }

    private fun tinyPng(rgb: Int): ByteArray {
        val image = BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until IMAGE_SIZE) for (y in 0 until IMAGE_SIZE) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun objOf(ex: Exchange): JsonObject? = ex.json as? JsonObject

    private fun idOf(ex: Exchange): Long? = objOf(ex)?.longAt("id")

    private fun idsOf(ex: Exchange): List<Long> = pageOrArray(ex.json).mapNotNull { it.longAt("id") }

    /** The `results` of a DRF page, or the elements of a plain array. */
    private fun pageOrArray(json: JsonElement?): List<JsonObject> = when (json) {
        is JsonObject -> json.arrAt("results").objects()
        is JsonArray -> json.objects()
        else -> emptyList()
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to Facts.toJson(value) })

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun describe(ex: Exchange, includeBody: Boolean = true): Map<String, Any?> = buildMap {
        put("status", ex.status)
        put("contentType", ex.contentType?.substringBefore(';'))
        put("isJson", ex.json != null)
        if (includeBody) put("body", ex.json ?: JsonPrimitive(ex.bodyText.take(MAX_TEXT)))
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val PNG = "image/png".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val OK = 200
        const val CREATED = 201
        const val MAX_TEXT = 200
        const val MAX_PARAM = 128
        const val IMAGE_SIZE = 8
        const val DRINK_COLOR = 0x7B1E3A
        const val SUBSTITUTE = "mellanmjölk"
        const val BATCH_FOOD = "kardemummakaka"
        const val PAIRS_WITH = "Passar med"
        const val PAIRING_TYPE = "Kraftigt rött"
        const val DRINK = "Dryck"
        const val DRINK_NAME = "Rioja Crianza"
        const val BARCODE_ALIAS = "Tandem: streckkod"
        const val RECEIPT_ALIAS = "Tandem: kvitto"

        /** A synthetic in-store EAN-13 (GS1 prefix 20-29 is never a real product) and a made-up receipt line. */
        const val BARCODE = "2000000000015"
        const val RECEIPT_LINE = "KIKÄRTOR 380G EKO"

        /** The Fas S recipes and their foods (names no earlier phase uses, so their state is known). */
        val MAKENOW_RECIPES: List<Pair<String, List<String>>> = listOf(
            "Hummus" to listOf("kikärtor", "tahini", "citronsaft", "olivolja"),
            "Tahinidressing" to listOf("tahini", "olivolja"),
            "Gröt med blåbär" to listOf("havregryn", "havredryck", "blåbär"),
            "Falafel" to listOf("kikärtor", "persilja", "spiskummin", "kikärtsmjöl"),
        )

        /** Every Fas S recipe of [MAKENOW_RECIPES], sorted (what an ignored makenow filter lists). */
        val ALL_FOUR: List<String> = MAKENOW_RECIPES.map { it.first }.sorted()

        /**
         * The facts the app relies on (api-schema/fixtures/<version>/FACTS.md "Fas S", docs/BYGGE.md
         * "Fas S – kontrakt"). A key ending in `.status` also matches the status of a recorded
         * response description. Every other fact is only recorded; tandoor-watch compares it.
         */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "household.reader.sameAsOwner" to true,
            "household.outsider.sameAsOwner" to false,
            // e. Foods
            "food.patchOnHand.status" to 200,
            "food.patchOnHand.value" to true,
            "food.patchOnHand.reader" to true,
            "food.patchOnHand.outsider" to false,
            "food.patchIgnoreShopping.value" to true,
            "food.batchOnHand.status" to 200,
            "food.batchOnHand.values" to listOf(true, true, true),
            "food.batchOnHand.reader" to true,
            "food.batchOnHand.outsider" to false,
            "food.batchOff.value" to false,
            "food.batchIgnoreShopping.value" to true,
            "food.batchOnHand.guest.status" to 403,
            "food.substitute.names" to listOf(SUBSTITUTE),
            // b. Inventory locations
            "inventoryLocation.create.status" to 201,
            "inventoryLocation.create.sameHousehold" to true,
            "inventoryLocation.create.newHouseholdCreated" to false,
            "inventoryLocation.create.isFreezer" to true,
            "inventoryLocation.patch.householdKept" to true,
            "inventoryLocation.reader.names" to listOf("Frys", "Kyl", "Skafferiet"),
            "inventoryLocation.guest.list.status" to 403,
            "inventoryLocation.guest.create.status" to 403,
            "inventoryLocation.createNoHousehold.status" to 400,
            // Locations and entries are SPACE-wide: another household sees them (the app filters by household).
            "inventoryLocation.outsider.names" to listOf("Frys", "Kyl", "Skafferiet"),
            "inventoryLocation.delete.status" to 204,
            "inventoryLocation.delete.entryGet.status" to 404,
            // b. Inventory entries and the log
            "inventoryEntry.create.status" to 201,
            "inventoryEntry.createZero.status" to 201,
            "inventoryEntry.createNoExpires.status" to 201,
            "inventoryEntry.list.foodsInOrder" to listOf("kikärtor", "blåbär", "persilja"),
            "inventoryEntry.list.empty=true" to listOf("kikärtor", "citronsaft", "blåbär", "persilja"),
            "inventoryEntry.list.foodId" to listOf("kikärtor"),
            "inventoryEntry.list.locationId" to listOf("blåbär"),
            "inventoryEntry.list.reader" to listOf("kikärtor", "blåbär", "persilja"),
            "inventoryEntry.list.outsider" to listOf("kikärtor", "blåbär", "persilja"),
            // `empty` in the query at all (also empty=false) lists the entries with amount 0 too.
            "inventoryEntry.list.empty=false" to listOf("kikärtor", "citronsaft", "blåbär", "persilja"),
            "inventoryEntry.list.code" to listOf("kikärtor"),
            "inventoryEntry.create.expiresInDays" to 2,
            "inventoryEntry.create.amount" to 2.0,
            // The nested location must be the whole object; its fields are WRITTEN (a different name renames it).
            "inventoryEntry.createLocationIdOnly.status" to 400,
            "inventoryEntry.createRenamedLocation.locationRenamed" to true,
            // A unit is required (not null).
            "inventoryEntry.createNoUnit.status" to 400,
            "inventoryEntry.createNullUnit.status" to 400,
            "inventoryEntry.createNoExpires.expires" to null,
            "inventoryEntry.patchNoteExpires.expiresInDays" to 1,
            "inventoryEntry.deleted.patch.status" to 404,
            "inventoryLog.create.status" to 405,
            "inventoryLog.afterEntryDelete.count" to 0,
            "inventoryEntry.guest.list.status" to 403,
            "inventoryEntry.guest.create.status" to 403,
            "inventoryEntry.reader.create.status" to 201,
            "inventoryEntry.reader.create.ownerSees" to true,
            "inventoryEntry.patchAmount.status" to 200,
            "inventoryEntry.patchZero.listed" to false,
            "inventoryEntry.patchMove.location" to "Frys",
            "inventoryEntry.delete.status" to 204,
            "inventoryEntry.deleted.get.status" to 404,
            "inventoryLog.entry.rowsNewestFirst" to listOf(
                "move:2.0->2.0",
                "move:2.0->2.0",
                "remove:0.0->2.0",
                "remove:1.0->0.0",
                "remove:2.0->1.0",
                "add:0.0->2.0",
            ),
            // a. makenow
            "makenow.0" to listOf("Gröt med blåbär", "Tahinidressing"),
            "makenow.1" to listOf("Gröt med blåbär", "Hummus", "Tahinidressing"),
            "makenow.2" to listOf("Falafel", "Gröt med blåbär", "Hummus", "Tahinidressing"),
            "makenow.true" to listOf("Gröt med blåbär", "Tahinidressing"),
            "makenow.0.afterLemonJuice" to listOf("Gröt med blåbär", "Hummus", "Tahinidressing"),
            "makenow.0.withoutSubstitute" to listOf("Tahinidressing"),
            "makenow.1.withoutSubstitute" to listOf("Gröt med blåbär", "Hummus", "Tahinidressing"),
            "makenow.0.reader" to mapOf("status" to 200, "recipes" to listOf("Gröt med blåbär", "Tahinidressing")),
            "makenow.0.outsider" to mapOf("status" to 200, "recipes" to emptyList<String>()),
            "makenow.0.guest" to mapOf("status" to 200, "recipes" to emptyList<String>()),
            "makenow.2.foodsOrChickpeas" to listOf("Falafel", "Hummus"),
            // Not an integer (or "true"/"yes"): the filter is ignored, every recipe is listed; -1 lists none.
            "makenow.invalid.false" to mapOf("status" to 200, "recipes" to ALL_FOUR),
            "makenow.invalid.1.5" to mapOf("status" to 200, "recipes" to ALL_FOUR),
            "makenow.invalid.-1" to mapOf("status" to 200, "recipes" to emptyList<String>()),
            // c. Keywords and pairings
            "keyword.create.status" to 201,
            "keyword.createDuplicate.sameId" to true,
            "keyword.createWithParent.parentSet" to false,
            "keyword.createOtherCase.sameId" to true,
            "keyword.child.fullName" to "$PAIRS_WITH > $PAIRING_TYPE",
            "keyword.moveEmptyBody.status" to 200,
            "keyword.guest.create.status" to 403,
            "keyword.reader.create.status" to 201,
            "keyword.move.status" to 200,
            "keyword.move.parentSet" to true,
            "pairing.memberPatch.status" to 200,
            "pairing.memberPatch.keywords" to listOf(PAIRING_TYPE),
            "pairing.patchReplaces.keywords" to listOf(PAIRING_TYPE),
            "pairing.guestPatch.status" to 403,
            "pairing.outsiderPatch.status" to 200,
            "pairing.patchIdOnly.status" to 200,
            "pairing.keywordsOrParent.includeChildren=true" to listOf("Falafel", "Hummus"),
            "pairing.keywordsOrParent.includeChildren=false" to listOf("Falafel"),
            "pairing.keywordsOrChild.reader" to listOf("Hummus"),
            // f. The drink
            "drink.createNoSteps.status" to 400,
            "drink.createEmptySteps.status" to 201,
            "drink.createOneEmptyStep.status" to 201,
            "drink.create.internal" to true,
            "drink.create.steps" to 0,
            "drink.imagePost.status" to 405,
            "drink.create.keywords" to listOf(DRINK, "Vin"),
            "drink.guest.create.status" to 403,
            "drink.image.status" to 200,
            "drink.flat.listed" to true,
            "drink.search.names" to listOf(DRINK_NAME),
            "drink.keywordsOr.names" to listOf(DRINK_NAME),
            "drink.makenow0.listed" to true,
            "drink.pairingPatch.status" to 200,
            "drink.pairing.bothSides" to listOf("Hummus", DRINK_NAME),
            // d. FOOD_ALIAS
            "automation.create.status" to 201,
            "automation.createReceipt.status" to 201,
            "automation.reader.params" to listOf(BARCODE, RECEIPT_LINE),
            "automation.guest.list.status" to 403,
            "automation.guest.create.status" to 403,
            "automation.reader.create.status" to 201,
            "automation.param128.status" to 201,
            "automation.param129.status" to 400,
            "automation.name129.status" to 400,
            "automation.createDuplicate.status" to 201,
            "automation.outsider.params" to listOf(BARCODE, RECEIPT_LINE),
            "automation.guest.patch.status" to 403,
            "automation.parser.lower" to "kikärtor",
            "automation.parser.upper" to "kikärtor",
            // A learned barcode is applied by the parser too ("1 <EAN>" -> the food).
            "automation.parser.barcode" to "kikärtor",
            "automation.patch.status" to 200,
            "automation.delete.status" to 204,
            "automation.deleted.get.status" to 404,
        )
    }
}
