package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.ShoppingListRecipeEntity
import casa.moller.tandem.core.database.outbox.OutboxRefs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Phase 2 outbox contract (plan 5.7, docs/BYGGE.md "Fas 2 – kontrakt"): merging on enqueue,
 * batches, temporary ids that are never reused, and the id rewrite after a CREATE, including the
 * plan's example (create offline, check offline, the check is sent after the create with the
 * server id).
 */
@RunWith(AndroidJUnit4::class)
class OutboxPhase2Test {
    private val database = newDatabase()
    private val outbox = database.outboxDao()
    private val ids = database.idRewriteDao()
    private val shopping = database.shoppingDao()

    @After
    fun close() {
        database.close()
    }

    private fun row(
        op: String,
        localId: Long? = null,
        serverId: Long? = null,
        payload: String = "{}",
        base: String? = null,
        entity: String = OutboxEntities.SHOPPING_ENTRY,
        dependsOn: Long? = null,
        refs: String? = null,
        batchKey: String? = null,
    ) = OutboxEntity(
        accountId = ACCOUNT_A,
        op = op,
        entity = entity,
        localId = localId,
        serverId = serverId,
        payload = payload,
        baseSnapshot = base,
        dependsOn = dependsOn,
        refs = refs,
        batchKey = batchKey,
        createdAtEpochMillis = 1,
    )

    private fun entry(localId: Long, serverId: Long?, listRecipe: Long? = null) = ShoppingEntryEntity(
        accountId = ACCOUNT_A,
        localId = localId,
        serverId = serverId,
        foodName = "mjölk",
        amount = 2.0,
        listRecipeId = listRecipe,
    )

    @Test
    fun aPatchOfAnUnsentCreateBecomesPartOfTheCreate() = runTest {
        val create = outbox.enqueue(row(OutboxOps.CREATE, localId = -1, payload = """{"food":{"name":"mjölk"},"amount":1}"""))
        val patch = outbox.enqueue(row(OutboxOps.PATCH, localId = -1, payload = """{"amount":2}"""))

        assertEquals(create, patch)
        val merged = checkNotNull(outbox.get(checkNotNull(create)))
        assertEquals("""{"food":{"name":"mjölk"},"amount":2}""", merged.payload)
        assertEquals(1, outbox.getAllFor(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY).size)
    }

    @Test
    fun twoPatchesMergeAndKeepTheFirstBase() = runTest {
        val first = outbox.enqueue(
            row(OutboxOps.PATCH, serverId = 7, payload = """{"title":"A"}""", base = """{"title":"X"}""", entity = MEAL),
        )
        val second = outbox.enqueue(
            row(OutboxOps.PATCH, serverId = 7, payload = """{"servings":3}""", base = """{"servings":2}""", entity = MEAL),
        )

        assertEquals(first, second)
        val merged = checkNotNull(outbox.get(checkNotNull(first)))
        assertEquals("""{"title":"A","servings":3}""", merged.payload)
        assertEquals("""{"title":"X"}""", merged.baseSnapshot)
    }

    @Test
    fun checkThenUncheckCancelsOut() = runTest {
        val check = outbox.enqueue(
            row(OutboxOps.BULK_CHECK, serverId = 5, payload = """{"checked":true}""", base = """{"checked":false}""", batchKey = CHECKED),
        )
        assertNotNull(check)
        val uncheck = outbox.enqueue(
            row(OutboxOps.BULK_CHECK, serverId = 5, payload = """{"checked":false}""", base = """{"checked":true}""", batchKey = UNCHECKED),
        )

        assertNull(uncheck)
        assertNull(outbox.nextReady(ACCOUNT_A))
    }

    @Test
    fun uncheckThenCheckCancelsOutToo() = runTest {
        val first = outbox.enqueue(
            row(OutboxOps.BULK_CHECK, serverId = 5, payload = """{"checked":false}""", base = """{"checked":true}"""),
        )
        val second = outbox.enqueue(row(OutboxOps.BULK_CHECK, serverId = 5, payload = """{"checked":true}""", batchKey = CHECKED))

        assertNull("checked again = back to the base: nothing to send", second)
        assertNull(first?.let { outbox.get(it) })
    }

    @Test
    fun checkingAnotherTimeKeepsTheLastValueAndItsBatch() = runTest {
        val first = outbox.enqueue(
            row(OutboxOps.BULK_CHECK, serverId = 6, payload = """{"checked":true}""", base = """{"checked":false}""", batchKey = CHECKED),
        )
        // A second tap on another device's state: same value again, the row stays.
        val second = outbox.enqueue(row(OutboxOps.BULK_CHECK, serverId = 6, payload = """{"checked":true}""", batchKey = CHECKED))

        assertEquals(first, second)
        assertEquals(CHECKED, second?.let { outbox.get(it) }?.batchKey)
    }

    @Test
    fun deletingSomethingThatNeverReachedTheServerRemovesEverythingQueuedForIt() = runTest {
        val listRecipe = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, localId = -3, entity = OutboxEntities.SHOPPING_LIST_RECIPE)))
        val entry = checkNotNull(
            outbox.enqueue(
                row(
                    OutboxOps.CREATE,
                    localId = -1,
                    payload = """{"list_recipe":-3}""",
                    dependsOn = listRecipe,
                    refs = OutboxRefs.encode(mapOf("list_recipe" to OutboxEntities.SHOPPING_LIST_RECIPE)),
                ),
            ),
        )
        outbox.enqueue(row(OutboxOps.BULK_CHECK, localId = -1, payload = """{"checked":true}"""))
        val other = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, localId = -2)))

        assertNull(outbox.enqueue(row(OutboxOps.DELETE, localId = -3, entity = OutboxEntities.SHOPPING_LIST_RECIPE)))

        assertNull(outbox.get(listRecipe))
        assertNull("the entry depended on the list recipe", outbox.get(entry))
        assertEquals(listOf(other), outbox.getAllFor(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY).map { it.id })
    }

    @Test
    fun aDeleteReplacesQueuedChangesOfAServerObject() = runTest {
        outbox.enqueue(row(OutboxOps.PATCH, serverId = 9, payload = """{"amount":3}"""))
        outbox.enqueue(row(OutboxOps.BULK_CHECK, serverId = 9, payload = """{"checked":true}""", base = """{"checked":false}"""))
        val delete = outbox.enqueue(row(OutboxOps.DELETE, serverId = 9))
        val again = outbox.enqueue(row(OutboxOps.DELETE, serverId = 9))

        assertEquals(delete, again)
        assertEquals(listOf(OutboxOps.DELETE), outbox.getAllFor(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY).map { it.op })
    }

    @Test
    fun readyChecksOfTheSameValueGoTogether() = runTest {
        val a = outbox.enqueue(row(OutboxOps.BULK_CHECK, serverId = 1, payload = """{"checked":true}""", batchKey = CHECKED))
        val b = outbox.enqueue(row(OutboxOps.BULK_CHECK, serverId = 2, payload = """{"checked":true}""", batchKey = CHECKED))
        outbox.enqueue(row(OutboxOps.BULK_CHECK, serverId = 3, payload = """{"checked":false}""", batchKey = UNCHECKED))
        // An entry still waiting for an older change of itself is not ready.
        outbox.enqueue(row(OutboxOps.PATCH, serverId = 4, payload = """{"amount":1}"""))
        outbox.enqueue(row(OutboxOps.BULK_CHECK, serverId = 4, payload = """{"checked":true}""", batchKey = CHECKED))

        assertEquals(listOf(a, b), outbox.nextReadyBatch(ACCOUNT_A, CHECKED, limit = 50).map { it.id })
        assertEquals(listOf(a), outbox.nextReadyBatch(ACCOUNT_A, CHECKED, limit = 1).map { it.id })
    }

    /** Plan 5.7's example: create offline (-7), check offline, the CREATE is answered with 70. */
    @Test
    fun theServerIdIsWrittenEverywhereInOneTransaction() = runTest {
        shopping.upsertListRecipes(listOf(ShoppingListRecipeEntity(ACCOUNT_A, localId = -3, serverId = null, name = "Gratäng")))
        shopping.upsertEntries(listOf(entry(-7, null, listRecipe = -3)))
        val createList = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, localId = -3, entity = OutboxEntities.SHOPPING_LIST_RECIPE)))
        val refs = OutboxRefs.encode(mapOf("list_recipe" to OutboxEntities.SHOPPING_LIST_RECIPE))
        val createEntry = checkNotNull(
            outbox.enqueue(row(OutboxOps.CREATE, localId = -7, payload = """{"list_recipe":-3}""", dependsOn = createList, refs = refs)),
        )
        // Checking it offline merges into the unsent CREATE.
        assertEquals(createEntry, outbox.enqueue(row(OutboxOps.BULK_CHECK, localId = -7, payload = """{"checked":true}""")))
        val dependent = checkNotNull(
            outbox.enqueue(
                row(
                    OutboxOps.PATCH,
                    serverId = 99,
                    entity = MEAL,
                    payload = """{"x":-7}""",
                    dependsOn = createEntry,
                    refs = """{"x":"shopping_entry"}""",
                ),
            ),
        )

        assertEquals(createList, outbox.nextReady(ACCOUNT_A)?.id)
        ids.completeCreate(ACCOUNT_A, createList, OutboxEntities.SHOPPING_LIST_RECIPE, localId = -3, serverId = 30, nowEpochMillis = 2)

        val ready = checkNotNull(outbox.nextReady(ACCOUNT_A))
        assertEquals(createEntry, ready.id)
        assertEquals(30L, ids.resolve(ACCOUNT_A, OutboxEntities.SHOPPING_LIST_RECIPE, -3))
        val mapping = ids.getMappings(ACCOUNT_A).associate { (it.entity to it.localId) to it.serverId }
        assertEquals(
            """{"list_recipe":30,"checked":true}""",
            OutboxRefs.resolve(ready.payload, ready.refs) { e, local -> mapping[e to local] },
        )
        assertEquals("the entry points at the list recipe's server id", 30L, shopping.getEntry(ACCOUNT_A, -7)?.listRecipeId)

        ids.completeCreate(ACCOUNT_A, createEntry, OutboxEntities.SHOPPING_ENTRY, localId = -7, serverId = 70, nowEpochMillis = 3)

        assertNull(shopping.getEntry(ACCOUNT_A, -7))
        val rekeyed = checkNotNull(shopping.getEntry(ACCOUNT_A, 70))
        assertEquals(70L, rekeyed.serverId)
        assertEquals(30L, rekeyed.listRecipeId)
        assertEquals(dependent, outbox.nextReady(ACCOUNT_A)?.id)
        assertEquals(70L, ids.current(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY, -7))
        assertEquals(12L, ids.current(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY, 12))
    }

    @Test
    fun aSyncCopyOfTheSameServerRowGivesWayToTheLocalOne() = runTest {
        shopping.upsertEntries(listOf(entry(-7, null), entry(70, 70).copy(foodName = "mjölk (från servern)")))
        val create = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, localId = -7)))

        ids.completeCreate(ACCOUNT_A, create, OutboxEntities.SHOPPING_ENTRY, localId = -7, serverId = 70, nowEpochMillis = 2)

        assertEquals(listOf("mjölk"), shopping.getEntries(ACCOUNT_A).map { it.foodName })
        assertEquals(listOf(70L), shopping.getEntries(ACCOUNT_A).map { it.localId })
    }

    @Test
    fun temporaryIdsAreNeverReused() = runTest {
        assertEquals(-1L, ids.nextLocalId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY))
        shopping.upsertEntries(listOf(entry(-1, null)))
        assertEquals(-2L, ids.nextLocalId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY))
        val create = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, localId = -1)))
        ids.completeCreate(ACCOUNT_A, create, OutboxEntities.SHOPPING_ENTRY, localId = -1, serverId = 10, nowEpochMillis = 2)

        // -1 is gone from the table and the outbox, but a screen may still hold it.
        assertEquals(-2L, ids.nextLocalId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY))
        assertEquals(-1L, ids.nextLocalId(ACCOUNT_B, OutboxEntities.SHOPPING_ENTRY))
        assertEquals(-1L, ids.nextLocalId(ACCOUNT_A, OutboxEntities.MEAL_TYPE))
    }

    @Test
    fun aFailedCreateFailsWhatDependsOnIt() = runTest {
        val create = checkNotNull(outbox.enqueue(row(OutboxOps.CREATE, localId = -1, entity = OutboxEntities.RECIPE_BOOK)))
        val entry = checkNotNull(
            outbox.enqueue(row(OutboxOps.CREATE, localId = -2, entity = OutboxEntities.RECIPE_BOOK_ENTRY, dependsOn = create)),
        )

        outbox.markFailed(create, "400")

        assertEquals(OutboxState.FAILED, outbox.get(entry)?.state)
        assertNull(outbox.nextReady(ACCOUNT_A))
    }

    @Test
    fun payloadReferencesResolveNestedFieldsAndArrays() {
        val refs = OutboxRefs.encode(mapOf("meal_type.id" to "meal_type", "ids" to "shopping_entry"))
        val payload = """{"meal_type":{"id":-2,"name":"Frukost"},"ids":[5,-7],"servings":-1}"""
        val mapped = mapOf("meal_type" to -2L to 3L, "shopping_entry" to -7L to 70L)

        assertEquals(
            """{"meal_type":{"id":3,"name":"Frukost"},"ids":[5,70],"servings":-1}""",
            OutboxRefs.resolve(payload, refs) { entity, local -> mapped[entity to local] },
        )
        assertNull(OutboxRefs.resolve(payload, refs) { _, _ -> null })
        assertEquals(payload, OutboxRefs.resolve(payload, null) { _, _ -> null })
    }

    private companion object {
        const val MEAL = OutboxEntities.MEAL_PLAN
        const val CHECKED = "shopping_entry:checked=true"
        const val UNCHECKED = "shopping_entry:checked=false"
    }
}
