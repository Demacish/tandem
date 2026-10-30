package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.IdMappingEntity
import casa.moller.tandem.core.database.entity.InventoryEntryEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.outbox.OutboxRefs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fas S (rester lane, Phase 2's open issue "id_mapping rensas aldrig"): `IdRewriteDao.pruneMappings`
 * removes old mappings that no outbox row and no local table can need any more, keeps the lowest
 * temporary id of each entity (so `nextLocalId` never hands one out twice) and leaves other
 * accounts alone.
 */
@RunWith(AndroidJUnit4::class)
class IdMappingPruneTest {
    private val database = newDatabase()
    private val ids = database.idRewriteDao()
    private val outbox = database.outboxDao()

    @After
    fun close() {
        database.close()
    }

    private suspend fun map(entity: String, localId: Long, createdAt: Long = OLD, account: String = ACCOUNT_A) {
        ids.upsertMapping(IdMappingEntity(account, entity, localId, SERVER_BASE - localId, createdAt))
    }

    private suspend fun mapped(entity: String, account: String = ACCOUNT_A): List<Long> =
        ids.getMappings(account).filter { it.entity == entity }.map { it.localId }

    private fun row(
        op: String,
        entity: String,
        localId: Long? = null,
        serverId: Long? = null,
        payload: String = "{}",
        refs: String? = null,
    ) = OutboxEntity(
        accountId = ACCOUNT_A,
        op = op,
        entity = entity,
        localId = localId,
        serverId = serverId,
        payload = payload,
        refs = refs,
        createdAtEpochMillis = OLD,
    )

    @Test
    fun oldUnusedMappingsGoButTheLowestOfEachEntityAndRecentOnesStay() = runTest {
        map(OutboxEntities.SHOPPING_ENTRY, -5)
        map(OutboxEntities.SHOPPING_ENTRY, -4)
        map(OutboxEntities.SHOPPING_ENTRY, -3)
        map(OutboxEntities.SHOPPING_ENTRY, -2, createdAt = RECENT)
        map(OutboxEntities.MEAL_PLAN, -7)
        map(OutboxEntities.MEAL_PLAN, -1)

        assertEquals(3, ids.pruneMappings(ACCOUNT_A, createdBefore = CUTOFF))

        assertEquals(listOf(-5L, -2L), mapped(OutboxEntities.SHOPPING_ENTRY))
        assertEquals(listOf(-7L), mapped(OutboxEntities.MEAL_PLAN))
        // The next temporary id is still below every id ever handed out.
        assertEquals(-6L, ids.nextLocalId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY))
        // Nothing left to do: a second prune removes nothing.
        assertEquals(0, ids.pruneMappings(ACCOUNT_A, createdBefore = CUTOFF))
    }

    @Test
    fun aMappingStaysWhileAnOutboxRowMayNeedIt() = runTest {
        map(OutboxEntities.RECIPE_BOOK, -9)
        // A later change of the object itself (rewritten to its server id, local id kept).
        map(OutboxEntities.RECIPE_BOOK, -4)
        outbox.insert(row(OutboxOps.PATCH, OutboxEntities.RECIPE_BOOK, localId = -4, serverId = SERVER_BASE + 4))
        // A reference in another row's payload, resolved just before sending.
        map(OutboxEntities.SHOPPING_LIST_RECIPE, -9)
        map(OutboxEntities.SHOPPING_LIST_RECIPE, -3)
        outbox.insert(
            row(
                OutboxOps.CREATE,
                OutboxEntities.SHOPPING_ENTRY,
                localId = -20,
                payload = """{"list_recipe": -3}""",
                refs = OutboxRefs.encode(mapOf("list_recipe" to OutboxEntities.SHOPPING_LIST_RECIPE)),
            ),
        )
        // A per-keyword field of RECIPE_KEYWORDS ("keyword:<id>") with a temporary id.
        map(OutboxEntities.KEYWORD, -9)
        map(OutboxEntities.KEYWORD, -6)
        outbox.insert(row(OutboxOps.RECIPE_KEYWORDS, OutboxEntities.RECIPE, serverId = 2, payload = """{"keyword:-6": true}"""))
        // Unrelated: an old alias mapping without any row goes.
        map(OutboxEntities.FOOD_ALIAS, -9)
        map(OutboxEntities.FOOD_ALIAS, -2)

        assertEquals(1, ids.pruneMappings(ACCOUNT_A, createdBefore = CUTOFF))

        assertEquals(listOf(-9L, -4L), mapped(OutboxEntities.RECIPE_BOOK))
        assertEquals(listOf(-9L, -3L), mapped(OutboxEntities.SHOPPING_LIST_RECIPE))
        assertEquals(listOf(-9L, -6L), mapped(OutboxEntities.KEYWORD))
        assertEquals(listOf(-9L), mapped(OutboxEntities.FOOD_ALIAS))
    }

    @Test
    fun aWaitingCreateKeepsEveryMappingOfItsEntity() = runTest {
        // Its lost-answer search leaves out the server ids other creates claimed (claimedServerIds).
        map(OutboxEntities.MEAL_PLAN, -8)
        map(OutboxEntities.MEAL_PLAN, -5)
        map(OutboxEntities.MEAL_PLAN, -2)
        val create = outbox.insert(row(OutboxOps.CREATE, OutboxEntities.MEAL_PLAN, localId = -11))

        assertEquals(0, ids.pruneMappings(ACCOUNT_A, createdBefore = CUTOFF))
        assertEquals(listOf(-8L, -5L, -2L), mapped(OutboxEntities.MEAL_PLAN))

        ids.deleteOutboxRow(create)
        assertEquals(2, ids.pruneMappings(ACCOUNT_A, createdBefore = CUTOFF))
        assertEquals(listOf(-8L), mapped(OutboxEntities.MEAL_PLAN))
    }

    @Test
    fun aMappingStaysWhileALocalTableStillHoldsTheTemporaryId() = runTest {
        // An entry stored with the old location id (a screen that held it after the rewrite).
        map(OutboxEntities.INVENTORY_LOCATION, -9)
        map(OutboxEntities.INVENTORY_LOCATION, -2)
        map(OutboxEntities.INVENTORY_LOCATION, -1)
        database.pantryDao().upsertEntries(
            listOf(InventoryEntryEntity(accountId = ACCOUNT_A, localId = 70, serverId = 70, locationId = -2, foodName = "grädde")),
        )
        // A child keyword whose parent is still the temporary id.
        map(OutboxEntities.KEYWORD, -9)
        map(OutboxEntities.KEYWORD, -3)
        database.drinkDraftDao().upsertKeywords(listOf(KeywordEntity(accountId = ACCOUNT_A, id = 40, name = "Rött vin", parentId = -3)))

        assertEquals(1, ids.pruneMappings(ACCOUNT_A, createdBefore = CUTOFF))

        assertEquals(listOf(-9L, -2L), mapped(OutboxEntities.INVENTORY_LOCATION))
        assertEquals(listOf(-9L, -3L), mapped(OutboxEntities.KEYWORD))
    }

    @Test
    fun onlyTheGivenAccountIsPruned() = runTest {
        map(OutboxEntities.SHOPPING_ENTRY, -9, account = ACCOUNT_B)
        map(OutboxEntities.SHOPPING_ENTRY, -2, account = ACCOUNT_B)
        map(OutboxEntities.SHOPPING_ENTRY, -9)
        map(OutboxEntities.SHOPPING_ENTRY, -2)

        assertEquals(1, ids.pruneMappings(ACCOUNT_A, createdBefore = CUTOFF))

        assertEquals(listOf(-9L), mapped(OutboxEntities.SHOPPING_ENTRY))
        assertEquals(listOf(-9L, -2L), mapped(OutboxEntities.SHOPPING_ENTRY, ACCOUNT_B))
    }

    private companion object {
        const val OLD = 1_000L
        const val CUTOFF = 5_000L
        const val RECENT = 9_000L
        const val SERVER_BASE = 500L
    }
}
