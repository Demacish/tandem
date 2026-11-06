package casa.moller.tandem.core.sync.recipeedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.dao.RecipeOverviewWrite
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 review (fix-data): what the editor's outbox got wrong, against the same stateful Tandoor as
 * [RecipeEditRepositoryTest]: a CREATE the server refused, picture changes (the newest whole, the
 * replaced copy deleted, dropped with the edit, after a new PATCH), the device's own writes that
 * are no conflict, a web change only the overview knows of, keyword names, lost answers, a recipe
 * that is not on the server yet, and what moves with its server id.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecipeEditReviewTest {
    private fun forEachVersion(block: suspend RecipeEditEnv.() -> Unit) = RecipeEditEnv.forEachVersion(block)

    private fun <T> ok(outcome: Outcome<T>): T {
        assertTrue("$outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    /** The next `POST /api/recipe/` is refused with a 400 (a field over Tandoor's limit), later ones go through. */
    private fun RecipeEditEnv.refuseNextCreate() {
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
    private fun RecipeEditEnv.loseNextAnswer(method: String, path: String) {
        val lose = AtomicBoolean(true)
        graph.server.respond(method, path) { request ->
            val saved = checkNotNull(tandoor.answer(request))
            if (lose.getAndSet(false)) FakeTandoorServer.json(503, "{}") else saved
        }
    }

    private fun RecipeEditEnv.photo(name: String): File =
        File(graph.context.cacheDir, "$name-$version.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    private fun fileOf(path: File) = "file://${path.path}"

    private suspend fun RecipeEditEnv.imageRow() = outbox().single { it.op == OutboxOps.RECIPE_IMAGE }

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

    private companion object {
        const val PASTA = 2L
        const val GONE_KEYWORD = 9_999L
    }
}
