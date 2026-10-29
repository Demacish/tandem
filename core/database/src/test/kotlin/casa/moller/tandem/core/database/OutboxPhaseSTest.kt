package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.DrinkDraftEntity
import casa.moller.tandem.core.database.entity.FoodAliasEntity
import casa.moller.tandem.core.database.entity.FoodAliasKinds
import casa.moller.tandem.core.database.entity.InventoryEntryEntity
import casa.moller.tandem.core.database.entity.InventoryLocationEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.MakeNowEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.RecipeKeywordFields
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Fas S outbox and table contract (docs/BYGGE.md "Fas S – kontrakt"): temporary ids of the new
 * entities, the id rewrite after their CREATE (a location's entries follow it, a keyword's parent
 * and recipe links follow it, a drink's draft is done), the merge rules of `BATCH_ON_HAND` and
 * `RECIPE_IMAGE`, the per-keyword fields of `RECIPE_KEYWORDS`, and the first queries of the lanes'
 * DAOs.
 */
@RunWith(AndroidJUnit4::class)
class OutboxPhaseSTest {
    private val database = newDatabase()
    private val outbox = database.outboxDao()
    private val ids = database.idRewriteDao()
    private val pantry = database.pantryDao()

    @After
    fun close() {
        database.close()
    }

    private fun row(
        op: String,
        entity: String,
        localId: Long? = null,
        serverId: Long? = null,
        payload: String = "{}",
        base: String? = null,
        batchKey: String? = null,
    ) = OutboxEntity(
        accountId = ACCOUNT_A,
        op = op,
        entity = entity,
        localId = localId,
        serverId = serverId,
        payload = payload,
        baseSnapshot = base,
        batchKey = batchKey,
        createdAtEpochMillis = 1,
    )

    private fun location(localId: Long, serverId: Long?, name: String = "Kyl") =
        InventoryLocationEntity(accountId = ACCOUNT_A, localId = localId, serverId = serverId, name = name)

    private fun entry(
        localId: Long,
        serverId: Long?,
        locationId: Long,
        food: String = "grädde",
        expires: String? = null,
        amount: Double = 1.0,
    ) = InventoryEntryEntity(
            accountId = ACCOUNT_A,
            localId = localId,
            serverId = serverId,
            locationId = locationId,
            foodName = food,
            amount = amount,
            expires = expires,
        )

    @Test
    fun aLocationCreatedOfflineTakesItsEntriesAlongWhenItGetsItsServerId() = runTest {
        val locationId = ids.nextLocalId(ACCOUNT_A, OutboxEntities.INVENTORY_LOCATION)
        assertEquals(-1L, locationId)
        pantry.upsertLocations(listOf(location(locationId, null)))
        val entryId = ids.nextLocalId(ACCOUNT_A, OutboxEntities.INVENTORY_ENTRY)
        pantry.upsertEntries(listOf(entry(entryId, null, locationId)))
        val create = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, OutboxEntities.INVENTORY_LOCATION, localId = locationId)))

        ids.completeCreate(ACCOUNT_A, create, OutboxEntities.INVENTORY_LOCATION, locationId, serverId = 12, nowEpochMillis = 2)

        assertEquals(listOf(12L), pantry.getLocations(ACCOUNT_A).map { it.serverId })
        assertEquals(12L, pantry.getEntry(ACCOUNT_A, entryId)?.locationId)
        assertEquals(12L, ids.current(ACCOUNT_A, OutboxEntities.INVENTORY_LOCATION, locationId))
    }

    @Test
    fun anEntryAndAnAliasAreReKeyedAndASyncCopyGivesWay() = runTest {
        pantry.upsertEntries(listOf(entry(-1, null, 3), entry(40, 40, 3)))
        val createEntry = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, OutboxEntities.INVENTORY_ENTRY, localId = -1)))
        ids.completeCreate(ACCOUNT_A, createEntry, OutboxEntities.INVENTORY_ENTRY, -1, serverId = 40, nowEpochMillis = 2)
        assertEquals(listOf(40L), pantry.getEntries(ACCOUNT_A).map { it.localId })

        val aliases = database.foodAliasDao()
        aliases.upsert(
            listOf(
                FoodAliasEntity(
                    accountId = ACCOUNT_A,
                    localId = -1,
                    serverId = null,
                    alias = "2000000000015",
                    aliasKey = "2000000000015",
                    foodName = "kikärtor",
                    kind = FoodAliasKinds.BARCODE,
                ),
            ),
        )
        val createAlias = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, OutboxEntities.FOOD_ALIAS, localId = -1)))
        ids.completeCreate(ACCOUNT_A, createAlias, OutboxEntities.FOOD_ALIAS, -1, serverId = 5, nowEpochMillis = 2)
        assertEquals(listOf(5L), aliases.find(ACCOUNT_A, "2000000000015").map { it.serverId })
    }

    @Test
    fun aKeywordCreatedOfflineKeepsItsChildrenAndRecipes() = runTest {
        val keywordId = ids.nextLocalId(ACCOUNT_A, OutboxEntities.KEYWORD)
        database.recipeDao().replaceKeywords(
            ACCOUNT_A,
            listOf(
                KeywordEntity(ACCOUNT_A, id = keywordId, name = "Passar med"),
                KeywordEntity(ACCOUNT_A, id = keywordId - 1, name = "Kraftigt rött", parentId = keywordId),
            ),
        )
        val create = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, OutboxEntities.KEYWORD, localId = keywordId)))

        ids.completeCreate(ACCOUNT_A, create, OutboxEntities.KEYWORD, keywordId, serverId = 90, nowEpochMillis = 2)

        val keywords = database.recipeDao().observeKeywords(ACCOUNT_A).first().associateBy { it.name }
        assertEquals(90L, keywords.getValue("Passar med").id)
        assertEquals(90L, keywords.getValue("Kraftigt rött").parentId)
    }

    @Test
    fun aDrinkDraftIsDoneWhenTheRecipeExistsAndItsPhotoGetsTheServerId() = runTest {
        val drinks = database.drinkDraftDao()
        val drinkId = ids.nextLocalId(ACCOUNT_A, OutboxEntities.DRINK)
        drinks.upsert(DrinkDraftEntity(accountId = ACCOUNT_A, localId = drinkId, name = "Rioja", createdAtEpochMillis = 1))
        val create = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, OutboxEntities.DRINK, localId = drinkId)))
        outbox.enqueue(row(OutboxOps.RECIPE_IMAGE, OutboxEntities.DRINK, localId = drinkId, payload = """{"file":"a.jpg"}"""))
        // A newer photo before sending replaces the older one.
        outbox.enqueue(row(OutboxOps.RECIPE_IMAGE, OutboxEntities.DRINK, localId = drinkId, payload = """{"file":"b.jpg"}"""))

        ids.completeCreate(ACCOUNT_A, create, OutboxEntities.DRINK, drinkId, serverId = 77, nowEpochMillis = 2)

        assertNull(drinks.get(ACCOUNT_A, drinkId))
        val image = outbox.getAllFor(ACCOUNT_A, OutboxEntities.DRINK).single()
        assertEquals(OutboxOps.RECIPE_IMAGE, image.op)
        assertEquals(77L, image.serverId)
        assertEquals("""{"file":"b.jpg"}""", image.payload)
    }

    @Test
    fun onHandInBatchMergesAndCancelsLikeACheck() = runTest {
        val on = row(
            OutboxOps.BATCH_ON_HAND,
            OutboxEntities.FOOD,
            serverId = 7,
            payload = """{"on_hand":true}""",
            base = """{"on_hand":false}""",
            batchKey = "food:on_hand=true",
        )
        outbox.enqueue(on)
        outbox.enqueue(on.copy(serverId = 8))
        assertEquals(2, outbox.nextReadyBatch(ACCOUNT_A, "food:on_hand=true", limit = 10).size)

        outbox.enqueue(on.copy(payload = """{"on_hand":false}""", batchKey = "food:on_hand=false"))
        assertEquals(listOf(8L), outbox.getAllFor(ACCOUNT_A, OutboxEntities.FOOD).map { it.serverId })
    }

    @Test
    fun wantToCookAndPairingsOfOneRecipeLiveSideBySide() = runTest {
        val wantToCook = row(
            OutboxOps.RECIPE_KEYWORDS,
            OutboxEntities.RECIPE,
            serverId = 3,
            payload = """{"keywords":{"vill-laga":true}}""",
            base = """{"keywords":{"vill-laga":false}}""",
        )
        outbox.enqueue(wantToCook)
        val field = RecipeKeywordFields.keyword(41)
        outbox.enqueue(wantToCook.copy(payload = """{"$field":true}""", baseSnapshot = """{"$field":false}"""))

        val merged = outbox.getAllFor(ACCOUNT_A, OutboxEntities.RECIPE).single()
        assertEquals("""{"keywords":{"vill-laga":true},"keyword:41":true}""", merged.payload)
        assertEquals(41L, RecipeKeywordFields.keywordIdOf(field))
        assertEquals("Kraftigt rött", RecipeKeywordFields.pairingNameOf(RecipeKeywordFields.pairing("Kraftigt rött")))
        assertNull(RecipeKeywordFields.keywordIdOf(RecipeKeywordFields.WANT_TO_COOK))

        // "Vill laga" taken back: the pairing stays queued (the bases merged, nothing cancels it).
        val takenBack = wantToCook.copy(payload = """{"keywords":{"vill-laga":false}}""", baseSnapshot = """{"keywords":{"vill-laga":true}}""")
        outbox.enqueue(takenBack)
        val after = outbox.getAllFor(ACCOUNT_A, OutboxEntities.RECIPE).single()
        assertEquals("""{"keywords":{"vill-laga":false},"keyword:41":true}""", after.payload)
        assertEquals("""{"keywords":{"vill-laga":false},"keyword:41":false}""", after.baseSnapshot)
    }

    @Test
    fun thePantryShowsEntriesWithStockSoonestFirstAndAReReadKeepsOfflineCreates() = runTest {
        pantry.upsertLocations(listOf(location(1, 1), location(-1, null, "Frys")))
        pantry.upsertEntries(
            listOf(
                entry(1, 1, 1, "persilja"),
                entry(2, 2, 1, "grädde", expires = "2000-01-16"),
                entry(3, 3, 1, "citronsaft", amount = 0.0),
                entry(-1, null, -1, "blåbär", expires = "2000-01-20"),
            ),
        )
        assertEquals(listOf("grädde", "blåbär", "persilja"), pantry.observeEntries(ACCOUNT_A).first().map { it.foodName })
        assertEquals(listOf("grädde"), pantry.observeExpiring(ACCOUNT_A, "2000-01-18").first().map { it.foodName })

        pantry.replaceServerRows(ACCOUNT_A, listOf(location(1, 1, "Kylen")), listOf(entry(9, 9, 1, "mjölk")))

        assertEquals(listOf("Frys", "Kylen"), pantry.getLocations(ACCOUNT_A).map { it.name })
        assertEquals(listOf("blåbär", "mjölk"), pantry.getEntries(ACCOUNT_A).map { it.foodName })
    }

    @Test
    fun makeNowGroupsAreReplacedAsAWhole() = runTest {
        val makeNow = database.makeNowDao()
        val first = listOf(
            MakeNowEntity(ACCOUNT_A, 1, 0, fetchedAtEpochMillis = 1),
            MakeNowEntity(ACCOUNT_A, 2, 1, fetchedAtEpochMillis = 1),
        )
        makeNow.replaceAll(ACCOUNT_A, first)
        makeNow.setMissingFoods(ACCOUNT_A, 2, """[{"id":5,"name":"grädde"}]""")
        assertEquals(listOf(0, 1), makeNow.observeAll(ACCOUNT_A).first().map { it.missingCount })

        makeNow.replaceAll(ACCOUNT_A, listOf(MakeNowEntity(ACCOUNT_A, 3, 2, fetchedAtEpochMillis = 2)))

        assertEquals(listOf(3L), makeNow.observeAll(ACCOUNT_A).first().map { it.recipeId })
    }
}
