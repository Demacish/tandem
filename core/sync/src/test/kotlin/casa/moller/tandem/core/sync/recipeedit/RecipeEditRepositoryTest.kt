package casa.moller.tandem.core.sync.recipeedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.dao.RecipeOverviewWrite
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.book.WantToCook
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
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

    /** The next `POST /api/recipe/` is refused with a 400 (a field over Tandoor's limit), later ones go through. */
    private fun Env.refuseNextCreate() {
        val refuse = AtomicBoolean(true)
        graph.server.respond("POST", "/api/recipe/") { request ->
            if (refuse.getAndSet(false)) {
                FakeTandoorServer.json(400, """{"name":["Ensure this field has no more than 128 characters."]}""")
            } else {
                checkNotNull(tandoor.answer(request))
            }
        }
    }

    /** The next [method] to [path] is applied on the server, but its answer is lost (a 503). */
    private fun Env.loseNextAnswer(method: String, path: String) {
        val lose = AtomicBoolean(true)
        graph.server.respond(method, path) { request ->
            val saved = checkNotNull(tandoor.answer(request))
            if (lose.getAndSet(false)) FakeTandoorServer.json(503, "{}") else saved
        }
    }

    private fun Env.photo(name: String): File =
        File(graph.context.cacheDir, "$name-$version.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    private fun fileOf(path: File) = "file://${path.path}"

    private suspend fun Env.imageRow() = outbox().single { it.op == OutboxOps.RECIPE_IMAGE }

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
        assertEquals(setOf("Husmanskost", "Fisk"), stored.keywords.map { it.name }.toSet())
        assertEquals(RecipeEditStatus.QUEUED, repo.observeEditStatus(id).first())
        val draft = checkNotNull(repo.observeDraft(id).first())
        assertEquals("Fiskgratäng", draft.name)
        assertEquals(setOf("Husmanskost", "Fisk"), draft.keywords.map { it.name }.toSet())
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
        assertEquals(listOf("""{"id":5,"name":"Husmanskost"}""", """{"name":"Fisk"}"""), keywords)

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
        val expected = setOf("""{"id":2,"name":"Pasta"}""", """{"id":3,"name":"Vegetariskt"}""")
        assertEquals("the server's keywords go by id, with the server's names", expected, keywords)
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
        assertEquals("[$version]", "Krämig svamppasta", shown.name)
        assertEquals("[$version]", "Partnern skrev om beskrivningen.", shown.description)
        assertEquals("Min svamppasta", repo.observeDraft(PASTA).first()?.name)

        // Saving the kept edit again sends it over the web's version (what the editor says it does).
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first())))
        assertEquals(1, outbox().size)
        drainAll()
        assertEquals("Min svamppasta", tandoor.recipe(PASTA)?.get("name")?.jsonPrimitive?.content)
        val sentDescription = tandoor.recipe(PASTA)?.get("description")?.jsonPrimitive?.content
        assertEquals("the kept edit's description wins", draft.description, sentDescription)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aChangeOfOnlyAStepOnTheWebIsFoundToo() = forEachVersion {
        // Plan §10 Fas 3 "Klart när": "även när bara ett steg ändrats" (a step PATCH leaves updated_at).
        ok(graph.recipes.refreshRecipe(PASTA))
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        ok(repo.updateRecipe(PASTA, draft.copy(name = "Min svamppasta")))
        tandoor.partnerEditsStep(PASTA, "Partnern skrev om steget.")
        drainAll()

        assertTrue("no PATCH went out", tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").isEmpty())
        assertEquals(RecipeEditStatus.CONFLICT, repo.observeEditStatus(PASTA).first())
        val shown = checkNotNull(graph.recipes.observeStoredRecipe(PASTA).first().recipe)
        assertEquals("[$version]", "Partnern skrev om steget.", shown.steps.minByOrNull { it.order }?.instruction)
        // Saved again from the web's version, it goes out.
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first())))
        drainAll()
        assertEquals("Min svamppasta", tandoor.recipe(PASTA)?.get("name")?.jsonPrimitive?.content)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun theRecipesPictureCanBeRemoved() = forEachVersion {
        tandoor.hasImage(PASTA)
        ok(graph.recipes.refreshRecipe(PASTA))
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        assertTrue("[$version] ${draft.currentImageUrl}", draft.currentImageUrl?.contains("recipe_$PASTA") == true)
        ok(repo.updateRecipe(PASTA, draft.copy(keepImage = false)))
        // Gone on the device at once, removed on the server with an empty `image_url`.
        assertEquals(null, graph.recipes.observeStoredRecipe(PASTA).first().recipe?.imageUrl)
        assertEquals(listOf(OutboxOps.PATCH, OutboxOps.RECIPE_IMAGE), outbox().map { it.op })
        drainAll()
        val (_, sent) = tandoor.callsTo("PUT", "/api/recipe/$PASTA/image/").single()
        assertTrue(sent, sent.contains("name=\"image_url\""))
        assertEquals(JsonNull, tandoor.recipe(PASTA)?.get("image"))
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

    // ------------------------------------------------------------------ Fas 4 review (fix-data)

    @Test
    fun aRefusedNewRecipeSurvivesAnEditAndIsPostedWithTheNewBody() = forEachVersion {
        refuseNextCreate()
        val id = ok(repo.createRecipe(newRecipe()))
        drainAll()
        assertEquals(RecipeEditStatus.REFUSED_NEW, repo.observeEditStatus(id).first())
        assertTrue(tandoor.callsTo("POST", "/api/recipe/").isEmpty())

        ok(repo.updateRecipe(id, checkNotNull(repo.observeDraft(id).first()).copy(name = "Rättad gratäng")))
        assertEquals("waits to be sent again", RecipeEditStatus.QUEUED, repo.observeEditStatus(id).first())
        assertEquals(listOf(OutboxOps.CREATE), outbox().map { it.op })
        assertEquals(0, outbox().single().attempts)
        assertTrue("Rättad gratäng" in storedNames())

        drainAll()
        val (_, sent) = tandoor.callsTo("POST", "/api/recipe/").single()
        assertEquals("Rättad gratäng", Json.parseToJsonElement(sent).jsonObject["name"]?.jsonPrimitive?.content)
        assertTrue(outbox().isEmpty())
        assertEquals(1, storedNames().count { it == "Rättad gratäng" })
    }

    @Test
    fun aRefusedNewRecipesPhotoComesBackWithIt() = forEachVersion {
        refuseNextCreate()
        val id = ok(repo.createRecipe(newRecipe(photo = photo("refused"))))
        drainAll()
        assertTrue(outbox().all { it.state == OutboxState.FAILED })

        ok(repo.updateRecipe(id, checkNotNull(repo.observeDraft(id).first()).copy(name = "Rättad")))
        assertTrue(outbox().all { it.state == OutboxState.PENDING })

        drainAll()
        val created = tandoor.recipeNamed("Rättad")?.get("id")?.jsonPrimitive?.long
        assertEquals(1, tandoor.callsTo("PUT", "/api/recipe/$created/image/").size)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun deletingARefusedNewRecipeCancelsEverything() = forEachVersion {
        refuseNextCreate()
        val id = ok(repo.createRecipe(newRecipe(photo = photo("refused-delete"))))
        drainAll()
        val copy = checkNotNull(RecipeEditPayloads.imagePathOf(imageRow().payload))

        ok(repo.deleteRecipe(id))

        assertTrue(outbox().isEmpty())
        assertFalse(File(copy).exists())
        assertFalse("Fiskgratäng" in storedNames())
    }

    @Test
    fun aRecipeDeletedOverAConflictIsDeletedOnTheServerAndStaysGone() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Min svamppasta")))
        tandoor.partnerEdits(PASTA, "Ändrad.")
        drainAll()
        assertEquals(RecipeEditStatus.CONFLICT, repo.observeEditStatus(PASTA).first())

        ok(repo.deleteRecipe(PASTA))
        drainAll()

        assertEquals(1, tandoor.callsTo("DELETE", "/api/recipe/$PASTA/").size)
        assertNull(tandoor.recipe(PASTA))
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aNewPhotoAfterAnUnsentRemovalIsUploaded() = forEachVersion {
        tandoor.hasImage(PASTA)
        ok(graph.recipes.refreshRecipe(PASTA))
        graph.network.online.value = false
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        ok(repo.updateRecipe(PASTA, draft.copy(keepImage = false)))
        ok(repo.updateRecipe(PASTA, draft.copy(imageUri = fileOf(photo("after-removal")))))
        assertFalse(RecipeEditPayloads.isImageRemoval(imageRow().payload))
        graph.network.online.value = true

        drainAll()

        val (_, sent) = tandoor.callsTo("PUT", "/api/recipe/$PASTA/image/").single()
        assertTrue(sent, sent.contains("name=\"image\""))
        assertFalse(sent, sent.contains("name=\"image_url\""))
    }

    @Test
    fun aPhotoAfterAnUnsentWebPictureReplacesIt() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        graph.network.online.value = false
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        ok(repo.updateRecipe(PASTA, draft.copy(imageUrl = "https://example.com/a.jpg")))
        ok(repo.updateRecipe(PASTA, draft.copy(imageUri = fileOf(photo("after-url")))))
        assertNull(RecipeEditPayloads.imageUrlOf(imageRow().payload))
        graph.network.online.value = true

        drainAll()

        val (_, sent) = tandoor.callsTo("PUT", "/api/recipe/$PASTA/image/").single()
        assertFalse(sent, sent.contains("name=\"image_url\""))
    }

    @Test
    fun aRemovalAfterAnUnsentPhotoSendsTheRemovalAndDeletesTheCopy() = forEachVersion {
        tandoor.hasImage(PASTA)
        ok(graph.recipes.refreshRecipe(PASTA))
        graph.network.online.value = false
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        ok(repo.updateRecipe(PASTA, draft.copy(imageUri = fileOf(photo("before-removal")))))
        val copy = checkNotNull(RecipeEditPayloads.imagePathOf(imageRow().payload))
        ok(repo.updateRecipe(PASTA, draft.copy(keepImage = false)))
        assertTrue(RecipeEditPayloads.isImageRemoval(imageRow().payload))
        assertFalse(File(copy).exists())
        graph.network.online.value = true

        drainAll()

        assertEquals(JsonNull, tandoor.recipe(PASTA)?.get("image"))
    }

    @Test
    fun aNewerPhotoDeletesTheCopyItReplaces() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        graph.network.online.value = false
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        ok(repo.updateRecipe(PASTA, draft.copy(imageUri = fileOf(photo("first")))))
        val first = checkNotNull(RecipeEditPayloads.imagePathOf(imageRow().payload))
        ok(repo.updateRecipe(PASTA, draft.copy(imageUri = fileOf(photo("second")))))
        val second = checkNotNull(RecipeEditPayloads.imagePathOf(imageRow().payload))

        assertTrue(first != second)
        assertFalse(File(first).exists())
        assertTrue(File(second).exists())
    }

    @Test
    fun droppingAConflictedEditDropsItsPictureChangeToo() = forEachVersion {
        tandoor.hasImage(PASTA)
        ok(graph.recipes.refreshRecipe(PASTA))
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(keepImage = false)))
        tandoor.partnerEdits(PASTA, "Ändrad.")
        graph.outbox.drain(graph.remote)
        assertEquals(RecipeEditStatus.CONFLICT, repo.observeEditStatus(PASTA).first())

        ok(repo.discardEdit(PASTA))
        drainAll()

        assertTrue("the picture stays", tandoor.callsTo("PUT", "/api/recipe/$PASTA/image/").isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aKeptEditSavedAgainSendsItsPictureChangeAfterThePatch() = forEachVersion {
        tandoor.hasImage(PASTA)
        ok(graph.recipes.refreshRecipe(PASTA))
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(keepImage = false)))
        tandoor.partnerEdits(PASTA, "Ändrad.")
        graph.outbox.drain(graph.remote)

        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Andra försöket")))

        assertEquals(listOf(OutboxOps.PATCH, OutboxOps.RECIPE_IMAGE), outbox().map { it.op })
        drainAll()
        val order = tandoor.calls.map { it.first }.filter { it.startsWith("PATCH") || it.startsWith("PUT") }
        assertEquals(listOf("PATCH /api/recipe/$PASTA/", "PUT /api/recipe/$PASTA/image/"), order)
        assertEquals(JsonNull, tandoor.recipe(PASTA)?.get("image"))
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aPatchWhoseAnswerWasLostIsNoConflict() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        val added = draft.steps[0].copy(ingredients = draft.steps[0].ingredients + DraftIngredient(amount = 1.0, food = "timjan"))
        ok(repo.updateRecipe(PASTA, draft.copy(name = "Min svamppasta", steps = listOf(added) + draft.steps.drop(1))))
        loseNextAnswer("PATCH", "/api/recipe/$PASTA/")

        drainAll()

        assertEquals("sent once", 1, tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").size)
        assertEquals(RecipeEditStatus.NONE, repo.observeEditStatus(PASTA).first())
        assertTrue(outbox().isEmpty())
        assertEquals("Min svamppasta", graph.recipes.observeStoredRecipe(PASTA).first().recipe?.name)
    }

    @Test
    fun wantToCookBeforeAnOfflineEditIsNoConflictAndStaysOnTheRecipe() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        graph.network.online.value = false
        ok(graph.books.setWantToCook(PASTA, true))
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Min svamppasta")))
        graph.network.online.value = true

        drainAll()

        assertEquals(RecipeEditStatus.NONE, repo.observeEditStatus(PASTA).first())
        val recipe = checkNotNull(tandoor.recipe(PASTA))
        assertEquals("Min svamppasta", recipe["name"]?.jsonPrimitive?.content)
        val names = recipe["keywords"]!!.jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue("$names", WantToCook.KEYWORD in names)
    }

    @Test
    fun aSecondSaveWhileTheFirstIsBeingSentIsNoConflict() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        val again = AtomicBoolean(true)
        graph.server.respond("PATCH", "/api/recipe/$PASTA/") { request ->
            if (again.getAndSet(false)) runBlocking { ok(repo.updateRecipe(PASTA, draft.copy(name = "Andra sparningen"))) }
            checkNotNull(tandoor.answer(request))
        }
        ok(repo.updateRecipe(PASTA, draft.copy(name = "Första sparningen")))

        drainAll()

        assertEquals(RecipeEditStatus.NONE, repo.observeEditStatus(PASTA).first())
        assertEquals("Andra sparningen", tandoor.recipe(PASTA)?.get("name")?.jsonPrimitive?.content)
        assertEquals(2, tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").size)
    }

    @Test
    fun aWebChangeTheDetailNeverSawIsAConflictEvenWhenTheOverviewMovedOn() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        tandoor.partnerChangesServings(PASTA, 6)
        // A background sync stores the new `updated_at` in the overview only.
        val updatedAt = RecipeEditPayloads.updatedAtOf(tandoor.recipe(PASTA))?.toEpochMilliseconds()
        val overview = graph.real.recipeDao().getOverviews(ACCOUNT_ID, listOf(PASTA)).single()
        graph.real.recipeDao().upsertOverviews(ACCOUNT_ID, listOf(RecipeOverviewWrite(overview.copy(updatedAtEpochMillis = updatedAt))))
        graph.network.online.value = false
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Min svamppasta")))
        graph.network.online.value = true

        drainAll()

        assertEquals(RecipeEditStatus.CONFLICT, repo.observeEditStatus(PASTA).first())
        assertTrue("nothing overwrites the web's servings", tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").isEmpty())
    }

    @Test
    fun aKeywordRenamedOnTheServerKeepsItsNameThroughACreate() = forEachVersion {
        tandoor.renameKeyword(5, "Husman")
        val id = ok(repo.createRecipe(newRecipe()))
        assertTrue(id < 0)

        drainAll()

        val (_, sent) = tandoor.callsTo("POST", "/api/recipe/").single()
        val keyword = Json.parseToJsonElement(sent).jsonObject["keywords"]!!.jsonArray.map { it.jsonObject }.first { "id" in it }
        assertEquals("Husman", keyword["name"]?.jsonPrimitive?.content)
        assertEquals("Husman", tandoor.keywordName(5))
    }

    @Test
    fun aKeywordRenamedOnTheServerKeepsItsNameThroughAPatchThatAddsIt() = forEachVersion {
        ok(graph.recipes.refreshRecipe(PASTA))
        val draft = checkNotNull(repo.observeDraft(PASTA).first())
        tandoor.renameKeyword(5, "Husman")
        ok(repo.updateRecipe(PASTA, draft.copy(keywords = draft.keywords + DraftKeyword(id = 5, name = "Husmanskost"))))

        drainAll()

        val (_, sent) = tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").single()
        val keywords = Json.parseToJsonElement(sent).jsonObject["keywords"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Husman", keywords.first { it["id"]?.jsonPrimitive?.long == 5L }["name"]?.jsonPrimitive?.content)
        assertEquals("Husman", tandoor.keywordName(5))
    }

    @Test
    fun aKeywordGoneFromTheServerGoesByNameAndAFailedReadHoldsTheRowBack() = forEachVersion {
        val unavailable = AtomicBoolean(true)
        graph.server.respond("GET", "/api/keyword/${GONE_KEYWORD}/") {
            if (unavailable.get()) FakeTandoorServer.json(503, "{}") else FakeTandoorServer.json(404, """{"detail":"Not found."}""")
        }
        val keywords = listOf(DraftKeyword(id = GONE_KEYWORD, name = "Gammalt"))
        ok(repo.createRecipe(newRecipe().copy(keywords = keywords)))

        assertEquals(DrainResult.RETRY, graph.outbox.drain(graph.remote))
        assertTrue("no stale name goes out", tandoor.callsTo("POST", "/api/recipe/").isEmpty())
        unavailable.set(false)
        drainAll()

        val (_, sent) = tandoor.callsTo("POST", "/api/recipe/").single()
        assertEquals("""[{"name":"Gammalt"}]""", Json.parseToJsonElement(sent).jsonObject["keywords"].toString())
    }

    @Test
    fun aFailedLookupForALostCreateNeverPostsASecondRecipe() = forEachVersion {
        tandoor.createdAt = Instant.fromEpochMilliseconds(graph.clock.millis).toString()
        loseNextAnswer("POST", "/api/recipe/")
        val lookupFails = AtomicBoolean(true)
        graph.server.respond("GET", "/api/recipe/") { request ->
            if (lookupFails.get()) FakeTandoorServer.json(503, "{}") else checkNotNull(tandoor.answer(request))
        }
        val id = ok(repo.createRecipe(newRecipe()))

        assertEquals("the POST was applied, its answer lost", DrainResult.RETRY, graph.outbox.drain(graph.remote))
        assertEquals("the lookup fails too", DrainResult.RETRY, graph.outbox.drain(graph.remote))
        assertEquals(1, tandoor.callsTo("POST", "/api/recipe/").size)

        lookupFails.set(false)
        drainAll()

        assertEquals("exactly one recipe", 1, tandoor.callsTo("POST", "/api/recipe/").size)
        assertTrue(outbox().isEmpty())
        assertTrue(graph.real.idRewriteDao().current(ACCOUNT_ID, OutboxEntities.RECIPE_EDIT, id) > 0)
        val sorted = graph.server.requests.last { it.startsWith("GET /api/recipe/?") }
        assertTrue(sorted, "sort_order=-created_at" in sorted)
    }

    @Test
    fun removingThePictureOfARecipeNotSentYetCancelsItsQueuedPicture() = forEachVersion {
        graph.network.online.value = false
        val id = ok(repo.createRecipe(newRecipe(photo = photo("new-removed"))))
        val copy = checkNotNull(RecipeEditPayloads.imagePathOf(imageRow().payload))

        ok(repo.updateRecipe(id, checkNotNull(repo.observeDraft(id).first()).copy(keepImage = false)))

        assertEquals(listOf(OutboxOps.CREATE), outbox().map { it.op })
        assertFalse(File(copy).exists())
        graph.network.online.value = true
        drainAll()
        assertEquals(1, tandoor.callsTo("POST", "/api/recipe/").size)
        assertTrue(tandoor.calls.none { (call, _) -> call.startsWith("PUT") })
    }

    @Test
    fun aDeletedRecipeAnIndexBroughtBackGoesWhenItsDeleteIsAnswered() = forEachVersion {
        ok(repo.deleteRecipe(PASTA))
        // The DELETE is queued: the index leaves the recipe out. (A stub an earlier read left is removed by the answer.)
        graph.real.recipeDao().applyIndex(ACCOUNT_ID, listOf(RecipeIndexEntry(PASTA, "Krämig svamppasta")), allowEmpty = true)
        assertTrue(graph.real.recipeDao().getOverviews(ACCOUNT_ID, listOf(PASTA)).isEmpty())
        val stub = RecipeOverviewEntity(accountId = ACCOUNT_ID, serverId = PASTA, name = "Krämig svamppasta")
        graph.real.recipeDao().insertOverviews(listOf(stub))

        drainAll()

        assertTrue(graph.real.recipeDao().getOverviews(ACCOUNT_ID, listOf(PASTA)).isEmpty())
    }

    @Test
    fun nutritionStaysWhileAnEditIsUnsentAndIsNeverSent() = forEachVersion {
        tandoor.hasNutrition(PASTA)
        ok(graph.recipes.refreshRecipe(PASTA))
        val before = checkNotNull(graph.recipes.observeStoredRecipe(PASTA).first().recipe).nutritionValues
        assertEquals(1, before.size)
        graph.network.online.value = false

        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Min svamppasta")))

        assertEquals(before, graph.recipes.observeStoredRecipe(PASTA).first().recipe?.nutritionValues)
        val base = outbox().single().baseSnapshot
        assertFalse("properties" in outbox().single().payload)

        // A second offline edit starts from the unsent body: the base of the first stays.
        ok(repo.updateRecipe(PASTA, checkNotNull(repo.observeDraft(PASTA).first()).copy(name = "Min andra svamppasta")))
        assertEquals(base, outbox().single().baseSnapshot)
        assertEquals(before, graph.recipes.observeStoredRecipe(PASTA).first().recipe?.nutritionValues)
        graph.network.online.value = true

        drainAll()

        val (_, sent) = tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").single()
        assertFalse(sent, "food_properties" in sent)
    }

    @Test
    fun aRecipeNotOnTheServerIsNeverFetchedSoItsOnlyCopyStays() = forEachVersion {
        graph.network.online.value = false
        val id = ok(repo.createRecipe(newRecipe()))
        graph.network.online.value = true
        graph.clock.advance(1.hours)

        ok(graph.recipes.refreshRecipe(id))
        assertEquals("Fiskgratäng", graph.recipes.observeRecipe(id).first { !it.refreshing && it.recipe != null }.recipe?.name)

        assertTrue(graph.server.requests.toString(), graph.server.requests.none { it.startsWith("GET /api/recipe/-") })
        assertTrue("Fiskgratäng" in storedNames())
    }

    @Test
    fun aRefusedNewRecipeIsNeverFetchedEither() = forEachVersion {
        refuseNextCreate()
        val id = ok(repo.createRecipe(newRecipe()))
        drainAll()
        graph.clock.advance(1.hours)

        ok(graph.recipes.refreshRecipe(id))

        assertTrue(graph.server.requests.none { it.startsWith("GET /api/recipe/-") })
        assertTrue("Fiskgratäng" in storedNames())
    }

    @Test
    fun theOldTemporaryIdIsFollowedToTheServerId() = forEachVersion {
        val id = ok(repo.createRecipe(newRecipe()))
        drainAll()
        val serverId = graph.real.idRewriteDao().current(ACCOUNT_ID, OutboxEntities.RECIPE_EDIT, id)

        ok(graph.recipes.refreshRecipe(id))

        assertTrue(serverId > 0)
        assertEquals(1, graph.server.requestsTo("GET", "/api/recipe/$serverId/").size)
    }

    @Test
    fun theDevicesOwnStateOfANewRecipeMovesToItsServerId() = forEachVersion {
        graph.network.online.value = false
        val id = ok(repo.createRecipe(newRecipe()))
        ok(graph.recipes.setAvailableOffline(id, true))
        ok(graph.recipes.setServingsChoice(id, 6.0))
        graph.recipes.markViewed(id)
        graph.network.online.value = true

        drainAll()

        val serverId = graph.real.idRewriteDao().current(ACCOUNT_ID, OutboxEntities.RECIPE_EDIT, id)
        val row = checkNotNull(graph.real.recipeDao().getDetailRow(ACCOUNT_ID, serverId))
        assertTrue(row.pinned)
        assertEquals(6.0, row.portionsChosen ?: 0.0, 0.0)
        assertEquals(graph.clock.millis, row.lastViewedLocalEpochMillis)
        assertNull(graph.real.recipeDao().getDetailRow(ACCOUNT_ID, id))
    }

    @Test
    fun theDevicesOwnStateMovesAlsoWhenTheAnswerStoredNoDetail() = forEachVersion {
        tandoor.createdAt = Instant.fromEpochMilliseconds(graph.clock.millis).toString()
        loseNextAnswer("POST", "/api/recipe/")
        val id = ok(repo.createRecipe(newRecipe()))
        ok(graph.recipes.setAvailableOffline(id, true))
        assertEquals(DrainResult.RETRY, graph.outbox.drain(graph.remote))
        // The lost-answer path fetches the recipe; that fetch fails here, so nothing is stored under the server id.
        val created = tandoor.recipeNamed("Fiskgratäng")?.get("id")?.jsonPrimitive?.long
        graph.server.respond("GET", "/api/recipe/$created/") { FakeTandoorServer.json(503, "{}") }

        drainAll()

        val serverId = graph.real.idRewriteDao().current(ACCOUNT_ID, OutboxEntities.RECIPE_EDIT, id)
        assertEquals(created, serverId)
        assertEquals(true, graph.real.recipeDao().getDetailRow(ACCOUNT_ID, serverId)?.pinned)
    }

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
        const val GONE_KEYWORD = 9_999L
    }
}
