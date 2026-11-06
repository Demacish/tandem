package casa.moller.tandem.core.sync.recipeedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.CookLogState
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.sync.book.FakeBookTandoor
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.mealplan.FakeMealPlanTandoor
import casa.moller.tandem.core.sync.shopping.FakeShoppingTandoor
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 review (fix-data): everything that names a recipe created in the app, against the stateful
 * fakes of BOTH 2.6.13 and 2.7.1. A meal, a list recipe, a book entry, "Klart!" and "Vill laga" of a
 * recipe with a temporary id wait for its CREATE and go out under the server id in the same drain;
 * a screen that still holds the old id writes under the new one; a refused CREATE takes them down
 * and brings them back.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TemporaryRecipeIdTest {
    private class Env(
        val graph: SyncTestGraph,
        val edits: FakeRecipeEditTandoor,
        val meals: FakeMealPlanTandoor,
        val books: FakeBookTandoor,
        val shopping: FakeShoppingTandoor,
        val version: String,
    ) {
        /** Bodies of every `POST /api/cook-log/`, in order. */
        val cookLogs = CopyOnWriteArrayList<JsonObject>()
        private val nextCookLog = AtomicLong(500)

        fun answerCookLogs() {
            graph.server.respond("POST", "/api/cook-log/") { request -> cookLogAnswer(request) }
        }

        /** Like Tandoor: the recorded 201 with the sent fields and a new id. */
        private fun cookLogAnswer(request: RecordedRequest): MockResponse {
            val sent = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            cookLogs += sent
            val template = Json.parseToJsonElement(checkNotNull(TandoorFixtures.exchange("fas1/cook-log-create").body)).jsonObject
            val created = JsonObject(template - "comment" + sent + ("id" to JsonPrimitive(nextCookLog.incrementAndGet())))
            return FakeTandoorServer.json(201, created.toString())
        }

        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                if (result == DrainResult.DONE && outbox().none { it.state != OutboxState.FAILED }) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${outbox()}")
        }

        suspend fun outbox() = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()

        suspend fun serverIdOf(id: Long): Long = graph.real.idRewriteDao().current(ACCOUNT_ID, OutboxEntities.RECIPE_EDIT, id)

        fun offline() {
            graph.network.online.value = false
        }

        fun online() {
            graph.network.online.value = true
        }

        fun cooked(id: Long) =
            CookLogDraft(recipeId = id, servings = 2, rating = 4, comment = "Gott", cookedAt = Instant.fromEpochSeconds(1_000))

        fun meal(id: Long) = MealPlanDraft(LocalDate(2026, 10, 20), mealTypeId = FakeMealPlanTandoor.DINNER, recipeId = id)

        fun body(call: Pair<String, String>): JsonObject = Json.parseToJsonElement(call.second).jsonObject
    }

    private fun forEachVersion(block: suspend Env.() -> Unit) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version)
            val edits = FakeRecipeEditTandoor(version)
            val meals = FakeMealPlanTandoor(version)
            val books = FakeBookTandoor(version)
            val shopping = FakeShoppingTandoor(version, graph.clock)
            graph.server.handler = { request ->
                edits.answer(request) ?: meals.answer(request) ?: books.answer(request) ?: shopping.answer(request)
            }
            try {
                runBlocking {
                    val account = graph.account
                    graph.real.accountDao().upsert(ServerAccountEntity(account.id, account.baseUrl, account.userId, account.spaceId))
                    graph.real.referenceDao().replaceMealTypes(
                        ACCOUNT_ID,
                        listOf(MealTypeEntity(ACCOUNT_ID, FakeMealPlanTandoor.DINNER, "Middag", 0, "18:00:00")),
                    )
                    Env(graph, edits, meals, books, shopping, version).apply { answerCookLogs() }.block()
                }
            } finally {
                graph.close()
            }
        }
    }

    private fun <T> ok(outcome: Outcome<T>): T {
        assertTrue("$outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    private val recipe = RecipeDraft(
        name = "Fiskgratäng",
        servings = 4,
        steps = listOf(
            DraftStep(instruction = "Grädda.", ingredients = listOf(DraftIngredient(amount = 600.0, unit = "g", food = "torsk"))),
        ),
    )

    private suspend fun Env.ingredientsOf(id: Long): Set<Long> =
        checkNotNull(graph.recipes.observeStoredRecipe(id).first().recipe).steps.flatMap { step -> step.ingredients.map { it.id } }.toSet()

    @Test
    fun everythingThatNamesANewRecipeGoesOutUnderItsServerIdInOneDrain() = forEachVersion {
        offline()
        val id = ok(graph.recipeEdits.createRecipe(recipe))
        ok(graph.mealPlans.create(meal(id)))
        ok(graph.shopping.addRecipe(RecipeShoppingRequest(id, 2.0, ingredientsOf(id))))
        val book = ok(graph.books.createBook("Vardag", ""))
        ok(graph.books.addToBook(book, id))
        ok(graph.cookLogs.logCooked(cooked(id)))
        ok(graph.books.setWantToCook(id, true))
        online()

        drainAll()

        val serverId = serverIdOf(id)
        assertTrue(serverId > 0)
        assertTrue("[$version] ${outbox()}", outbox().isEmpty())
        val planned = meals.calls.single { (call, _) -> call == "POST /api/meal-plan/" }
        assertEquals(serverId, body(planned)["recipe"]!!.jsonObject["id"]!!.jsonPrimitive.long)
        val listed = shopping.calls.single { (call, _) -> call == "POST /api/shopping-list-recipe/" }
        assertEquals(serverId, body(listed)["recipe"]!!.jsonPrimitive.long)
        val entry = shopping.calls.single { (call, _) -> call == "POST /api/shopping-list-entry/" }
        assertFalse("the server does not know a line of a recipe it had not got", "ingredient" in body(entry))
        val inBook = books.callsTo("POST", "/api/recipe-book-entry/").single()
        assertEquals(serverId, body(inBook)["recipe"]!!.jsonPrimitive.long)
        assertEquals(serverId, cookLogs.single()["recipe"]!!.jsonPrimitive.long)
        val entries = graph.real.cookLogDao().getEntries(ACCOUNT_ID).filter { it.recipeId == serverId }
        assertEquals(listOf(CookLogState.SENT), entries.map { it.state })
        val keywords = edits.recipe(serverId)!!["keywords"]!!.jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue("$keywords", WantToCook.KEYWORD in keywords)
    }

    @Test
    fun aScreenThatStillHoldsTheOldIdWritesUnderTheServerId() = forEachVersion {
        val id = ok(graph.recipeEdits.createRecipe(recipe))
        val lines = ingredientsOf(id)
        drainAll()
        val serverId = serverIdOf(id)
        assertTrue(serverId > 0)

        // Cook mode, the plan sheet and the detail were opened before the answer came.
        ok(graph.cookLogs.logCooked(cooked(id)))
        ok(graph.mealPlans.create(meal(id)))
        ok(graph.books.setWantToCook(id, true))
        ok(graph.recipes.setAvailableOffline(id, true))
        graph.recipes.markViewed(id)
        assertTrue(lines.isNotEmpty())

        assertTrue("[$version] ${outbox()}", outbox().none { (it.serverId ?: 0) < 0 })
        assertTrue(graph.real.recipeDao().getDetailRow(ACCOUNT_ID, serverId)?.pinned == true)
        drainAll()
        assertEquals(serverId, cookLogs.single()["recipe"]!!.jsonPrimitive.long)
        val planned = meals.calls.single { (call, _) -> call == "POST /api/meal-plan/" }
        assertEquals(serverId, body(planned)["recipe"]!!.jsonObject["id"]!!.jsonPrimitive.long)
        assertEquals(1, graph.server.requestsTo("POST", "/api/view-log/").size)
    }

    @Test
    fun aRecipeNotAnsweredYetHasNoViewLogAndHoldsItsRowsBack() = forEachVersion {
        val busy = AtomicBoolean(true)
        graph.server.respond("POST", "/api/recipe/") { request ->
            if (busy.get()) FakeTandoorServer.json(503, "{}") else checkNotNull(edits.answer(request))
        }
        val id = ok(graph.recipeEdits.createRecipe(recipe))
        graph.recipes.markViewed(id)
        ok(graph.cookLogs.logCooked(cooked(id)))
        ok(graph.mealPlans.create(meal(id)))

        assertEquals(DrainResult.RETRY, graph.outbox.drain(graph.remote))

        assertTrue(outbox().none { it.op == OutboxOps.VIEW_LOG })
        assertTrue("nothing goes out under the temporary id", cookLogs.isEmpty())
        assertTrue(meals.calls.none { (call, _) -> call == "POST /api/meal-plan/" })
        busy.set(false)
        drainAll()
        assertEquals(serverIdOf(id), cookLogs.single()["recipe"]!!.jsonPrimitive.long)
    }

    @Test
    fun aRefusedCreateTakesItsRowsDownAndBringsThemBack() = forEachVersion {
        val refuse = AtomicBoolean(true)
        graph.server.respond("POST", "/api/recipe/") { request ->
            if (refuse.getAndSet(false)) {
                FakeTandoorServer.json(400, """{"name":["Ensure this field has no more than 128 characters."]}""")
            } else {
                checkNotNull(edits.answer(request))
            }
        }
        val id = ok(graph.recipeEdits.createRecipe(recipe))
        ok(graph.mealPlans.create(meal(id)))
        ok(graph.cookLogs.logCooked(cooked(id)))

        drainAll()

        assertTrue("[$version] ${outbox()}", outbox().all { it.state == OutboxState.FAILED })
        assertTrue(cookLogs.isEmpty())

        val draft = checkNotNull(graph.recipeEdits.observeDraft(id).first())
        ok(graph.recipeEdits.updateRecipe(id, draft.copy(name = "Rättad")))
        drainAll()

        assertTrue("[$version] ${outbox()}", outbox().isEmpty())
        assertEquals(serverIdOf(id), cookLogs.single()["recipe"]!!.jsonPrimitive.long)
        val planned = meals.calls.single { (call, _) -> call == "POST /api/meal-plan/" }
        assertEquals(serverIdOf(id), body(planned)["recipe"]!!.jsonObject["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun deletingARefusedRecipeTakesItsRowsAndItsKlartWithIt() = forEachVersion {
        graph.server.respond("POST", "/api/recipe/") { FakeTandoorServer.json(400, """{"name":["Too long."]}""") }
        val id = ok(graph.recipeEdits.createRecipe(recipe))
        ok(graph.cookLogs.logCooked(cooked(id)))
        drainAll()

        ok(graph.recipeEdits.deleteRecipe(id))

        assertTrue("[$version] ${outbox()}", outbox().isEmpty())
        val entry = graph.real.cookLogDao().getEntries(ACCOUNT_ID).single()
        assertEquals(CookLogState.DROPPED, entry.state)
    }

    @Test
    fun anOpenScreenFollowsTheAnswer() = forEachVersion {
        val id = ok(graph.recipeEdits.createRecipe(recipe))
        coroutineScope {
            val answered = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MILLIS) { graph.recipes.observeStoredRecipe(id).first { it.recipeId > 0 && it.recipe != null } }
            }
            drainAll()
            assertEquals("Fiskgratäng", answered.await().recipe?.name)
        }
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 10
        const val TIMEOUT_MILLIS = 10_000L
    }
}
