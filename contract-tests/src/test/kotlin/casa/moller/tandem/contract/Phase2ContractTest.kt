package casa.moller.tandem.contract

import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
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
 * Establishes and records the server facts Fas 2 ("handla & planera", release 0.3.0) depends on
 * (plan §3.3, §3.5, §3.7, §5.7, §6.2, features #9-#17 and #42): the shopping list (entries,
 * `updated_after`, bulk check, "köp senare", the window rule, deletes), list recipes, stores and
 * aisle order, foods (category, on hand), the ingredient parser, the meal plan (dates, moves,
 * `addshopping` and `mealplan_autoadd_shopping`), the user preferences the app follows, recipe
 * books (owner vs. shared member, the "Vill laga" smart book) and the guest role.
 *
 * Runs after Phase1ContractTest against the same throwaway server. contract.yml first puts the
 * contract user and the other member ([ContractEnv.readerToken]) into one household and adds a
 * guest member ([ContractEnv.guestToken]). Requests claim `Host: tandoor.example.com` as in Fas 1;
 * recordings go to `build/contract-recordings/<tag>/fas2/` with the same date shift (the run day
 * is 2000-01-15) and are committed to `api-schema/fixtures/<version>/fas2/`.
 *
 * Checks are soft as in Fas 1: [observe] records every fact, and the last step compares the facts in
 * `EXPECTED` (what the app relies on) and fails with all mismatches together; tandoor-watch's
 * nightly diff catches changes of the other facts.
 * Every step tolerates the failure of an earlier one (ids may be missing), so one run shows every
 * difference of a new version.
 *
 * Skipped unless TANDOOR_GUEST_TOKEN is set. Never run against a production server.
 *
 * Size and complexity rules are suppressed on purpose: like Phase1ContractTest this is one ordered
 * scenario against one server, the steps share the seeded ids, and a step reads best as the list
 * of requests it makes.
 */
@Suppress("LargeClass", "LongMethod", "CyclomaticComplexMethod")
@EnabledIfEnvironmentVariable(named = "TANDOOR_GUEST_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase2ContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val http = TandoorHttp(env.baseUrl, sanitizer, env.fas2RecordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private var ownerId: Long? = null
    private var readerId: Long? = null
    private var preferences: JsonObject? = null
    private var recipeId: Long? = null
    private val ingredientIds = LinkedHashMap<String, Long>()
    private val foodIds = LinkedHashMap<String, Long>()
    private val unitIds = LinkedHashMap<String, Long>()
    private var shoppingListId: Long? = null
    private val categoryIds = LinkedHashMap<String, Long>()

    /** Dinner (from Fas 1) and breakfast (created here), as `{id, name}`. */
    private var mealTypeRefs: Pair<JsonObject, JsonObject>? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        assertTrue(env.readerToken.isNotBlank(), "TANDOOR_READER_TOKEN must be set")
        assertTrue(env.guestToken.isNotBlank(), "TANDOOR_GUEST_TOKEN must be set")
        // Same fake numbering as Fas 1: ...0001 the contract user, ...0002 the other member; ...0003 the guest.
        sanitizer.fakeTokenFor(env.fas1Token)
        sanitizer.fakeTokenFor(env.readerToken)
        sanitizer.fakeTokenFor(env.guestToken)
        env.fas2RecordDir.deleteRecursively()
        env.fas2RecordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun writeFacts() {
        facts["mismatches"] = mismatches
        facts.write(File(env.fas2RecordDir, "observed-facts.json"), sanitizer)
    }

    // --- a. Who is who: preferences, server settings, groups, household ------------------------

    @Test
    @Order(10)
    fun preferencesAndRoles() {
        val settings = http.call("server-settings", "GET", "/api/server-settings/current/")
        val interval = objOf(settings)?.get("shopping_min_autosync_interval")
        observe("serverSettings.shoppingMinAutosyncInterval", interval)
        observe("serverSettings.shoppingMinAutosyncInterval.type", interval?.let { Facts.typeOf(it) })

        val prefs = api("user-preference", "GET", "/api/user-preference/")
        preferences = (prefs.json as? JsonArray)?.firstOrNull() as? JsonObject
        ownerId = preferences?.objAt("user")?.longAt("id")
        preferences?.let { observe("userPreference.fieldTypes", Facts.fieldTypes(it)) }
        for (key in PREFERENCE_KEYS) observe("userPreference.$key", preferences?.get(key) ?: "<absent>")

        val readerPrefs = api("user-preference-reader", "GET", "/api/user-preference/", token = env.readerToken, record = false)
        readerId = ((readerPrefs.json as? JsonArray)?.firstOrNull() as? JsonObject)?.objAt("user")?.longAt("id")

        val owner = ownerId
        if (owner != null) {
            val patch = api("user-preference-patch", "PATCH", "/api/user-preference/$owner/", obj("shopping_auto_sync" to 10))
            observe("userPreference.patch.status", patch.status)
            observe("userPreference.patch.shoppingAutoSync", objOf(patch)?.get("shopping_auto_sync"))
            readerId?.let { other ->
                val foreign = api(
                    "user-preference-patch-other-user",
                    "PATCH",
                    "/api/user-preference/$other/",
                    obj("shopping_auto_sync" to 10),
                )
                observe("userPreference.patchOtherUser", describe(foreign))
            }
            val original = preferences?.get("shopping_auto_sync") ?: JsonPrimitive(DEFAULT_AUTO_SYNC)
            api("user-preference-restore", "PATCH", "/api/user-preference/$owner/", obj("shopping_auto_sync" to original), record = false)
        }

        for ((who, token) in listOf("owner" to env.fas1Token, "reader" to env.readerToken, "guest" to env.guestToken)) {
            val space = api("user-space-$who", "GET", "/api/user-space/all_personal/", token = token, record = who != "reader")
            val first = (space.json as? JsonArray)?.firstOrNull() as? JsonObject
            observe("userSpace.$who.status", space.status)
            observe("userSpace.$who.groups", first?.arrAt("groups")?.objects()?.map { it.stringAt("name") })
            observe("userSpace.$who.household.type", first?.get("household")?.let { Facts.typeOf(it) })
            observe("userSpace.$who.household.keys", first?.objAt("household")?.keys?.sorted())
            val household = api("household-list-$who", "GET", "/api/household/", token = token, record = who == "owner")
            observe("household.list.$who", describe(household, includeBody = false))
            observe("household.list.$who.names", pageOrArray(household.json).map { it.stringAt("name") })
        }
        val spaceAsGuest = api("space-current-guest", "GET", "/api/space/current/", token = env.guestToken, record = false)
        observe("space.current.guest.status", spaceAsGuest.status)
    }

    // --- b. Seeding: a recipe with ingredients, a named shopping list --------------------------

    @Test
    @Order(20)
    fun seedRecipeAndList() {
        val step = obj(
            "name" to "",
            "instruction" to "Skiva potatisen, varva med grädde och mjölk och grädda.",
            "time" to 60,
            "order" to 0,
            "show_as_header" to false,
            "show_ingredients_table" to true,
            "ingredients" to RECIPE_INGREDIENTS.mapIndexed { index, (amount, unit, food) -> ingredient(index, amount, unit, food) },
        )
        val body = obj(
            "name" to RECIPE,
            "description" to "",
            "servings" to 4,
            "servings_text" to "portioner",
            "working_time" to 20,
            "waiting_time" to 60,
            "internal" to true,
            "keywords" to emptyList<Any>(),
            "steps" to listOf(step),
        )
        val created = api("seed-recipe-create", "POST", "/api/recipe/", body, record = false)
        assertEquals(201, created.status, created.toString())
        recipeId = idOf(created)
        val ingredients = objOf(created)?.arrAt("steps").objects().flatMap { it.arrAt("ingredients").objects() }
        for (ingredient in ingredients) {
            val food = ingredient.objAt("food")
            val name = food?.stringAt("name") ?: continue
            ingredient.longAt("id")?.let { ingredientIds[name] = it }
            food?.longAt("id")?.let { foodIds[name] = it }
            ingredient.objAt("unit")?.let { unit -> unit.longAt("id")?.let { unitIds[unit.stringAt("name").orEmpty()] = it } }
        }

        val list = api(
            "shopping-list-create",
            "POST",
            "/api/shopping-list/",
            obj("name" to "Veckohandling", "description" to "", "color" to "#2E7D32"),
        )
        observe("shoppingList.create", describe(list, includeBody = false))
        objOf(list)?.let { observe("shoppingList.fieldTypes", Facts.fieldTypes(it)) }
        shoppingListId = idOf(list)
        val lists = api("shopping-list-list", "GET", "/api/shopping-list/")
        observe("shoppingList.list.keys", objOf(lists)?.keys?.toList() ?: lists.json?.let { Facts.typeOf(it) })
    }

    // --- c. Stores, categories and the aisle order ----------------------------------------------

    @Test
    @Order(30)
    fun supermarketsAndCategories() {
        for ((index, name) in CATEGORIES.withIndex()) {
            val created = api(
                "supermarket-category-create",
                "POST",
                "/api/supermarket-category/",
                obj("name" to name, "description" to ""),
                record = index == 0,
            )
            if (index == 0) observe("supermarketCategory.create", describe(created, includeBody = false))
            idOf(created)?.let { categoryIds[name] = it }
        }
        // SupermarketSerializer.create passes every field to get_or_create, so `shopping_lists` (a
        // many-to-many field) in the body makes it fail [K]: create the store without it.
        val withLists = obj("name" to "$STORE (listor)", "description" to "", "shopping_lists" to emptyList<Any>())
        observe(
            "supermarket.createWithShoppingLists.status",
            api("store-lists", "POST", "/api/supermarket/", withLists, record = false).status,
        )
        val store = api("supermarket-create", "POST", "/api/supermarket/", obj("name" to STORE, "description" to ""))
        observe("supermarket.create", describe(store, includeBody = false))
        objOf(store)?.let { observe("supermarket.fieldTypes", Facts.fieldTypes(it)) }
        val storeId = idOf(store)
        if (storeId != null) {
            // Created in reverse aisle order: is the store's list sorted by `order` or by creation?
            val relationIds = mutableListOf<Long>()
            for ((index, name) in CATEGORIES.withIndex().reversed()) {
                val category = categoryIds[name] ?: continue
                val body = obj("supermarket" to storeId, "category" to obj("id" to category, "name" to name), "order" to index)
                val relation = api(
                    "supermarket-category-relation-create",
                    "POST",
                    "/api/supermarket-category-relation/",
                    body,
                    record = index == 0,
                )
                if (index == 0) observe("supermarketCategoryRelation.create", describe(relation))
                idOf(relation)?.let { relationIds += it }
            }
            relationIds.firstOrNull()?.let { relation ->
                val path = "/api/supermarket-category-relation/$relation/"
                val patch = api("supermarket-category-relation-patch", "PATCH", path, obj("order" to 10))
                observe("supermarketCategoryRelation.patch", describe(patch, includeBody = false))
                observe("supermarketCategoryRelation.patch.order", objOf(patch)?.get("order"))
            }
            val stores = api("supermarket-list", "GET", "/api/supermarket/")
            val ours = pageOrArray(stores.json).firstOrNull { it.longAt("id") == storeId }
            val aisles = ours?.arrAt("category_to_supermarket").objects()
            val order = aisles.map { "${it.objAt("category")?.stringAt("name")}=${it.longAt("order")}" }
            observe("supermarket.list.categoryOrder", order)
            observe("supermarket.list.keys", objOf(stores)?.keys?.toList())
            api("supermarket-category-list", "GET", "/api/supermarket-category/")
            val relations = api("supermarket-category-relation-list", "GET", "/api/supermarket-category-relation/?page_size=100")
            observe("supermarketCategoryRelation.list", describe(relations, includeBody = false))
        }

        // Foods: the category comes from the food (plan 6.2), and "on hand".
        val cream = foodIds["grädde"]
        val dairy = categoryIds["Mejeri"]
        if (cream != null && dairy != null) {
            val body = obj("supermarket_category" to obj("id" to dairy, "name" to "Mejeri"))
            val patch = api("food-patch-category", "PATCH", "/api/food/$cream/", body)
            observe("food.patchCategory", describe(patch, includeBody = false))
            observe("food.patchCategory.value", objOf(patch)?.get("supermarket_category"))
        }
        val butter = foodIds["smör"]
        if (butter != null) {
            val onHand = api("food-patch-onhand", "PATCH", "/api/food/$butter/", obj("food_onhand" to true))
            observe("food.patchOnHand", describe(onHand, includeBody = false))
            observe("food.patchOnHand.value", objOf(onHand)?.get("food_onhand"))
            val get = api("food-get", "GET", "/api/food/$butter/")
            objOf(get)?.let { observe("food.fieldTypes", Facts.fieldTypes(it)) }
            observe("food.get.foodOnhand", objOf(get)?.get("food_onhand"))
            // As another member of the household: is "on hand" per household?
            val asReader = api("food-get-as-reader", "GET", "/api/food/$butter/", token = env.readerToken, record = false)
            observe("food.get.foodOnhand.reader", objOf(asReader)?.get("food_onhand"))
            api("food-patch-onhand-off", "PATCH", "/api/food/$butter/", obj("food_onhand" to false), record = false)
        }
    }

    // --- d. Shopping list entries ---------------------------------------------------------------

    @Test
    @Order(40)
    fun shoppingEntries() {
        val empty = api("shopping-list-entry-list", "GET", "/api/shopping-list-entry/?page_size=200")
        observe("shopping.list.status", empty.status)
        observe("shopping.list.keys", objOf(empty)?.keys?.toList())
        observe("shopping.list.timestamp.format", objOf(empty)?.stringAt("timestamp")?.let { shape(it) })
        val t0 = objOf(empty)?.stringAt("timestamp")

        val milk = create("shopping-list-entry-create", obj("food" to obj("name" to "mjölk"), "unit" to obj("name" to "l"), "amount" to 2))
        observe("shopping.create.status", milk.status)
        objOf(milk)?.let { entry ->
            observe("shopping.entry.fieldTypes", Facts.fieldTypes(entry))
            observe("shopping.entry.food.keys", entry.objAt("food")?.keys?.sorted())
            observe("shopping.entry.unit.keys", entry.objAt("unit")?.keys?.sorted())
            observe("shopping.entry.createdBy.keys", entry.objAt("created_by")?.keys?.sorted())
            observe("shopping.entry.listRecipeData", entry["list_recipe_data"])
            observe("shopping.entry.order", entry["order"])
            observe("shopping.entry.amount", entry["amount"])
            observe("shopping.entry.updatedAt.format", entry.stringAt("updated_at")?.let { shape(it) })
            observe("shopping.entry.delayUntil", entry["delay_until"])
            observe("shopping.entry.completedAt", entry["completed_at"])
        }
        val milkId = idOf(milk)

        val creamFood = foodIds["grädde"]
        val creamRef = if (creamFood != null) obj("id" to creamFood, "name" to "grädde") else obj("name" to "grädde")
        val cream = create("shopping-list-entry-create-existing-food", obj("food" to creamRef, "amount" to 1))
        observe("shopping.createExistingFood.status", cream.status)
        observe("shopping.createExistingFood.sameFoodId", objOf(cream)?.objAt("food")?.longAt("id")?.let { it == creamFood })
        observe("shopping.entry.food.supermarketCategory", objOf(cream)?.objAt("food")?.get("supermarket_category"))
        val creamId = idOf(cream)

        val toothpaste = create(
            "shopping-list-entry-create-free-text",
            obj("food" to obj("name" to "tandkräm"), "unit" to null, "amount" to 1),
        )
        observe("shopping.createFreeText.status", toothpaste.status)
        observe("shopping.createFreeText.unit", objOf(toothpaste)?.get("unit"))
        val toothpasteId = idOf(toothpaste)

        observe("shopping.createNoFood", describe(create("shopping-list-entry-create-no-food", obj("food" to null, "amount" to 1))))
        val noAmount = create("shopping-list-entry-create-no-amount", obj("food" to obj("name" to "bröd")))
        observe("shopping.createNoAmount", describe(noAmount))
        idOf(noAmount)?.let { api("cleanup", "DELETE", "/api/shopping-list-entry/$it/", record = false) }
        val zero = create("shopping-list-entry-create-zero-amount", obj("food" to obj("name" to "salt"), "amount" to 0), record = false)
        observe("shopping.createZeroAmount.status", zero.status)

        val listId = shoppingListId
        val lists = listOfNotNull(listId).map { obj("id" to it) }
        val eggsBody = obj("food" to obj("name" to "ägg"), "unit" to obj("name" to "st"), "amount" to 6, "shopping_lists" to lists)
        val inList = create("shopping-list-entry-create-in-list", eggsBody)
        observe("shopping.createInList.status", inList.status)
        observe("shopping.createInList.lists", objOf(inList)?.arrAt("shopping_lists").objects().map { it.stringAt("name") })
        val eggsId = idOf(inList)

        if (t0 != null) {
            val after = listAfter("shopping-list-entry-list-updated-after", t0, record = true)
            observe(
                "shopping.updatedAfter.t0.includesCreated",
                idsOf(after).containsAll(listOfNotNull(milkId, creamId, toothpasteId, eggsId)),
            )
            // The timestamp carries "+01:00"/"+02:00": an unencoded "+" becomes a space in the query.
            val raw = api("raw-plus", "GET", "/api/shopping-list-entry/?updated_after=$t0", record = false)
            observe("shopping.updatedAfter.rawPlus", describe(raw, includeBody = raw.status != 200))
            observe("shopping.updatedAfter.rawPlus.count", objOf(raw)?.longAt("count"))
            observe("shopping.updatedAfter.rawPlus.returnsEverything", objOf(raw)?.longAt("count") == objOf(fullList())?.longAt("count"))
            val dateOnly = api("date-only", "GET", "/api/shopping-list-entry/?updated_after=$today", record = false)
            observe("shopping.updatedAfter.dateOnly", describe(dateOnly, includeBody = dateOnly.status != 200))
            val utc = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(30).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_INSTANT)
            val zulu = api("zulu", "GET", "/api/shopping-list-entry/?updated_after=${enc(utc)}", record = false)
            observe("shopping.updatedAfter.zulu.status", zulu.status)
            observe("shopping.updatedAfter.zulu.count", objOf(zulu)?.longAt("count"))
        }

        // Check one with PATCH: completed_at, and is updated_at bumped (seen by updated_after)?
        if (milkId != null) {
            val t = timestampNow()
            val checked = patchEntry("shopping-list-entry-patch-checked", milkId, obj("checked" to true))
            observe("shopping.patchChecked.status", checked.status)
            observe("shopping.patchChecked.completedAt.format", objOf(checked)?.stringAt("completed_at")?.let { shape(it) })
            observe("shopping.patchChecked.seenByUpdatedAfter", t?.let { milkId in idsOf(listAfter("patch-checked", it)) })
            observe("shopping.list.includesCheckedRecent", milkId in idsOf(fullList()))
        }

        // Bulk check (the web's offline queue) and uncheck.
        val bulkIds = listOfNotNull(creamId, toothpasteId)
        if (bulkIds.isNotEmpty()) {
            var t = timestampNow()
            val checkAll = bulk("shopping-list-entry-bulk-check", obj("ids" to bulkIds, "checked" to true))
            observe("shopping.bulkCheck", describe(checkAll))
            observe("shopping.bulkCheck.seenByUpdatedAfter", t?.let { idsOf(listAfter("bulk-check", it)).containsAll(bulkIds) })
            val afterCheck = toothpasteId?.let { getEntry("shopping-list-entry-get-checked", it, record = false) }
            observe("shopping.bulkCheck.completedAtSet", afterCheck?.let { objOf(it)?.stringAt("completed_at") != null })
            t = timestampNow()
            val uncheck = bulk("shopping-list-entry-bulk-uncheck", obj("ids" to listOfNotNull(toothpasteId), "checked" to false))
            observe("shopping.bulkUncheck.status", uncheck.status)
            observe("shopping.bulkUncheck.seenByUpdatedAfter", t?.let { toothpasteId in idsOf(listAfter("bulk-uncheck", it)) })
            val got = toothpasteId?.let { getEntry("shopping-list-entry-get", it) }
            observe("shopping.bulkUncheck.completedAt", got?.let { objOf(it)?.get("completed_at") })
            observe("shopping.bulkUncheck.checked", got?.let { objOf(it)?.get("checked") })
        }

        // Moving between lists (plan 3.5): does bulk shopping_lists_add or a PATCH bump updated_at?
        if (listId != null && creamId != null) {
            val t = timestampNow()
            val add = bulk("shopping-list-entry-bulk-lists-add", obj("ids" to listOf(creamId), "shopping_lists_add" to listOf(listId)))
            observe("shopping.bulkListsAdd.status", add.status)
            observe("shopping.bulkListsAdd.seenByUpdatedAfter", t?.let { creamId in idsOf(listAfter("bulk-lists-add", it)) })
        }
        if (listId != null && toothpasteId != null) {
            val t = timestampNow()
            val patch = patchEntry("shopping-list-entry-patch-lists", toothpasteId, obj("shopping_lists" to listOf(obj("id" to listId))))
            observe("shopping.patchLists.status", patch.status)
            observe("shopping.patchLists.lists", objOf(patch)?.arrAt("shopping_lists").objects().map { it.stringAt("name") })
            observe("shopping.patchLists.seenByUpdatedAfter", t?.let { toothpasteId in idsOf(listAfter("patch-lists", it)) })
        }

        // "Köp senare" (#14): delay_until = now + default_delay hours, undone with 1970-01-01 exactly as
        // the app sends it (ShoppingPayloads.NOT_DELAYED), so the gate covers the app's own write.
        if (eggsId != null) {
            val later = isoOffset(ZonedDateTime.now(zone).plusHours(4))
            val delay = patchEntry("shopping-list-entry-patch-delay", eggsId, obj("delay_until" to later))
            observe("shopping.delay.status", delay.status)
            observe("shopping.delay.value.format", objOf(delay)?.stringAt("delay_until")?.let { shape(it) })
            observe("shopping.delay.listed", eggsId in idsOf(fullList()))
            val undo = patchEntry("shopping-list-entry-patch-undelay", eggsId, obj("delay_until" to "1970-01-01T00:00:00Z"))
            observe("shopping.undelay.status", undo.status)
            observe("shopping.undelay.value", objOf(undo)?.get("delay_until"))
            val undone = getEntry("shopping-list-entry-get-undelayed", eggsId, record = false)
            observe("shopping.undelay.notDelayed", objOf(undone)?.stringAt("delay_until")?.let { isPast(it) })
            observe("shopping.undelay.listed", eggsId in idsOf(fullList()))
            val clear = patchEntry("shopping-list-entry-patch-delay-null", eggsId, obj("delay_until" to null), record = false)
            observe("shopping.delayNull.status", clear.status)
            observe("shopping.delayNull.value", objOf(clear)?.get("delay_until"))
        }

        // The window rule (plan 3.5): the list leaves out checked entries completed before
        // midnight (UTC) min(shopping_recent_days, 14) days ago. completed_at cannot be set through
        // the API (the serializer sets it), so contract.yml created three checked entries completed
        // 30, 10 and 3 days ago.
        val archived = env.archivedEntryIds
        val owner = ownerId
        if (archived.size == ARCHIVED_ENTRIES && owner != null) {
            observe("shopping.window.recentDays7.listed30d10d3d", archived.map { it in idsOf(fullList()) })
            val wide = api("recent-days-20", "PATCH", "/api/user-preference/$owner/", obj("shopping_recent_days" to 20), record = false)
            observe("shopping.window.patchRecentDays.status", wide.status)
            observe("shopping.window.recentDays20.listed30d10d3d", archived.map { it in idsOf(fullList()) })
            api("recent-days-7", "PATCH", "/api/user-preference/$owner/", obj("shopping_recent_days" to 7), record = false)
            observe("shopping.window.agedOut.detailStatus", getEntry("shopping-list-entry-get-aged-out", archived.first()).status)
            val since = timestampNow()
            observe("shopping.window.agedOut.updatedAfterNow", since?.let { archived.first() in idsOf(listAfter("aged-out", it)) })
        } else {
            observe("shopping.window.skipped", "archived entries: $archived")
        }

        // Deleted rows: what the outbox meets when someone else deleted an entry (plan 3.5, 5.4).
        if (milkId != null) {
            observe("shopping.delete.status", api("shopping-list-entry-delete", "DELETE", "/api/shopping-list-entry/$milkId/").status)
            observe("shopping.deleted.get", describe(getEntry("shopping-list-entry-deleted-get", milkId)))
            observe("shopping.deleted.patch", describe(patchEntry("shopping-list-entry-deleted-patch", milkId, obj("checked" to false))))
            observe(
                "shopping.deleted.delete",
                describe(api("shopping-list-entry-deleted-delete", "DELETE", "/api/shopping-list-entry/$milkId/")),
            )
            observe(
                "shopping.deleted.bulk",
                describe(bulk("shopping-list-entry-bulk-deleted-id", obj("ids" to listOf(milkId), "checked" to true))),
            )
            if (eggsId != null) {
                val mixed = bulk("bulk-mixed", obj("ids" to listOf(milkId, eggsId), "checked" to true), record = false)
                observe("shopping.deleted.bulkMixed.status", mixed.status)
                observe("shopping.deleted.bulkMixed.otherChecked", objOf(getEntry("eggs", eggsId, record = false))?.get("checked"))
            }
        }

        // The household: the other member sees the contract user's entries and the other way round.
        val asReader = api("as-member", "GET", "/api/shopping-list-entry/?page_size=200", token = env.readerToken, record = false)
        observe("shopping.householdMember.status", asReader.status)
        observe("shopping.householdMember.seesOwnersEntries", idsOf(asReader).containsAll(listOfNotNull(creamId, toothpasteId)))
        val memberEntry = create(
            "member-create",
            obj("food" to obj("name" to "bröd"), "amount" to 1),
            token = env.readerToken,
            record = false,
        )
        observe("shopping.householdMember.create.status", memberEntry.status)
        val memberEntryId = idOf(memberEntry)
        val ownerView = fullList()
        observe("shopping.householdMember.ownerSeesMembersEntry", memberEntryId in idsOf(ownerView))
        val createdBy = pageOrArray(ownerView.json).firstOrNull { it.longAt("id") == memberEntryId }?.objAt("created_by")?.longAt("id")
        observe("shopping.householdMember.createdByMember", createdBy?.let { it == readerId })

        // The guest (plan #42).
        val guestList = api("shopping-list-entry-list-as-guest", "GET", "/api/shopping-list-entry/?page_size=200", token = env.guestToken)
        observe("shopping.guest.list", describe(guestList, includeBody = guestList.status != 200))
        val guestCreate = create(
            "shopping-list-entry-create-as-guest",
            obj("food" to obj("name" to "godis"), "amount" to 1),
            token = env.guestToken,
        )
        observe("shopping.guest.create", describe(guestCreate))
        creamId?.let { id ->
            val guestCheck = bulk("guest-bulk", obj("ids" to listOf(id), "checked" to false), token = env.guestToken, record = false)
            observe("shopping.guest.bulk", describe(guestCheck))
        }
        observe("shopping.final.count", objOf(fullList())?.longAt("count"))
    }

    // --- e. Recipe -> shopping list, list recipes ----------------------------------------------

    @Test
    @Order(50)
    fun listRecipes() {
        val recipe = recipeId ?: return observe("listRecipe.skipped", "no recipe")
        val picked = listOfNotNull(ingredientIds["grädde"], ingredientIds["potatis"])
        val put = api("recipe-shopping-put", "PUT", "/api/recipe/$recipe/shopping/", obj("ingredients" to picked, "servings" to 8))
        observe("recipeShopping.put", describe(put))
        val fromRecipe = pageOrArray(fullList().json).filter { it.objAt("list_recipe_data")?.longAt("recipe") == recipe }
        observe("recipeShopping.entries.foods", amounts(fromRecipe))
        observe("recipeShopping.entries.ingredientSet", fromRecipe.isNotEmpty() && fromRecipe.all { it.longAt("ingredient") != null })
        val listRecipeId = fromRecipe.firstOrNull()?.longAt("list_recipe")
        fromRecipe.firstOrNull()?.objAt("list_recipe_data")?.let { data ->
            observe("listRecipe.data.keys", data.keys.sorted())
            observe("listRecipe.data.servings", data["servings"])
            observe("listRecipe.data.mealPlanData", data["meal_plan_data"]?.let { Facts.typeOf(it) })
        }
        val lr = api("shopping-list-recipe-list", "GET", "/api/shopping-list-recipe/")
        observe("listRecipe.list.keys", objOf(lr)?.keys?.toList() ?: lr.json?.let { Facts.typeOf(it) })

        if (listRecipeId != null) {
            // The same list recipe with other servings: are the entries rescaled in place?
            val body = obj("ingredients" to picked, "servings" to 4, "list_recipe" to listRecipeId)
            val again = api("recipe-shopping-put-servings", "PUT", "/api/recipe/$recipe/shopping/", body, record = false)
            observe("recipeShopping.putServings.status", again.status)
            observe("recipeShopping.putServings.foods", amounts(entriesOf(listRecipeId)))

            val patch = api(
                "shopping-list-recipe-patch-servings",
                "PATCH",
                "/api/shopping-list-recipe/$listRecipeId/",
                obj("servings" to 2),
            )
            observe("listRecipe.patchServings.status", patch.status)
            val patched = entriesOf(listRecipeId)
            observe("listRecipe.patchServings.foods", amounts(patched))

            val entryIds = patched.mapNotNull { it.longAt("id") }
            observe(
                "listRecipe.delete.status",
                api("shopping-list-recipe-delete", "DELETE", "/api/shopping-list-recipe/$listRecipeId/").status,
            )
            val remaining = idsOf(fullList())
            observe("listRecipe.delete.entriesRemain", entryIds.count { it in remaining })
            entryIds.firstOrNull()?.let { id ->
                observe("listRecipe.delete.entryGet", describe(getEntry("shopping-list-entry-of-deleted-list-recipe", id)))
            }
            val deletedGet = api("shopping-list-recipe-deleted-get", "GET", "/api/shopping-list-recipe/$listRecipeId/")
            observe("listRecipe.deleted.get", describe(deletedGet))
        }

        // A list recipe created by hand + bulk_create_entries (the "Handla för veckan" route without a meal plan).
        val created = api(
            "shopping-list-recipe-create",
            "POST",
            "/api/shopping-list-recipe/",
            obj("recipe" to recipe, "servings" to 2, "name" to ""),
        )
        observe("listRecipe.create", describe(created))
        val createdId = idOf(created)
        val food = foodIds["mjölk"]
        if (createdId != null && food != null) {
            val entry = obj("amount" to 1.5, "unit_id" to unitIds["dl"], "food_id" to food, "ingredient_id" to ingredientIds["mjölk"])
            val body = obj("entries" to listOf(entry), "shopping_lists_ids" to listOfNotNull(shoppingListId))
            val path = "/api/shopping-list-recipe/$createdId/bulk_create_entries/"
            val bulkCreate = api("shopping-list-recipe-bulk-create-entries", "POST", path, body)
            observe("listRecipe.bulkCreateEntries", describe(bulkCreate))
            val entries = entriesOf(createdId)
            observe("listRecipe.bulkCreateEntries.created", amounts(entries))
            observe(
                "listRecipe.bulkCreateEntries.lists",
                entries.map { e -> e.arrAt("shopping_lists").objects().map { it.stringAt("name") } },
            )
            // "servings 0 + list_recipe deletes" (the schema's help text) fails: the view tests
            // `if servings and servings <= 0`, so 0 goes on to edit [K]; a negative number deletes.
            val zeroBody = obj("ingredients" to emptyList<Any>(), "servings" to 0, "list_recipe" to createdId)
            val zero = api("recipe-shopping-put-servings-0", "PUT", "/api/recipe/$recipe/shopping/", zeroBody)
            observe("recipeShopping.putServings0", describe(zero, includeBody = false))
            val removeBody = obj("ingredients" to emptyList<Any>(), "servings" to -1, "list_recipe" to createdId)
            val remove = api("recipe-shopping-put-delete", "PUT", "/api/recipe/$recipe/shopping/", removeBody)
            observe("recipeShopping.putNegativeServings", describe(remove))
            val remaining = idsOf(fullList())
            observe("recipeShopping.putNegativeServings.entriesRemain", entries.mapNotNull { it.longAt("id") }.count { it in remaining })
            val gone = api("list-recipe-after-delete", "GET", "/api/shopping-list-recipe/$createdId/", record = false)
            observe("recipeShopping.putNegativeServings.listRecipeStatus", gone.status)
        }
    }

    // --- f. Free text -> amount/unit/food (#11) --------------------------------------------------

    @Test
    @Order(60)
    fun ingredientParser() {
        val single = api("ingredient-parser-single", "POST", "/api/ingredient-parser/post/", obj("ingredient" to "2 dl grädde"))
        observe("parser.single", describe(single, includeBody = single.status != 200))
        observe("parser.single.ingredient", objOf(single)?.get("ingredient"))
        val many = api("ingredient-parser-many", "POST", "/api/ingredient-parser/post/", obj("ingredients" to PARSER_SAMPLES))
        observe("parser.many.status", many.status)
        val parsed = objOf(many)?.arrAt("ingredients").objects()
        observe("parser.many.count", parsed.size)
        parsed.firstOrNull()?.let { observe("parser.result.keys", it.keys.sorted()) }
        for ((text, result) in PARSER_SAMPLES.zip(parsed)) {
            val summary = mapOf(
                "amount" to result["amount"],
                "unit" to result.objAt("unit")?.stringAt("name"),
                "food" to result.objAt("food")?.stringAt("name"),
                "note" to result["note"],
            )
            observe("parser.sample.$text", summary)
            // The parser returns the food with an id: it created (or found) it on the server.
            observe("parser.sample.$text.foodHasId", result.objAt("food")?.longAt("id") != null)
        }
        val guest = api(
            "parser-guest",
            "POST",
            "/api/ingredient-parser/post/",
            obj("ingredient" to "1 l mjölk"),
            token = env.guestToken,
            record = false,
        )
        observe("parser.guest.status", guest.status)
    }

    // --- g. Meal plan (#15, #16) -----------------------------------------------------------------

    @Test
    @Order(70)
    fun mealPlanDates() {
        val types = pageOrArray(api("meal-type-list", "GET", "/api/meal-type/", record = false).json)
        val dinner = types.firstOrNull { it.stringAt("time")?.startsWith("18") == true } ?: types.firstOrNull()
        val breakfast = api("meal-type-create", "POST", "/api/meal-type/", obj("name" to "Frukost", "time" to "07:30", "order" to 2))
        observe("mealType.create", describe(breakfast))
        val breakfastRef = objOf(breakfast)?.let { ref(it) }
        val dinnerRef = dinner?.let { ref(it) }
        if (dinnerRef == null || breakfastRef == null) return observe("mealPlan.skipped", "no meal types")
        mealTypeRefs = dinnerRef to breakfastRef

        val day3 = today.plusDays(3)
        val dateOnly = api(
            "meal-plan-create-date-only",
            "POST",
            "/api/meal-plan/",
            mealPlan("Datum utan tid", null, 2, day3.toString(), dinnerRef),
        )
        observe("mealPlan.dateOnly", describe(dateOnly, includeBody = dateOnly.status != CREATED))
        observe("mealPlan.dateOnly.dates", datesOf(dateOnly))

        // 23:30 UTC is 00:30 or 01:30 the next day in the server's zone: which day is it listed on?
        val late = api(
            "meal-plan-create-utc-late",
            "POST",
            "/api/meal-plan/",
            mealPlan("Sent UTC", null, 1, "${day3}T23:30:00Z", breakfastRef),
        )
        observe("mealPlan.lateUtc.dates", datesOf(late))
        val lateId = idOf(late)
        observe("mealPlan.lateUtc.listedOnDay3", lateId in mealPlanIdsOn(day3))
        observe("mealPlan.lateUtc.listedOnDay4", lateId in mealPlanIdsOn(day3.plusDays(1)))

        // Two days: listed by to_date only?
        val day5 = today.plusDays(5)
        val twoDaysBody = JsonObject(
            mealPlan("Två dagar", null, 4, "${day5}T00:00:00", dinnerRef) + ("to_date" to JsonPrimitive("${day5.plusDays(1)}T00:00:00")),
        )
        val twoDays = api("meal-plan-create-two-days", "POST", "/api/meal-plan/", twoDaysBody)
        observe("mealPlan.twoDays.dates", datesOf(twoDays))
        val twoDaysId = idOf(twoDays)
        observe("mealPlan.twoDays.listedOnFirstDay", twoDaysId in mealPlanIdsOn(day5))
        observe("mealPlan.twoDays.listedOnSecondDay", twoDaysId in mealPlanIdsOn(day5.plusDays(1)))

        // Move (#15): PATCH from_date only, then both; other fields.
        val moved = idOf(dateOnly)
        if (moved != null) {
            val newFrom = ZonedDateTime.of(today.plusDays(4), LocalTime.of(18, 0), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val move = api("meal-plan-patch-move", "PATCH", "/api/meal-plan/$moved/", obj("from_date" to newFrom))
            observe("mealPlan.moveFromOnly", describe(move, includeBody = move.status != 200))
            observe("mealPlan.moveFromOnly.dates", datesOf(move))
            // The app's own move (MealPlanDates.toServer): both dates, NAIVE server-local date-times.
            val target = today.plusDays(4)
            val naive = "${target}T18:00:00"
            val both = api(
                "meal-plan-patch-move-both",
                "PATCH",
                "/api/meal-plan/$moved/",
                obj("from_date" to naive, "to_date" to naive),
                record = false,
            )
            observe("mealPlan.moveBoth", describe(both, includeBody = both.status != 200))
            observe("mealPlan.moveBoth.dates", datesOf(both))
            observe("mealPlan.moveBoth.listedOnTargetDay", moved in mealPlanIdsOn(target))
            observe("mealPlan.moveBoth.listedOnOldDay", moved in mealPlanIdsOn(day3))
            val fieldsBody = obj("title" to "Ny titel", "servings" to 3, "meal_type" to breakfastRef)
            val fields = api("meal-plan-patch-fields", "PATCH", "/api/meal-plan/$moved/", fieldsBody)
            observe("mealPlan.patchFields.status", fields.status)
            observe("mealPlan.patchFields.mealType", objOf(fields)?.objAt("meal_type")?.stringAt("name"))
            observe("mealPlan.patchFields.servings", objOf(fields)?.get("servings"))
            val get = api("meal-plan-get", "GET", "/api/meal-plan/$moved/")
            objOf(get)?.let { observe("mealPlan.fieldTypes", Facts.fieldTypes(it)) }
        }

        // The household member sees the plan; the guest reads but cannot write.
        val window = "from_date=$today&to_date=${today.plusDays(14)}"
        val ownerCount = objOf(api("mp-owner", "GET", "/api/meal-plan/?$window", record = false))?.longAt("count")
        val memberList = api("mp-member", "GET", "/api/meal-plan/?$window", token = env.readerToken, record = false)
        val memberCount = objOf(memberList)?.longAt("count")
        observe("mealPlan.householdMember.seesSame", ownerCount != null && ownerCount == memberCount)
        val guestList = api("mp-guest", "GET", "/api/meal-plan/?$window", token = env.guestToken, record = false)
        observe("mealPlan.guest.list.status", guestList.status)
        observe("mealPlan.guest.list.count", objOf(guestList)?.longAt("count"))
        val guestBody = mealPlan("Gäst", null, 1, "${today}T00:00:00", dinnerRef)
        observe(
            "mealPlan.guest.create",
            describe(api("meal-plan-create-as-guest", "POST", "/api/meal-plan/", guestBody, token = env.guestToken)),
        )
    }

    @Test
    @Order(71)
    fun mealPlanShopping() {
        val dinnerRef = mealTypeRefs?.first ?: return observe("mealPlanShopping.skipped", "no meal types")
        val recipe = recipeId ?: return observe("mealPlanShopping.skipped", "no recipe")
        val recipeRef = obj("id" to recipe, "name" to RECIPE)

        // "Handla för veckan" (#16): addshopping with mealplan_autoadd_shopping off and on.
        val off = api("meal-plan-create-addshopping", "POST", "/api/meal-plan/", withAddShopping(mealPlanOn(recipeRef, 7, dinnerRef), true))
        observe("mealPlan.addShopping.status", off.status)
        observe("mealPlan.addShopping.shoppingFlag", objOf(off)?.get("shopping"))
        idOf(off)?.let { id ->
            val entries = pageOrArray(api("shopping-list-entry-list-mealplan", "GET", "/api/shopping-list-entry/?mealplan=$id").json)
            observe("mealPlan.addShopping.entries", amounts(entries))
            val mealPlanData = entries.firstOrNull()?.objAt("list_recipe_data")?.objAt("meal_plan_data")
            observe("mealPlan.addShopping.entries.mealPlanDataId", mealPlanData?.longAt("id")?.let { it == id })
            observe(
                "mealPlan.addShopping.getShoppingFlag",
                objOf(api("mp-get", "GET", "/api/meal-plan/$id/", record = false))?.get("shopping"),
            )
        }
        val plain = api("meal-plan-create-no-addshopping", "POST", "/api/meal-plan/", mealPlanOn(recipeRef, 8, dinnerRef), record = false)
        val plainId = idOf(plain)
        observe("mealPlan.noAddShopping.shoppingFlag", objOf(plain)?.get("shopping"))
        observe("mealPlan.noAddShopping.entries", plainId?.let { entryCountOfMealPlan(it) })

        val owner = ownerId
        if (owner != null) {
            val on = api("user-preference-patch-autoadd", "PATCH", "/api/user-preference/$owner/", obj("mealplan_autoadd_shopping" to true))
            observe("userPreference.autoadd.status", on.status)
            val auto = api("meal-plan-create-autoadd", "POST", "/api/meal-plan/", mealPlanOn(recipeRef, 9, dinnerRef))
            observe("mealPlan.autoAdd.shoppingFlag", objOf(auto)?.get("shopping"))
            observe("mealPlan.autoAdd.entries", idOf(auto)?.let { entryCountOfMealPlan(it) })
            val both = api(
                "mp-both",
                "POST",
                "/api/meal-plan/",
                withAddShopping(mealPlanOn(recipeRef, 10, dinnerRef), true),
                record = false,
            )
            observe("mealPlan.autoAddAndAddShopping.entries", idOf(both)?.let { entryCountOfMealPlan(it) })
            val no = api(
                "mp-false",
                "POST",
                "/api/meal-plan/",
                withAddShopping(mealPlanOn(recipeRef, 11, dinnerRef), false),
                record = false,
            )
            observe("mealPlan.autoAddWithAddShoppingFalse.entries", idOf(no)?.let { entryCountOfMealPlan(it) })
            api("autoadd-off", "PATCH", "/api/user-preference/$owner/", obj("mealplan_autoadd_shopping" to false), record = false)
        }

        // recipe/{id}/shopping/ also takes `mealplan` (RecipeShoppingEditor) [K].
        val linked = api("mp-linked", "POST", "/api/meal-plan/", mealPlanOn(recipeRef, 12, dinnerRef), record = false)
        idOf(linked)?.let { id ->
            val body = obj("ingredients" to emptyList<Any>(), "servings" to 2, "mealplan" to id)
            val put = api("recipe-shopping-put-mealplan", "PUT", "/api/recipe/$recipe/shopping/", body)
            observe("recipeShopping.mealplan.status", put.status)
            observe("recipeShopping.mealplan.entries", entryCountOfMealPlan(id))
            observe(
                "recipeShopping.mealplan.shoppingFlag",
                objOf(api("mp-linked-get", "GET", "/api/meal-plan/$id/", record = false))?.get("shopping"),
            )
        }

        // An entry with mealplan_id creates or reuses the meal's list recipe (plan 3.3 (b)).
        if (plainId == null) return
        val first = create(
            "shopping-list-entry-create-mealplan-id",
            obj("food" to obj("name" to "grädde"), "amount" to 3, "mealplan_id" to plainId),
        )
        observe("shopping.mealplanId.status", first.status)
        observe("shopping.mealplanId.listRecipe", objOf(first)?.get("list_recipe")?.let { Facts.typeOf(it) })
        val second = create("mp-id-again", obj("food" to obj("name" to "potatis"), "amount" to 1, "mealplan_id" to plainId), record = false)
        observe(
            "shopping.mealplanId.sameListRecipe",
            objOf(first)?.longAt("list_recipe")?.let { it == objOf(second)?.longAt("list_recipe") },
        )
        val flag = objOf(api("mp-flag", "GET", "/api/meal-plan/$plainId/", record = false))?.get("shopping")
        observe("mealPlan.afterMealplanIdEntries.shoppingFlag", flag)

        // Deleting the meal: what happens to its entries, and the 404s for the deleted row.
        val entryIds = listOfNotNull(idOf(first), idOf(second))
        observe("mealPlan.delete.status", api("meal-plan-delete", "DELETE", "/api/meal-plan/$plainId/").status)
        val remaining = idsOf(fullList())
        observe("mealPlan.delete.entriesRemain", entryIds.count { it in remaining })
        observe("mealPlan.deleted.get", describe(api("meal-plan-deleted-get", "GET", "/api/meal-plan/$plainId/")))
        observe(
            "mealPlan.deleted.patch",
            describe(api("meal-plan-deleted-patch", "PATCH", "/api/meal-plan/$plainId/", obj("servings" to 1))),
        )
        observe("mealPlan.deleted.delete", describe(api("meal-plan-deleted-delete", "DELETE", "/api/meal-plan/$plainId/")))
    }

    // --- h. Recipe books: owner, shared member, smart book (#17, plan 3.7) ----------------------

    @Test
    @Order(80)
    fun recipeBooks() {
        val recipe = recipeId ?: return observe("recipeBook.skipped", "no recipe")
        val sharedWith = listOfNotNull(readerId).map { obj("id" to it) }
        val book = api("recipe-book-create-shared", "POST", "/api/recipe-book/", bookBody("Favoriter (contract-admin)", sharedWith))
        observe("recipeBook.createShared", describe(book, includeBody = book.status != CREATED))
        observe("recipeBook.createShared.sharedWithMember", objOf(book)?.arrAt("shared").objects().map { it.longAt("id") == readerId })
        objOf(book)?.let { observe("recipeBook.fieldTypes", Facts.fieldTypes(it)) }
        val bookId = idOf(book) ?: return
        val entryId = idOf(api("entry-owner", "POST", "/api/recipe-book-entry/", obj("book" to bookId, "recipe" to recipe), record = false))

        val memberBooks = api("recipe-book-list-as-shared-member", "GET", "/api/recipe-book/", token = env.readerToken)
        observe("recipeBook.sharedMember.listed", bookId in idsOf(memberBooks))
        val listed = pageOrArray(memberBooks.json).firstOrNull { it.longAt("id") == bookId }
        observe("recipeBook.sharedMember.createdBy.type", listed?.get("created_by")?.let { Facts.typeOf(it) })
        val memberEntries = api("member-entries", "GET", "/api/recipe-book-entry/?book=$bookId", token = env.readerToken, record = false)
        observe("recipeBook.sharedMember.entries", objOf(memberEntries)?.longAt("count"))
        // Another recipe than the one in the book (the same one gives 400 "unique set" before any permission check).
        val other = (api("flat", "GET", "/api/recipe/flat/", record = false).json as? JsonArray).objects()
            .mapNotNull { it.longAt("id") }.firstOrNull { it != recipe }
        val memberAdd = api(
            "recipe-book-entry-create-as-shared-member",
            "POST",
            "/api/recipe-book-entry/",
            obj("book" to bookId, "recipe" to (other ?: recipe)),
            token = env.readerToken,
        )
        observe("recipeBook.sharedMember.addEntry", describe(memberAdd))
        entryId?.let { id ->
            val memberRemove = api(
                "recipe-book-entry-delete-as-shared-member",
                "DELETE",
                "/api/recipe-book-entry/$id/",
                token = env.readerToken,
            )
            observe("recipeBook.sharedMember.deleteEntry", describe(memberRemove))
        }
        val rename = api(
            "member-rename",
            "PATCH",
            "/api/recipe-book/$bookId/",
            obj("name" to "Mina"),
            token = env.readerToken,
            record = false,
        )
        observe("recipeBook.sharedMember.patchBook", describe(rename))
        // The member's own book (plan 3.7 alternative A), shared with the contract user.
        val ownerShare = listOfNotNull(ownerId).map { obj("id" to it) }
        val memberBook = api(
            "member-book",
            "POST",
            "/api/recipe-book/",
            bookBody("Favoriter (contract-reader)", ownerShare),
            token = env.readerToken,
            record = false,
        )
        observe("recipeBook.memberCreate.status", memberBook.status)
        observe(
            "recipeBook.ownerSeesMembersSharedBook",
            idOf(memberBook)?.let { it in idsOf(api("rb-owner", "GET", "/api/recipe-book/", record = false)) },
        )

        val byRecipe = api("recipe-book-entry-list-by-recipe", "GET", "/api/recipe-book-entry/?recipe=$recipe")
        observe("recipeBook.entriesByRecipe.count", objOf(byRecipe)?.longAt("count"))
        observe("recipeBook.entries.list.keys", objOf(byRecipe)?.keys?.toList())

        entryId?.let { id ->
            observe("recipeBook.deleteEntry.status", api("recipe-book-entry-delete", "DELETE", "/api/recipe-book-entry/$id/").status)
            observe("recipeBook.deletedEntry.get", describe(api("recipe-book-entry-deleted-get", "GET", "/api/recipe-book-entry/$id/")))
            observe(
                "recipeBook.deletedEntry.delete",
                describe(api("recipe-book-entry-deleted-delete", "DELETE", "/api/recipe-book-entry/$id/")),
            )
        }

        val guestBook = api(
            "recipe-book-create-as-guest",
            "POST",
            "/api/recipe-book/",
            bookBody("Gästbok", emptyList()),
            token = env.guestToken,
        )
        observe("recipeBook.guest.create", describe(guestBook))
        val guestBooks = api("rb-guest", "GET", "/api/recipe-book/", token = env.guestToken, record = false)
        observe("recipeBook.guest.list", describe(guestBooks, includeBody = false))

        observe("recipeBook.delete.status", api("recipe-book-delete", "DELETE", "/api/recipe-book/$bookId/").status)
        observe("recipeBook.deleted.get", describe(api("recipe-book-deleted-get", "GET", "/api/recipe-book/$bookId/")))
    }

    /** "Vill laga" (plan 3.7 alternative B): a keyword every member can set on a recipe, and a smart book on it. */
    @Test
    @Order(81)
    fun wantToCook() {
        val recipe = recipeId ?: return observe("wantToCook.skipped", "no recipe")
        val keyword = api("keyword-create-vill-laga", "POST", "/api/keyword/", obj("name" to WANT_TO_COOK), record = false)
        observe("wantToCook.keyword.status", keyword.status)
        val keywordId = idOf(keyword)
        val detail = api("recipe-get", "GET", "/api/recipe/$recipe/", record = false)
        val keywords = objOf(detail)?.arrAt("keywords").objects().map { obj("id" to it.longAt("id"), "name" to it.stringAt("name")) }
        val withKeyword = keywords + obj("name" to WANT_TO_COOK)
        val path = "/api/recipe/$recipe/"
        val memberPatch = api("recipe-patch-keywords-as-member", "PATCH", path, obj("keywords" to withKeyword), token = env.readerToken)
        observe("wantToCook.memberPatch.status", memberPatch.status)
        val patchedKeywords = objOf(memberPatch)?.arrAt("keywords").objects()
        observe("wantToCook.memberPatch.keywords", patchedKeywords.map { it.stringAt("name") })
        observe("wantToCook.memberPatch.sameKeywordId", patchedKeywords.any { it.longAt("id") == keywordId })
        val guestPatch = api("recipe-patch-keywords-as-guest", "PATCH", path, obj("keywords" to withKeyword), token = env.guestToken)
        observe("wantToCook.guestPatch", describe(guestPatch))
        if (keywordId == null) return

        val searches = listOf(
            "ids" to """{"keywords_or":[$keywordId]}""",
            "objects" to """{"keywords_or":[{"id":$keywordId,"name":"$WANT_TO_COOK"}]}""",
        )
        for ((label, search) in searches) {
            val filter = customFilter(label, search) ?: continue
            if (label == "ids") smartBook(filter)
        }
        val byKeyword = api("keywords-or", "GET", "/api/recipe/?keywords_or=$keywordId&page_size=100", record = false)
        observe("wantToCook.keywordsOr.recipes", pageOrArray(byKeyword.json).map { it.stringAt("name") })
        // Taking the keyword off again: the whole list without it.
        val remove = api("remove-keyword", "PATCH", path, obj("keywords" to keywords), token = env.readerToken, record = false)
        observe("wantToCook.remove.keywords", objOf(remove)?.arrAt("keywords").objects().map { it.stringAt("name") })
    }

    /** A `CustomFilter` with [search] and the recipe list through it; the filter, or null when it was refused. */
    private fun customFilter(label: String, search: String): JsonObject? {
        val filterBody = obj("name" to "Vill laga ($label)", "search" to search, "shared" to emptyList<Any>())
        val filter = api("custom-filter-create-$label", "POST", "/api/custom-filter/", filterBody, record = label == "ids")
        observe("wantToCook.filter.$label.create", describe(filter, includeBody = filter.status != CREATED))
        val filterId = idOf(filter) ?: return null
        val list = api("recipe-list-filter-$label", "GET", "/api/recipe/?filter=$filterId&page_size=100", record = label == "ids")
        observe("wantToCook.filter.$label.status", list.status)
        observe("wantToCook.filter.$label.recipes", pageOrArray(list.json).map { it.stringAt("name") })
        return objOf(filter)
    }

    /** The "Vill laga" smart book on [filter], shared with the other member, and what that member sees. */
    private fun smartBook(filter: JsonObject) {
        val filterId = filter.longAt("id")
        val sharedWith = listOfNotNull(readerId).map { obj("id" to it) }
        val smart = api("recipe-book-create-smart", "POST", "/api/recipe-book/", bookBody("Vill laga", sharedWith, filter))
        observe("wantToCook.smartBook.create", describe(smart, includeBody = smart.status != CREATED))
        observe("wantToCook.smartBook.filter.keys", objOf(smart)?.objAt("filter")?.keys?.sorted())
        val smartId = idOf(smart) ?: return
        val entries = api("smart-entries", "GET", "/api/recipe-book-entry/?book=$smartId", record = false)
        observe("wantToCook.smartBook.entries", objOf(entries)?.longAt("count"))
        val asMember = api("filter-member", "GET", "/api/recipe/?filter=$filterId&page_size=100", token = env.readerToken, record = false)
        observe("wantToCook.smartBook.memberRecipes", describe(asMember, includeBody = false))
        observe("wantToCook.smartBook.memberRecipes.names", pageOrArray(asMember.json).map { it.stringAt("name") })
    }

    // --- Baseline -------------------------------------------------------------------------------

    @Test
    @Order(99)
    fun everyFactMatchesTheBaseline() {
        for ((key, expected) in EXPECTED) {
            val described = (facts[key.removeSuffix(".status")] as? JsonObject)?.get("status")
            val observed = facts[key] ?: described.takeIf { key.endsWith(".status") }
            if (observed != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed $observed"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the 2.6.13 baseline:\n" + mismatches.joinToString("\n"))
    }

    // --- Helpers --------------------------------------------------------------------------------

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

    private fun create(name: String, body: JsonObject, token: String = env.fas1Token, record: Boolean = true): Exchange =
        api(name, "POST", "/api/shopping-list-entry/", body, token, record)

    private fun patchEntry(name: String, id: Long, body: JsonObject, record: Boolean = true): Exchange =
        api(name, "PATCH", "/api/shopping-list-entry/$id/", body, record = record)

    private fun getEntry(name: String, id: Long, record: Boolean = true): Exchange =
        api(name, "GET", "/api/shopping-list-entry/$id/", record = record)

    private fun bulk(name: String, body: JsonObject, token: String = env.fas1Token, record: Boolean = true): Exchange {
        pause()
        return api(name, "POST", "/api/shopping-list-entry/bulk/", body, token, record)
    }

    private fun fullList(): Exchange = api("full-list", "GET", "/api/shopping-list-entry/?page_size=200", record = false)

    private fun listAfter(name: String, timestamp: String, record: Boolean = false): Exchange =
        api(name, "GET", "/api/shopping-list-entry/?page_size=200&updated_after=${enc(timestamp)}", record = record)

    private fun entriesOf(listRecipe: Long): List<JsonObject> =
        pageOrArray(fullList().json).filter { it.longAt("list_recipe") == listRecipe }

    private fun entryCountOfMealPlan(id: Long): Int =
        pageOrArray(api("mealplan-entries", "GET", "/api/shopping-list-entry/?mealplan=$id", record = false).json).size

    private fun mealPlanIdsOn(day: LocalDate): List<Long> = idsOf(
        api("mp-day", "GET", "/api/meal-plan/?from_date=$day&to_date=$day", record = false),
    )

    /** The server's own clock, as the app uses it (`timestamp` of a list response), then a pause so later writes are newer. */
    private fun timestampNow(): String? {
        val timestamp = objOf(api("ts", "GET", "/api/shopping-list-entry/?page_size=1", record = false))?.stringAt("timestamp")
        pause()
        return timestamp
    }

    private fun objOf(ex: Exchange): JsonObject? = ex.json as? JsonObject

    private fun idOf(ex: Exchange): Long? = objOf(ex)?.longAt("id")

    private fun idsOf(ex: Exchange): List<Long> = pageOrArray(ex.json).mapNotNull { it.longAt("id") }

    private fun datesOf(ex: Exchange): List<String?>? = objOf(ex)?.let { listOf(it.stringAt("from_date"), it.stringAt("to_date")) }

    private fun amounts(entries: List<JsonObject>): List<String> =
        entries.map { "${it.objAt("food")?.stringAt("name")}=${it["amount"]}" }.sorted()

    /** The `results` of a DRF page, or the elements of a plain array. */
    private fun pageOrArray(json: JsonElement?): List<JsonObject> = when (json) {
        is JsonObject -> json.arrAt("results").objects()
        is JsonArray -> json.objects()
        else -> emptyList()
    }

    /** A JSON object from pairs; values as [Facts.toJson] converts them (maps, lists, numbers, null). */
    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.associate { (key, value) -> key to Facts.toJson(value) },
    )

    private fun ref(source: JsonObject): JsonObject = obj("id" to source.longAt("id"), "name" to source.stringAt("name"))

    private fun bookBody(name: String, shared: List<JsonObject>, filter: JsonObject? = null): JsonObject =
        obj("name" to name, "description" to "", "shared" to shared, "filter" to filter)

    private fun mealPlan(title: String, recipe: JsonObject?, servings: Int, fromDate: String, mealType: JsonObject): JsonObject =
        obj("title" to title, "recipe" to recipe, "servings" to servings, "note" to "", "from_date" to fromDate, "meal_type" to mealType)

    private fun mealPlanOn(recipe: JsonObject, daysAhead: Long, mealType: JsonObject): JsonObject =
        mealPlan("", recipe, 4, "${today.plusDays(daysAhead)}T00:00:00", mealType)

    private fun withAddShopping(body: JsonObject, value: Boolean): JsonObject = JsonObject(body + ("addshopping" to JsonPrimitive(value)))

    private fun ingredient(order: Int, amount: Double, unit: String, food: String): JsonObject = obj(
        "food" to obj("name" to food),
        "unit" to obj("name" to unit),
        "amount" to amount,
        "note" to "",
        "order" to order,
        "is_header" to false,
        "no_amount" to false,
    )

    /** `2026-10-25T14:03:11.123456+01:00` -> `9999-99-99T99:99:99.999999+99:99` (only the shape is a fact). */
    private fun shape(value: String): String = value.replace(Regex("[0-9]"), "9")

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun pause() = Thread.sleep(SMALL_PAUSE_MS)

    /** True when a Tandoor date-time (with offset) lies before now; null when unreadable. */
    private fun isPast(value: String): Boolean? =
        runCatching { OffsetDateTime.parse(value).toInstant().isBefore(Instant.now()) }.getOrNull()

    /** Whole seconds with the zone's offset, as the web app sends date-times. */
    private fun isoOffset(time: ZonedDateTime): String = time.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

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
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val CREATED = 201
        const val NO_CONTENT = 204
        const val SMALL_PAUSE_MS = 1100L
        const val MAX_TEXT = 200
        const val ARCHIVED_ENTRIES = 3
        const val DEFAULT_AUTO_SYNC = 5
        const val RECIPE = "Potatisgratäng"
        const val STORE = "Testbutiken"
        const val WANT_TO_COOK = "vill-laga"

        /** amount, unit, food of the seeded recipe. */
        val RECIPE_INGREDIENTS = listOf(
            Triple(1.0, "kg", "potatis"),
            Triple(3.0, "dl", "grädde"),
            Triple(2.0, "dl", "mjölk"),
            Triple(1.0, "msk", "smör"),
        )

        val CATEGORIES = listOf("Grönsaker", "Mejeri", "Övrigt")

        val PREFERENCE_KEYS = listOf(
            "mealplan_autoadd_shopping",
            "mealplan_autoexclude_onhand",
            "mealplan_autoinclude_related",
            "shopping_auto_sync",
            "shopping_share",
            "default_delay",
            "shopping_recent_days",
            "shopping_add_onhand",
            "filter_to_supermarket",
            "default_meal_type",
            "shopping_update_food_lists",
        )

        private val PAGE_KEYS = listOf("count", "next", "previous", "timestamp", "results")

        private fun parsed(amount: Double, unit: String?, food: String, note: String = ""): Map<String, Any?> =
            mapOf("amount" to amount, "unit" to unit, "food" to food, "note" to note)

        /**
         * The facts the app relies on (api-schema/fixtures/<version>/FACTS.md "Fas 2"), the same in
         * 2.6.13 and 2.7.1. A key ending in `.status` also matches the status of a recorded response
         * description. Every other fact is only recorded; tandoor-watch compares it between versions.
         */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "serverSettings.shoppingMinAutosyncInterval.type" to "string",
            "userPreference.shopping_share" to "<absent>",
            "userPreference.patchOtherUser.status" to 404,
            "userSpace.guest.groups" to listOf("guest"),
            "userSpace.owner.household.keys" to listOf("created_at", "id", "name", "updated_at"),
            "household.list.reader.status" to 403,
            "shoppingList.list.keys" to PAGE_KEYS,
            "supermarket.createWithShoppingLists.status" to 500,
            "supermarket.create.status" to 201,
            "supermarket.list.categoryOrder" to listOf("Grönsaker=0", "Mejeri=1", "Övrigt=10"),
            "food.patchCategory.status" to 200,
            "food.get.foodOnhand" to true,
            "food.get.foodOnhand.reader" to true,
            "shopping.list.keys" to PAGE_KEYS,
            "shopping.create.status" to 201,
            "shopping.entry.food.keys" to listOf("id", "name", "plural_name", "shopping_lists", "supermarket_category"),
            "shopping.createExistingFood.sameFoodId" to true,
            "shopping.createNoFood.status" to 500,
            "shopping.createNoAmount.status" to 400,
            "shopping.createZeroAmount.status" to 201,
            "shopping.updatedAfter.t0.includesCreated" to true,
            "shopping.updatedAfter.rawPlus.returnsEverything" to true,
            "shopping.patchChecked.seenByUpdatedAfter" to true,
            "shopping.bulkCheck.status" to 200,
            "shopping.bulkCheck.seenByUpdatedAfter" to true,
            "shopping.bulkCheck.completedAtSet" to true,
            "shopping.bulkUncheck.seenByUpdatedAfter" to true,
            "shopping.bulkUncheck.completedAt" to null,
            "shopping.bulkListsAdd.seenByUpdatedAfter" to false,
            "shopping.patchLists.seenByUpdatedAfter" to true,
            "shopping.delay.listed" to true,
            "shopping.delayNull.status" to 200,
            // The app's own "Köp nu" (ShoppingPayloads.NOT_DELAYED = 1970-01-01T00:00:00Z).
            "shopping.undelay.status" to 200,
            "shopping.undelay.notDelayed" to true,
            "shopping.undelay.listed" to true,
            "shopping.window.recentDays7.listed30d10d3d" to listOf(false, false, true),
            "shopping.window.recentDays20.listed30d10d3d" to listOf(false, true, true),
            "shopping.window.agedOut.detailStatus" to 200,
            "shopping.delete.status" to 204,
            "shopping.deleted.get.status" to 404,
            "shopping.deleted.patch.status" to 404,
            "shopping.deleted.delete.status" to 404,
            "shopping.deleted.bulk.status" to 200,
            "shopping.deleted.bulkMixed.otherChecked" to true,
            "shopping.householdMember.seesOwnersEntries" to true,
            "shopping.householdMember.ownerSeesMembersEntry" to true,
            "shopping.guest.create.status" to 201,
            "shopping.guest.bulk.status" to 403,
            "recipeShopping.entries.foods" to listOf("grädde=6.0", "potatis=2.0"),
            "recipeShopping.putServings.foods" to listOf("grädde=3.0", "potatis=1.0"),
            "listRecipe.patchServings.foods" to listOf("grädde=1.5", "potatis=0.5"),
            "listRecipe.delete.entriesRemain" to 0,
            "listRecipe.bulkCreateEntries.created" to listOf("mjölk=1.5"),
            "recipeShopping.putServings0.status" to 500,
            "recipeShopping.putNegativeServings.entriesRemain" to 0,
            "recipeShopping.mealplan.status" to 500,
            "parser.sample.2 dl grädde" to parsed(2.0, "dl", "grädde"),
            "parser.sample.mjölk" to parsed(0.0, null, "mjölk"),
            "parser.sample.6 ägg" to parsed(6.0, null, "ägg"),
            "parser.sample.1 1/2 tsk salt" to parsed(1.5, "tsk", "salt"),
            "parser.sample.1,5 kg potatis" to parsed(1.5, "kg", "potatis"),
            "parser.sample.ca 200 g smör, rumsvarmt" to parsed(200.0, "g", "ca smör", "rumsvarmt"),
            "parser.sample.1 paket smör" to parsed(1.0, "paket", "smör"),
            "parser.sample.½ citron" to parsed(0.5, null, "citron"),
            "parser.sample.ca 200 g smör, rumsvarmt.foodHasId" to true,
            "mealType.create.status" to 201,
            "mealPlan.dateOnly.status" to 201,
            "mealPlan.lateUtc.listedOnDay3" to false,
            "mealPlan.lateUtc.listedOnDay4" to true,
            "mealPlan.twoDays.listedOnFirstDay" to false,
            "mealPlan.twoDays.listedOnSecondDay" to true,
            // The app's own move (MealPlanDates.toServer: both dates, naive server-local).
            "mealPlan.moveBoth.status" to 200,
            "mealPlan.moveBoth.listedOnTargetDay" to true,
            "mealPlan.moveBoth.listedOnOldDay" to false,
            "mealPlan.patchFields.status" to 200,
            "mealPlan.addShopping.shoppingFlag" to true,
            "mealPlan.addShopping.entries" to listOf("grädde=3.0", "mjölk=2.0", "potatis=1.0", "smör=1.0"),
            "mealPlan.noAddShopping.entries" to 0,
            "mealPlan.autoAdd.entries" to 0,
            "mealPlan.autoAddAndAddShopping.entries" to 4,
            "mealPlan.autoAddWithAddShoppingFalse.entries" to 0,
            "shopping.mealplanId.sameListRecipe" to true,
            "mealPlan.afterMealplanIdEntries.shoppingFlag" to true,
            "mealPlan.delete.entriesRemain" to 0,
            "mealPlan.deleted.get.status" to 404,
            "mealPlan.deleted.patch.status" to 404,
            "mealPlan.deleted.delete.status" to 404,
            "mealPlan.householdMember.seesSame" to true,
            "mealPlan.guest.create.status" to 201,
            "recipeBook.createShared.status" to 201,
            "recipeBook.sharedMember.listed" to true,
            "recipeBook.sharedMember.addEntry.status" to 201,
            "recipeBook.sharedMember.deleteEntry.status" to 403,
            "recipeBook.sharedMember.patchBook.status" to 403,
            "recipeBook.ownerSeesMembersSharedBook" to true,
            "recipeBook.deletedEntry.get.status" to 404,
            "recipeBook.guest.create.status" to 201,
            "recipeBook.deleted.get.status" to 404,
            "wantToCook.memberPatch.status" to 200,
            "wantToCook.memberPatch.sameKeywordId" to true,
            "wantToCook.guestPatch.status" to 403,
            "wantToCook.filter.ids.recipes" to listOf(RECIPE),
            "wantToCook.filter.objects.status" to 500,
            "wantToCook.smartBook.memberRecipes.names" to listOf(RECIPE),
            "wantToCook.remove.keywords" to emptyList<String>(),
        )

        val PARSER_SAMPLES = listOf(
            "2 dl grädde",
            "mjölk",
            "6 ägg",
            "1 1/2 tsk salt",
            "1,5 kg potatis",
            "ca 200 g smör, rumsvarmt",
            "1 paket smör",
            "½ citron",
        )
    }
}
