package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Outbox ordering, `depends_on`, failure cascade, VIEW_LOG coalescing and safe-mode pausing (plan 3.5, 5.7). */
@RunWith(AndroidJUnit4::class)
class OutboxDaoTest {
    private val database = newDatabase()
    private val outbox = database.outboxDao()

    @After
    fun close() {
        database.close()
    }

    private suspend fun add(
        entity: String = OutboxEntities.MEAL_PLAN,
        serverId: Long? = null,
        localId: Long? = null,
        dependsOn: Long? = null,
        accountId: String = ACCOUNT_A,
        op: String = OutboxOps.PATCH,
    ): Long = outbox.insert(
        OutboxEntity(
            accountId = accountId,
            op = op,
            entity = entity,
            serverId = serverId,
            localId = localId,
            dependsOn = dependsOn,
            createdAtEpochMillis = 1,
        ),
    )

    @Test
    fun rowsAreSentInIdOrderAndOneAtATimePerObject() = runTest {
        val first = add(serverId = 5)
        val second = add(serverId = 5)
        val other = add(entity = OutboxEntities.SHOPPING_ENTRY, serverId = 5)

        assertEquals(first, outbox.nextReady(ACCOUNT_A)?.id)
        assertEquals(1, outbox.markInFlight(first))
        assertEquals(0, outbox.markInFlight(first))
        // `second` waits for `first` (same meal plan entry); the shopping entry 5 is another object.
        assertEquals(other, outbox.nextReady(ACCOUNT_A)?.id)
        outbox.markInFlight(other)
        assertNull(outbox.nextReady(ACCOUNT_A))

        outbox.markDone(first)
        assertEquals(second, outbox.nextReady(ACCOUNT_A)?.id)
        assertEquals(1, checkNotNull(outbox.get(other)).attempts)
    }

    @Test
    fun aRowWaitsForItsDependency() = runTest {
        val create = add(entity = OutboxEntities.SHOPPING_ENTRY, localId = -7, op = OutboxOps.CREATE)
        val patch = add(serverId = 9, dependsOn = create)

        assertEquals(create, outbox.nextReady(ACCOUNT_A)?.id)
        outbox.markInFlight(create)
        assertNull(outbox.nextReady(ACCOUNT_A))
        outbox.markRetry(create, "timeout")
        assertEquals(create, outbox.nextReady(ACCOUNT_A)?.id)
        assertEquals("timeout", outbox.get(create)?.lastError)

        outbox.rewriteServerId(ACCOUNT_A, OutboxEntities.SHOPPING_ENTRY, localId = -7, serverId = 70)
        outbox.markDone(create)
        assertEquals(patch, outbox.nextReady(ACCOUNT_A)?.id)
    }

    @Test
    fun aFailedLogRowDoesNotHoldBackLaterRowsOfTheRecipe() = runTest {
        val rejected = add(entity = OutboxEntities.RECIPE, serverId = 2, op = OutboxOps.COOK_LOG)
        val edit = add(entity = OutboxEntities.RECIPE, serverId = 2, op = OutboxOps.PATCH)
        outbox.markInFlight(rejected)
        outbox.markFailed(rejected, "http-400")

        assertEquals(edit, outbox.nextReady(ACCOUNT_A)?.id)
        outbox.markInFlight(edit)
        outbox.markFailed(edit, "http-400")
        val next = add(entity = OutboxEntities.RECIPE, serverId = 2, op = OutboxOps.COOK_LOG)
        assertNull("a failed edit still holds the recipe's later rows", outbox.nextReady(ACCOUNT_A))
        outbox.markDone(edit)
        assertEquals(next, outbox.nextReady(ACCOUNT_A)?.id)
    }

    @Test
    fun aPermanentFailureFailsEveryDependentRow() = runTest {
        val create = add(entity = OutboxEntities.SHOPPING_ENTRY, localId = -7, op = OutboxOps.CREATE)
        val check = add(entity = OutboxEntities.SHOPPING_ENTRY, localId = -7, dependsOn = create, op = OutboxOps.BULK_CHECK)
        val plan = add(serverId = 3, dependsOn = check)
        val unrelated = add(serverId = 4)
        outbox.markInFlight(create)

        assertEquals(listOf(create, check, plan), outbox.markFailed(create, "400"))
        assertEquals(listOf(create, check, plan), outbox.getByState(ACCOUNT_A, OutboxState.FAILED).map { it.id })
        assertEquals(unrelated, outbox.nextReady(ACCOUNT_A)?.id)
        outbox.markDone(unrelated)
        assertNull(outbox.nextReady(ACCOUNT_A))

        outbox.retryFailed(create)
        assertEquals(create, outbox.nextReady(ACCOUNT_A)?.id)
        assertNull(outbox.get(create)?.lastError)
    }

    @Test
    fun viewLogsOfTheSameRecipeAreCoalesced() = runTest {
        outbox.enqueueViewLog(ACCOUNT_A, recipeId = 1, payload = """{"recipe":1}""", nowEpochMillis = 10)
        outbox.enqueueViewLog(ACCOUNT_A, recipeId = 1, payload = """{"recipe":1}""", nowEpochMillis = 20)
        outbox.enqueueViewLog(ACCOUNT_A, recipeId = 2, payload = """{"recipe":2}""", nowEpochMillis = 30)
        val last = outbox.enqueueViewLog(ACCOUNT_A, recipeId = 1, payload = """{"recipe":1}""", nowEpochMillis = 40)

        val rows = outbox.observeAll(ACCOUNT_A).first()
        assertEquals(listOf(2L, 1L), rows.map { it.serverId })
        assertEquals(listOf(30L, 40L), rows.map { it.createdAtEpochMillis })
        assertEquals(last, rows.last().id)
        assertEquals(2, outbox.observeCount(ACCOUNT_A).first())

        // A view log that is being sent is not touched; the new one queues behind it.
        outbox.markInFlight(rows.first().id)
        outbox.enqueueViewLog(ACCOUNT_A, recipeId = 2, payload = """{"recipe":2}""", nowEpochMillis = 50)
        val recipeTwo = outbox.observeAll(ACCOUNT_A).first().filter { it.serverId == 2L }
        assertEquals(listOf(OutboxState.IN_FLIGHT, OutboxState.PENDING), recipeTwo.map { it.state })
    }

    @Test
    fun safeModePausesAndResumes() = runTest {
        val row = add(serverId = 1)
        val sending = add(serverId = 2)
        outbox.markInFlight(sending)

        outbox.pauseAll(ACCOUNT_A)
        assertNull(outbox.nextReady(ACCOUNT_A))
        assertEquals(2, outbox.observeCount(ACCOUNT_A).first())

        outbox.resumeAll(ACCOUNT_A)
        assertEquals(row, outbox.nextReady(ACCOUNT_A)?.id)

        // After a crash the in-flight row is sent again.
        outbox.resetInFlight(ACCOUNT_A)
        assertEquals(OutboxState.PENDING, outbox.get(sending)?.state)
    }

    @Test
    fun accountsHaveSeparateQueues() = runTest {
        val rowB = add(serverId = 1, accountId = ACCOUNT_B)
        assertNull(outbox.nextReady(ACCOUNT_A))
        val rowA = add(serverId = 1)
        // Same object id in another account does not block.
        assertEquals(rowA, outbox.nextReady(ACCOUNT_A)?.id)
        assertEquals(rowB, outbox.nextReady(ACCOUNT_B)?.id)
        outbox.pauseAll(ACCOUNT_A)
        assertEquals(rowB, outbox.nextReady(ACCOUNT_B)?.id)
    }
}
