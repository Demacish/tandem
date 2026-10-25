package casa.moller.tandem.core.database

import android.content.Context
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.SupermarketCategoryEntity
import casa.moller.tandem.core.database.entity.SupermarketCategoryRelationEntity
import casa.moller.tandem.core.database.entity.SupermarketEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shopping list's DAO queries (Phase 2, inköp-data lane): a local change and its outbox row
 * are stored in ONE transaction (or not at all), the pending count, foods' aisles and "har hemma",
 * a store's aisle order, the polling floor, and a 0.2.0 database migrated to schema 4 keeps its
 * queue while the shopping list works beside it.
 */
@RunWith(AndroidJUnit4::class)
class ShoppingDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = newDatabase()
    private val shopping = database.shoppingDao()
    private val outbox = database.outboxDao()
    private val stores = database.supermarketDao()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = context.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = TandemDatabase::class,
    )

    @Before
    fun deleteFile() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    private fun entry(localId: Long, food: String, serverId: Long? = localId.takeIf { it > 0 }, foodId: Long? = null) =
        ShoppingEntryEntity(accountId = ACCOUNT_A, localId = localId, serverId = serverId, foodName = food, foodId = foodId)

    private fun create(localId: Long) = OutboxEntity(
        accountId = ACCOUNT_A,
        op = OutboxOps.CREATE,
        entity = OutboxEntities.SHOPPING_ENTRY,
        localId = localId,
        payload = """{"food":{"name":"mjölk"},"amount":1}""",
        createdAtEpochMillis = 1,
    )

    @Test
    fun aLocalChangeAndItsOutboxRowAreStoredTogetherOrNotAtAll() = runTest {
        shopping.inTransaction {
            val localId = database.idRewriteDao().nextLocalId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY)
            shopping.upsertEntries(listOf(entry(localId, "mjölk")))
            outbox.enqueue(create(localId))
        }
        assertEquals(listOf(-1L), shopping.getEntries(ACCOUNT_A).map { it.localId })
        assertEquals(1, outbox.observeCount(ACCOUNT_A).first())

        val failed = runCatching {
            shopping.inTransaction {
                shopping.upsertEntries(listOf(entry(-2, "bröd")))
                outbox.enqueue(create(-2))
                error("the app died half-way")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals("rolled back together", listOf(-1L), shopping.getEntries(ACCOUNT_A).map { it.localId })
        assertEquals(1, outbox.observeCount(ACCOUNT_A).first())
        // The next temporary id is still new (never -1 again).
        assertEquals(-2L, database.idRewriteDao().nextLocalId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY))
    }

    @Test
    fun thePendingCountIsTheShoppingRowsOnly() = runTest {
        fun row(account: String, op: String, entity: String, serverId: Long) =
            OutboxEntity(accountId = account, op = op, entity = entity, serverId = serverId, createdAtEpochMillis = 1)
        outbox.insert(create(-1))
        outbox.insert(row(ACCOUNT_A, OutboxOps.PATCH, OutboxEntities.FOOD, 3))
        outbox.insert(row(ACCOUNT_A, OutboxOps.PATCH, OutboxEntities.SUPERMARKET_CATEGORY_RELATION, 4))
        outbox.insert(row(ACCOUNT_A, OutboxOps.COOK_LOG, OutboxEntities.RECIPE, 2))
        outbox.insert(row(ACCOUNT_B, OutboxOps.PATCH, OutboxEntities.FOOD, 3))
        assertEquals(3, shopping.observePendingCount(ACCOUNT_A).first())
        assertEquals(listOf(OutboxEntities.SHOPPING_ENTRY), shopping.observeOutbox(ACCOUNT_A).first().map { it.entity })
    }

    @Test
    fun foodsAislesAndTheAisleOrderChangeHere() = runTest {
        database.referenceDao().replaceFoods(ACCOUNT_A, listOf(FoodEntity(accountId = ACCOUNT_A, id = 7, name = "grädde")))
        shopping.upsertEntries(listOf(entry(1, "grädde", foodId = 7), entry(2, "tandkräm", foodId = 8)))
        stores.replaceAll(
            ACCOUNT_A,
            supermarkets = listOf(SupermarketEntity(accountId = ACCOUNT_A, id = 1, name = "Butiken")),
            categories = listOf(
                SupermarketCategoryEntity(accountId = ACCOUNT_A, id = 1, name = "Mejeri"),
                SupermarketCategoryEntity(accountId = ACCOUNT_A, id = 2, name = "Hushåll"),
            ),
            relations = listOf(
                SupermarketCategoryRelationEntity(accountId = ACCOUNT_A, id = 10, supermarketId = 1, categoryId = 1, relationOrder = 0),
                SupermarketCategoryRelationEntity(accountId = ACCOUNT_A, id = 11, supermarketId = 1, categoryId = 2, relationOrder = 1),
            ),
        )

        stores.setFoodCategory(ACCOUNT_A, 7, 1)
        shopping.setCategoryOfFood(ACCOUNT_A, 7, 1)
        stores.setFoodOnHand(ACCOUNT_A, 7, true)
        assertEquals(1L, stores.getFood(ACCOUNT_A, 7)?.supermarketCategoryId)
        assertTrue(stores.observeFoods(ACCOUNT_A).first().single().onHand)
        assertEquals(mapOf(1L to 1L, 2L to null), shopping.getEntries(ACCOUNT_A).associate { it.localId to it.categoryId })

        stores.setRelationOrder(ACCOUNT_A, 10, 5)
        assertEquals(listOf(11L, 10L), stores.getRelations(ACCOUNT_A, 1).map { it.id })
        assertEquals(listOf(11L, 10L), stores.observeAllRelations(ACCOUNT_A).first().map { it.id })
        assertEquals("Hushåll", stores.getCategory(ACCOUNT_A, 2)?.name)
    }

    @Test
    fun thePollingFloorIsStoredWithTheAccount() = runTest {
        assertNull(shopping.getMinAutosyncSeconds(ACCOUNT_A))
        database.accountDao().upsert(ServerAccountEntity(id = ACCOUNT_A, baseUrl = "https://tandoor.example.com/", userId = 1, spaceId = 1))
        shopping.setMinAutosyncSeconds(ACCOUNT_A, 5)
        assertEquals(5, shopping.getMinAutosyncSeconds(ACCOUNT_A))
        shopping.setMinAutosyncSeconds(ACCOUNT_A, null)
        assertNull(shopping.getMinAutosyncSeconds(ACCOUNT_A))
    }

    /** A 0.2.0 database (schema 3) with a queued "Klart!": after the update the list works and the old row goes first. */
    @Test
    fun aMigratedDatabaseKeepsItsQueueBesideTheShoppingList() = runTest {
        helper.createDatabase(3).apply {
            execSQL(
                "INSERT INTO outbox (account_id, op, entity, server_id, payload, attempts, created_at, state) " +
                    "VALUES ('$ACCOUNT_A', 'COOK_LOG', 'recipe', 2, '{}', 0, 1, 'PENDING')",
            )
            execSQL("INSERT INTO food (account_id, id, name, on_hand, ignore_shopping) VALUES ('$ACCOUNT_A', 7, 'grädde', 0, 0)")
            close()
        }
        helper.runMigrationsAndValidate(4, TandemMigrations.ALL).close()

        val migrated = TandemDatabase.build(context, TEST_DB)
        try {
            migrated.shoppingDao().inTransaction {
                val localId = migrated.idRewriteDao().nextLocalId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY)
                migrated.shoppingDao().upsertEntries(listOf(entry(localId, "grädde", foodId = 7)))
                migrated.outboxDao().enqueue(create(localId))
            }
            assertEquals("COOK_LOG", migrated.outboxDao().nextReady(ACCOUNT_A)?.op)
            assertEquals(2, migrated.outboxDao().observeCount(ACCOUNT_A).first())
            assertEquals(1, migrated.shoppingDao().observePendingCount(ACCOUNT_A).first())
            assertEquals("grädde", migrated.supermarketDao().getFood(ACCOUNT_A, 7)?.name)
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "shopping-migration-test.db"
    }
}
