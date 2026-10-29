package casa.moller.tandem.core.sync.demo

import casa.moller.tandem.core.data.demo.DemoLanguage
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.InventoryEntryEntity
import casa.moller.tandem.core.database.entity.InventoryLocationEntity
import casa.moller.tandem.core.database.entity.StapleEntity
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.domain.pantry.StapleCatalog
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.pantry.parseDate
import java.util.Locale
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * Fas S (hemma-data lane): the demo pantry (plan 6.2 "Demoläge", §8.8): Kyl, Frys and Skafferi with
 * the foods of the demo recipes (cream and salmon expire tomorrow, so "Använd upp" has something to
 * lift), and the usual staples marked "har hemma". "Kan laga nu" is computed from it on the device
 * (`DefaultMakeNowRepository`): Har allt, Saknar 1 and Saknar 2 all have recipes. Never the network.
 *
 * Seeded once per content version and language ([SEED_KEY]), so what the user does in demo mode
 * stays; on a new day the expiry dates move along (they are written relative to the seed day).
 * The foods' names come from the demo recipes' ingredients, so they follow the demo's language.
 */
internal object DemoPantry {
    const val SEED_KEY: String = "demo_pantry_seed"

    private const val FRIDGE = 1L
    private const val FREEZER = 2L
    private const val PANTRY = 3L

    /** The demo's entries: food id of the demo content, location, amount, unit, expiry (days from the seed day). */
    private class Item(val foodId: Long, val location: Long, val amount: Double, val unit: Map<DemoLanguage, String>, val days: Int?)

    private val pieces = mapOf(DemoLanguage.SWEDISH to "st", DemoLanguage.ENGLISH to "pcs")
    private val litre = mapOf(DemoLanguage.SWEDISH to "l", DemoLanguage.ENGLISH to "l")
    private val decilitre = mapOf(DemoLanguage.SWEDISH to "dl", DemoLanguage.ENGLISH to "dl")
    private val grams = mapOf(DemoLanguage.SWEDISH to "g", DemoLanguage.ENGLISH to "g")
    private val packet = mapOf(DemoLanguage.SWEDISH to "förp", DemoLanguage.ENGLISH to "pack")
    private val jar = mapOf(DemoLanguage.SWEDISH to "burk", DemoLanguage.ENGLISH to "jar")
    private val kilograms = mapOf(DemoLanguage.SWEDISH to "kg", DemoLanguage.ENGLISH to "kg")

    private val items = listOf(
        // Kyl: whipping cream and salmon expire tomorrow ("Grädden går ut i morgon").
        Item(foodId = 19, location = FRIDGE, amount = 3.0, unit = decilitre, days = 1),
        Item(foodId = 5, location = FRIDGE, amount = 400.0, unit = grams, days = 1),
        Item(foodId = 18, location = FRIDGE, amount = 2.0, unit = decilitre, days = 2),
        Item(foodId = 10, location = FRIDGE, amount = 1.0, unit = packet, days = 3),
        Item(foodId = 43, location = FRIDGE, amount = 1.0, unit = litre, days = 5),
        Item(foodId = 46, location = FRIDGE, amount = 6.0, unit = pieces, days = 10),
        Item(foodId = 9, location = FRIDGE, amount = 1.0, unit = packet, days = 20),
        Item(foodId = 20, location = FRIDGE, amount = 1.0, unit = packet, days = 6),
        Item(foodId = 6, location = FRIDGE, amount = 2.0, unit = pieces, days = 12),
        Item(foodId = 81, location = FRIDGE, amount = 4.0, unit = pieces, days = 14),
        // Frys.
        Item(foodId = 50, location = FREEZER, amount = 500.0, unit = grams, days = 60),
        Item(foodId = 54, location = FREEZER, amount = 500.0, unit = grams, days = 45),
        // Skafferi.
        Item(foodId = 1, location = PANTRY, amount = 2.0, unit = kilograms, days = 14),
        Item(foodId = 11, location = PANTRY, amount = 3.0, unit = pieces, days = 20),
        Item(foodId = 13, location = PANTRY, amount = 1.0, unit = pieces, days = 20),
        Item(foodId = 14, location = PANTRY, amount = 2.0, unit = jar, days = 200),
        Item(foodId = 45, location = PANTRY, amount = 2.0, unit = packet, days = null),
        Item(foodId = 17, location = PANTRY, amount = 1.0, unit = packet, days = null),
        Item(foodId = 80, location = PANTRY, amount = 1.0, unit = packet, days = null),
        Item(foodId = 57, location = PANTRY, amount = 1.0, unit = packet, days = null),
        Item(foodId = 48, location = PANTRY, amount = 1.0, unit = jar, days = 90),
    )

    /** Salt, svartpeppar, olivolja, rapsolja, vatten and kanel: "har hemma". */
    private val stapleFoodIds = listOf(2L, 3L, 7L, 8L, 15L, 82L)

    suspend fun ensureSeeded(database: TandemDatabase, contentVersion: Int, locale: Locale, today: LocalDate, now: Long) {
        val accountId = DEMO_ACCOUNT_ID
        val language = DemoLanguage.of(locale)
        val key = "v" + contentVersion + "|" + language.tag
        val states = database.syncStateDao()
        val state = states.get(accountId, SEED_KEY)
        if (state?.hash == key) {
            moveDates(database, state.lastDate?.let(::parseDate), today)
            if (state.lastDate != today.toString()) states.upsert(state.copy(lastDate = today.toString()))
            return
        }
        val names = foodNames(database)
        val pantry = database.pantryDao()
        pantry.inTransaction {
            pantry.replaceServerRows(accountId, locations(language, now), entries(language, names, today, now))
            seedStaples(database, names, now)
            database.makeNowDao().deleteAll(accountId)
            states.upsert(SyncStateEntity(accountId = accountId, entity = SEED_KEY, hash = key, lastDate = today.toString()))
        }
    }

    private fun locations(language: DemoLanguage, now: Long): List<InventoryLocationEntity> {
        val (fridge, freezer, pantry) = if (language == DemoLanguage.SWEDISH) {
            Triple("Kyl", "Frys", "Skafferi")
        } else {
            Triple("Fridge", "Freezer", "Pantry")
        }
        return listOf(
            location(FRIDGE, fridge, isFreezer = false, order = 1, now = now),
            location(FREEZER, freezer, isFreezer = true, order = 2, now = now),
            location(PANTRY, pantry, isFreezer = false, order = 3, now = now),
        )
    }

    private fun location(id: Long, name: String, isFreezer: Boolean, order: Int, now: Long) = InventoryLocationEntity(
        accountId = DEMO_ACCOUNT_ID,
        localId = id,
        serverId = id,
        name = name,
        isFreezer = isFreezer,
        locationOrder = order,
        fetchedAtEpochMillis = now,
    )

    private fun entries(
        language: DemoLanguage,
        names: Map<Long, Pair<String, String?>>,
        today: LocalDate,
        now: Long,
    ): List<InventoryEntryEntity> = items.mapIndexedNotNull { index, item ->
        val (name, plural) = names[item.foodId] ?: return@mapIndexedNotNull null
        InventoryEntryEntity(
            accountId = DEMO_ACCOUNT_ID,
            localId = index + 1L,
            serverId = index + 1L,
            locationId = item.location,
            foodId = item.foodId,
            foodName = name,
            foodPluralName = plural,
            unitName = item.unit[language],
            amount = item.amount,
            expires = item.days?.let { today.plus(DatePeriod(days = it)).toString() },
            createdAtEpochMillis = now,
            fetchedAtEpochMillis = now,
        )
    }

    /** The staples' foods "har hemma" (added to the demo's food list when it lacks them) and the staple list. */
    private suspend fun seedStaples(database: TandemDatabase, names: Map<Long, Pair<String, String?>>, now: Long) {
        val accountId = DEMO_ACCOUNT_ID
        val known = database.referenceDao().getFoods(accountId).associateBy { it.id }
        val staples = mutableListOf<StapleEntity>()
        for (foodId in stapleFoodIds) {
            val (name, plural) = names[foodId] ?: continue
            if (foodId in known) {
                database.supermarketDao().setFoodOnHand(accountId, foodId, true)
            } else {
                val food = FoodEntity(accountId = accountId, id = foodId, name = name, pluralName = plural, onHand = true)
                database.referenceDao().upsertFoods(listOf(food))
            }
            staples += StapleEntity(accountId, StapleCatalog.normalize(name), name, foodId, now)
        }
        database.stapleDao().upsertStaples(staples)
    }

    /** The demo recipes' foods by id: name and plural, in the demo's language (from the stored ingredients). */
    private suspend fun foodNames(database: TandemDatabase): Map<Long, Pair<String, String?>> {
        val dao = database.makeNowDao()
        val names = HashMap<Long, Pair<String, String?>>()
        for (recipeId in dao.getDetailIds(DEMO_ACCOUNT_ID)) {
            for (ingredient in dao.getIngredients(DEMO_ACCOUNT_ID, recipeId)) {
                val id = ingredient.foodId
                val name = ingredient.foodName
                if (id != null && name != null) names.getOrPut(id) { name to ingredient.foodPluralName }
            }
        }
        return names
    }

    /** Moves every dated entry by the days since [seededOn], so "går ut i morgon" stays tomorrow in a demo kept for days. */
    private suspend fun moveDates(database: TandemDatabase, seededOn: LocalDate?, today: LocalDate) {
        val from = seededOn ?: return
        val days = from.daysUntil(today)
        if (days <= 0) return
        val pantry = database.pantryDao()
        val moved = pantry.getEntries(DEMO_ACCOUNT_ID).mapNotNull { entry ->
            val expires = entry.expires?.let(::parseDate) ?: return@mapNotNull null
            entry.copy(expires = expires.plus(DatePeriod(days = days)).toString())
        }
        if (moved.isNotEmpty()) pantry.upsertEntries(moved)
    }
}
