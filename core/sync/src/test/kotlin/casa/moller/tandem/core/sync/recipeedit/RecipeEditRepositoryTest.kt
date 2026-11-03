package casa.moller.tandem.core.sync.recipeedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * The recipe editor's writes (#22, plan §8.9) against a stateful Tandoor answering in the recorded
 * shapes of BOTH 2.6.13 and 2.7.1 ([FakeRecipeEditTandoor]): a recipe created offline (in the list
 * at once, POSTed with foods and units by name, its temporary id rewritten), an edit PATCHed with
 * the step and ingredient ids (an omitted step is deleted, the stored copy is not overwritten by a
 * sync meanwhile), a conflict held back with the server's version shown and the edit kept, a
 * delete, the photo, guests and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecipeEditRepositoryTest {
    private class Env(val graph: SyncTestGraph, val tandoor: FakeRecipeEditTandoor, val version: String) {
        val repo = graph.recipeEdits

        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                if (result == DrainResult.DONE && outbox().none { it.state != OutboxState.FAILED }) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${outbox()}")
        }

        suspend fun outbox() = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()

        suspend fun storedNames() = graph.recipes.observeRecipes(RecipeQuery()).first().map { it.name }
    }

    private fun forEachVersion(block: suspend Env.() -> Unit) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version)
            val tandoor = FakeRecipeEditTandoor(version).also { it.install(graph.server) }
            try {
                runBlocking {
                    val account = graph.account
                    graph.real.accountDao().upsert(ServerAccountEntity(account.id, account.baseUrl, account.userId, account.spaceId))
                    assertTrue(graph.engine.run(SyncReason.FIRST_SYNC).error == null)
                    Env(graph, tandoor, version).block()
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

    private fun failure(outcome: Outcome<*>) = (outcome as? Outcome.Failure)?.error

    private fun newRecipe(name: String = "Fiskgratäng", photo: File? = null) = RecipeDraft(
        name = name,
        description = "Vardagsmat.",
        servings = 4,
        servingsText = "portioner",
        workingMinutes = 20,
        waitingMinutes = 30,
        keywords = listOf(DraftKeyword(id = 5, name = "Husmanskost"), DraftKeyword(name = "Fisk")),
        imageUri = photo?.let { "file://${it.path}" },
        steps = listOf(
            DraftStep(
                name = "Förbered",
                instruction = "Skär {{ ingredients[0] }} i bitar.",
                timeMinutes = 10,
                ingredients = listOf(
                    DraftIngredient(amount = 600.0, unit = "g", food = "torskfilé", note = "tinad"),
                    DraftIngredient(isHeader = true, note = "Sås"),
                    DraftIngredient(amount = 2.5, unit = "dl", food = "vispgrädde"),
                    DraftIngredient(food = "salt", noAmount = true),
                ),
            ),
            DraftStep(instruction = "Grädda i 200 grader.", timeMinutes = 20),
            DraftStep(),
        ),
    )

    // ------------------------------------------------------------------ create

    @Test
    fun aRecipeCreatedOfflineIsInTheListAtOnceAndPostedWithFoodsAndUnitsByName() = forEachVersion {
        graph.network.online.value = false
        val id = ok(repo.createRecipe(newRecipe()))
        assertTrue("temporary id", id < 0)
        assertTrue("Fiskgratäng" in storedNames())
        val stored = checkNotNull(graph.recipes.observeStoredRecipe(id).first().recipe)
        assertEquals(2, stored.steps.size)
        assertEquals(listOf("torskfilé", null, "vispgrädde", "salt"), stored.steps[0].ingredients.map { it.food?.name })
        assertEquals(listOf("Husmanskost", "Fisk"), stored.keywords.map { it.name })
        assertEquals(RecipeEditStatus.QUEUED, repo.observeEditStatus(id).first())
        val draft = checkNotNull(repo.observeDraft(id).first())
        assertEquals("Fiskgratäng", draft.name)
        assertEquals(listOf("Husmanskost", "Fisk"), draft.keywords.map { it.name })
        assertEquals(listOf("torskfilé", null, "vispgrädde", "salt"), draft.steps[0].ingredients.map { it.food })

        graph.network.online.value = true
        drainAll()
        val post = tandoor.callsTo("POST", "/api/recipe/").single().second
        val body = Json.parseToJsonElement(post).jsonObject
        assertEquals("[$version] internal like the web", "true", body["internal"]?.jsonPrimitive?.content)
        val steps = body["steps"]!!.jsonArray
        assertEquals("the empty step is dropped", 2, steps.size)
        val first = steps[0].jsonObject["ingredients"]!!.jsonArray
        assertEquals("""{"name":"torskfilé"}""", first[0].jsonObject["food"].toString())
        assertEquals("""{"name":"g"}""", first[0].jsonObject["unit"].toString())
        assertEquals("true", first[1].jsonObject["is_header"]?.jsonPrimitive?.content)
        assertEquals("true", first[3].jsonObject["no_amount"]?.jsonPrimitive?.content)
        val keywords = body["keywords"]!!.jsonArray.map { it.toString() }
        assertEquals(listOf("""{"id":5}""", """{"name":"Fisk"}"""), keywords)

        val server = checkNotNull(tandoor.recipeNamed("Fiskgratäng"))
        val serverId = server["id"]!!.jsonPrimitive.long
        assertTrue("the temporary rows are gone", graph.real.recipeDao().getListItems(ACCOUNT_ID, listOf(id)).isEmpty())
        assertEquals(serverId, graph.real.idRewriteDao().resolve(ACCOUNT_ID, OutboxEntities.RECIPE_EDIT, id))
        // A screen still holding the temporary id sees the recipe under its server id.
        assertEquals(serverId, graph.recipes.observeStoredRecipe(id).first().recipe?.id)
        assertEquals(RecipeEditStatus.NONE, repo.observeEditStatus(id).first())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aPhotoIsUploadedAfterTheRecipeAndItsCopyDeleted() = forEachVersion {
        val photo = File(graph.context.cacheDir, "recipe-photo-$version.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val id = ok(repo.createRecipe(newRecipe(photo = photo)))
        val rows = outbox()
        assertEquals(listOf(OutboxOps.CREATE, OutboxOps.RECIPE_IMAGE), rows.map { it.op })
        assertEquals(rows[0].id, rows[1].dependsOn)
        assertTrue(graph.recipes.observeStoredRecipe(id).first().recipe?.imageUrl?.startsWith("file://") == true)
        drainAll()
        val calls = tandoor.calls.map { it.first }
        val posted = calls.indexOfFirst { it.startsWith("POST /api/recipe/") }
        val uploaded = calls.indexOfFirst { it.startsWith("PUT /api/recipe/") }
        assertTrue("$calls", posted in 0 until uploaded)
        assertEquals(1, graph.drinkImages.deleted.size)
        val serverId = tandoor.recipeNamed("Fiskgratäng")!!["id"]!!.jsonPrimitive.long
        assertTrue(graph.recipes.observeStoredRecipe(serverId).first().recipe?.imageUrl?.contains("recipe_$serverId.jpg") == true)
    }

    @Test
    fun aMissingPhotoIsRefusedAndNothingIsSaved() = forEachVersion {
        val missing = File(graph.context.cacheDir, "not-there-${System.nanoTime()}.jpg")
        val result = repo.createRecipe(newRecipe(photo = missing))
        assertEquals(RecipeEditProblem.PHOTO_UNREADABLE.toError(), failure(result))
        assertFalse("Fiskgratäng" in storedNames())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aNameIsRequired() = forEachVersion {
        assertEquals(RecipeEditProblem.NAME_EMPTY.toError(), failure(repo.createRecipe(RecipeDraft(name = "  "))))
        assertEquals(RecipeEditProblem.UNKNOWN_RECIPE.toError(), failure(repo.updateRecipe(NOT_STORED, newRecipe())))
    }

    // ------------------------------------------------------------------ edit

    @Test
    fun anEditIsPatchedWithTheStepAndIngredientIdsAndAnOmittedStepIsDeleted() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        assertEquals(listOf(1L, 2L, 3L), draft.steps.map { it.id })
        val edited = draft.copy(
            name = "Krämig svamppasta med timjan",
            steps = listOf(
                draft.steps[0].copy(
                    name = "Förbered allt",
                    ingredients = draft.steps[0].ingredients + DraftIngredient(amount = 1.0, unit = "tsk", food = "timjan"),
                ),
                draft.steps[2],
            ),
        )
        ok(repo.updateRecipe(PASTA, edited))
        // Shown at once, before anything was sent; a sync meanwhile keeps the edit.
        val local = checkNotNull(graph.recipes.observeStoredRecipe(PASTA).first().recipe)
        assertEquals("Krämig svamppasta med timjan", local.name)
        assertEquals(listOf("Förbered allt", "Sås"), local.steps.map { it.name })
        assertEquals(RecipeEditStatus.QUEUED, repo.observeEditStatus(PASTA).first())
        ok(graph.recipes.refreshRecipe(PASTA))
        assertEquals("Krämig svamppasta med timjan", graph.recipes.observeStoredRecipe(PASTA).first().recipe?.name)
        assertEquals("Krämig svamppasta med timjan", storedNames().first { it.startsWith("Krämig") })

        drainAll()
        val patch = Json.parseToJsonElement(tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").single().second).jsonObject
        val steps = patch["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1L, 3L), steps.map { it["id"]?.jsonPrimitive?.longOrNull })
        val ingredients = steps[0]["ingredients"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1L, 2L, 3L, null), ingredients.map { it["id"]?.jsonPrimitive?.longOrNull })
        assertEquals("""{"name":"timjan"}""", ingredients[3]["food"].toString())
        val keywords = patch["keywords"]!!.jsonArray.map { it.toString() }.toSet()
        assertEquals("the server's keywords go by id", setOf("""{"id":2}""", """{"id":3}"""), keywords)
        assertEquals("the step without a file keeps it", 2, tandoor.stepsOf(PASTA).size)
        assertEquals(listOf(1L, 3L), tandoor.stepsOf(PASTA).map { it["id"]!!.jsonPrimitive.long })
        assertEquals(4, (tandoor.stepsOf(PASTA)[0]["ingredients"] as JsonArray).size)
        assertEquals(RecipeEditStatus.NONE, repo.observeEditStatus(PASTA).first())
        assertEquals("Krämig svamppasta med timjan", graph.recipes.observeStoredRecipe(PASTA).first().recipe?.name)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun anEditOfARecipeChangedOnTheServerIsHeldBackAndKept() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        ok(repo.updateRecipe(PASTA, draft.copy(name = "Min svamppasta")))
        tandoor.partnerEdits(PASTA, "Partnern skrev om beskrivningen.")
        drainAll()

        assertTrue("no PATCH went out", tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").isEmpty())
        val row = outbox().single()
        assertEquals(OutboxState.FAILED, row.state)
        assertEquals(RecipeEditPayloads.CONFLICT, row.lastError)
        assertEquals(RecipeEditStatus.CONFLICT, repo.observeEditStatus(PASTA).first())
        // The server's version is shown; the kept edit is what the editor opens.
        val shown = checkNotNull(graph.recipes.observeStoredRecipe(PASTA).first().recipe)
        assertEquals("Krämig svamppasta", shown.name)
        assertEquals("Partnern skrev om beskrivningen.", shown.description)
        assertEquals("Min svamppasta", repo.observeDraft(PASTA).first()?.name)

        // Saving again starts from the server's version and goes through.
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Min svamppasta")))
        assertEquals(1, outbox().size)
        drainAll()
        assertEquals("Min svamppasta", tandoor.recipe(PASTA)?.get("name")?.jsonPrimitive?.content)
        assertEquals("Partnern skrev om beskrivningen.", tandoor.recipe(PASTA)?.get("description")?.jsonPrimitive?.content)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aKeptEditCanBeDiscarded() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Min svamppasta")))
        tandoor.partnerEdits(PASTA, "Ändrad.")
        drainAll()
        assertEquals(RecipeEditStatus.CONFLICT, repo.observeEditStatus(PASTA).first())
        ok(repo.discardEdit(PASTA))
        assertEquals(RecipeEditStatus.NONE, repo.observeEditStatus(PASTA).first())
        assertEquals("Krämig svamppasta", repo.observeDraft(PASTA).first()?.name)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun anEditOfARecipeNotSentYetMergesIntoItsCreate() = forEachVersion {
        graph.network.online.value = false
        val id = ok(repo.createRecipe(newRecipe()))
        ok(repo.updateRecipe(id, checkNotNull(repo.observeDraft(id).first()).copy(name = "Fiskgratäng med dill")))
        assertEquals(listOf(OutboxOps.CREATE), outbox().map { it.op })
        assertEquals("Fiskgratäng med dill", graph.recipes.observeStoredRecipe(id).first().recipe?.name)
        graph.network.online.value = true
        drainAll()
        assertEquals(1, tandoor.callsTo("POST", "/api/recipe/").size)
        assertTrue(tandoor.callsTo("PATCH", "/api/recipe/").isEmpty())
        assertTrue(tandoor.recipeNamed("Fiskgratäng med dill") != null)
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun aDeletedRecipeIsGoneAtOnceAndDeletedOnTheServer() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        ok(repo.deleteRecipe(PASTA))
        assertFalse("Krämig svamppasta" in storedNames())
        drainAll()
        assertEquals(1, tandoor.callsTo("DELETE", "/api/recipe/$PASTA/").size)
        assertNull(tandoor.recipe(PASTA))
        assertTrue(outbox().isEmpty())
        // Already gone on the server: dropped, no error.
        ok(graph.recipes.refreshRecipe(PANCAKES))
        tandoor.removeOnServer(PANCAKES)
        ok(repo.deleteRecipe(PANCAKES))
        drainAll()
        assertEquals(1, tandoor.callsTo("DELETE", "/api/recipe/$PANCAKES/").size)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun deletingARecipeNotSentYetSendsNothing() = forEachVersion {
        graph.network.online.value = false
        val id = ok(repo.createRecipe(newRecipe()))
        ok(repo.deleteRecipe(id))
        assertFalse("Fiskgratäng" in storedNames())
        assertTrue(outbox().isEmpty())
        graph.network.online.value = true
        drainAll()
        assertTrue(tandoor.callsTo("POST", "/api/recipe/").isEmpty() && tandoor.callsTo("DELETE", "/api/recipe/").isEmpty())
    }

    // ------------------------------------------------------------------ guests and demo mode

    @Test
    fun aGuestChangesNothing() = forEachVersion {
        graph.permissions.setGuest(true)
        assertEquals(DataProblem.READ_ONLY.toError(), failure(repo.createRecipe(newRecipe())))
        assertEquals(DataProblem.READ_ONLY.toError(), failure(repo.deleteRecipe(PASTA)))
        assertTrue(outbox().isEmpty())
        assertTrue("Krämig svamppasta" in storedNames())
    }

    @Test
    fun demoModeWritesTheDemoDatabaseWithoutTheNetwork(): Unit = runBlocking {
        val graph = SyncTestGraph()
        try {
            graph.sources.source.value = ActiveSource.Demo
            assertTrue(graph.demo.ensureSeeded() is Outcome.Success)
            val repo = graph.recipeEdits
            val id = ok(repo.createRecipe(newRecipe()))
            assertTrue(id > 0)
            assertEquals(RecipeEditStatus.NONE, repo.observeEditStatus(id).first())
            assertEquals("Fiskgratäng", graph.recipes.observeStoredRecipe(id).first().recipe?.name)
            ok(repo.updateRecipe(id, checkNotNull(repo.observeDraft(id).first()).copy(name = "Fiskgratäng med dill")))
            assertEquals("Fiskgratäng med dill", graph.recipes.observeStoredRecipe(id).first().recipe?.name)
            ok(repo.deleteRecipe(id))
            assertNull(graph.recipes.observeStoredRecipe(id).first().recipe)
            assertEquals(0, graph.demoDatabase.outboxDao().observeCount(DEMO_ACCOUNT_ID).first())
            assertTrue("demo mode sends nothing: ${graph.server.requests}", graph.server.requests.isEmpty())
        } finally {
            graph.close()
        }
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 10
        const val PASTA = 2L
        const val PANCAKES = 3L
        const val NOT_STORED = 4_242L
    }
}
