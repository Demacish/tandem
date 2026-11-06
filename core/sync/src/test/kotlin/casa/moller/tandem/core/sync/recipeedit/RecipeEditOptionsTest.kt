package casa.moller.tandem.core.sync.recipeedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.PropertyType
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import casa.moller.tandem.core.model.recipe.RecipeProperty
import casa.moller.tandem.core.model.recipe.StepFile
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 (#22, decision B9): what the editor's controls beyond the plain form send, on the fake
 * built from the facts of `Phase4EditorContractTest`: the flags, a sub-recipe, a step's file and the
 * recipe's own nutrition. What the user did not touch stays out of the PATCH, so a save can never
 * change it.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecipeEditOptionsTest {
    private fun forEachVersion(block: suspend RecipeEditEnv.() -> Unit) = RecipeEditEnv.forEachVersion(block)

    private fun <T> ok(outcome: Outcome<T>): T {
        assertTrue("$outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    private suspend fun RecipeEditEnv.draft(): RecipeDraft {
        ok(graph.recipes.refreshRecipe(PASTA))
        return checkNotNull(repo.observeDraft(PASTA).first())
    }

    /** This draft with its first step changed. */
    private fun RecipeDraft.first(change: (DraftStep) -> DraftStep): RecipeDraft =
        copy(steps = steps.mapIndexed { index, step -> if (index == 0) change(step) else step })

    private fun RecipeEditEnv.patchBody(): JsonObject =
        Json.parseToJsonElement(tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").last().second).jsonObject

    private fun JsonObject.steps(): List<JsonObject> = getValue("steps").jsonArray.map { it.jsonObject }

    private fun RecipeEditEnv.chosenFile(name: String = "receptblad.pdf"): StepFile {
        val file = File(graph.context.cacheDir, "chosen-$version-${System.nanoTime()}.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return StepFile(name = name, uri = "file://${file.path}")
    }

    private suspend fun RecipeEditEnv.linkOnServer() {
        val draft = draft()
        val link = StepRecipeRef(PANCAKES, "Pannkakor")
        ok(repo.updateRecipe(PASTA, draft.first { it.copy(stepRecipe = link) }))
        drainAll()
    }

    @Test
    fun theFlagsGoWithTheSteps() = forEachVersion {
        val draft = draft()
        val first = draft.steps[0].copy(showAsHeader = !draft.steps[0].showAsHeader, showIngredientsTable = false)
        ok(repo.updateRecipe(PASTA, draft.copy(isPrivate = true, steps = listOf(first) + draft.steps.drop(1))))
        val local = checkNotNull(graph.recipes.observeStoredRecipe(PASTA).first().recipe)
        assertEquals(first.showAsHeader, local.steps[0].showAsHeader)
        assertFalse(local.steps[0].showIngredientsTable)
        assertTrue(local.isPrivate)
        drainAll()
        val body = patchBody()
        assertEquals("true", body["private"]?.jsonPrimitive?.content)
        assertEquals("$version", first.showAsHeader.toString(), body.steps()[0]["show_as_header"]?.jsonPrimitive?.content)
        assertEquals("false", body.steps()[0]["show_ingredients_table"]?.jsonPrimitive?.content)
    }

    @Test
    fun whatNobodyTouchedStaysOutOfThePatch() = forEachVersion {
        linkOnServer()
        tandoor.hasNutrition(PASTA)
        val draft = draft()
        assertEquals(StepRecipeRef(PANCAKES, "Pannkakor"), draft.steps[0].stepRecipe)
        ok(repo.updateRecipe(PASTA, draft.copy(name = "Ny pasta")))
        drainAll()
        val body = patchBody()
        assertFalse("properties" in body)
        assertFalse("step_recipe" in body.steps()[0])
        assertFalse("file" in body.steps()[0])
        assertEquals("the server keeps its link", PANCAKES, tandoor.stepsOf(PASTA)[0]["step_recipe"]?.jsonPrimitive?.long)
    }

    @Test
    fun aSubRecipeIsLinkedChangedAndTakenOff() = forEachVersion {
        linkOnServer()
        val sent = patchBody().steps()[0]
        assertEquals(PANCAKES, sent["step_recipe"]?.jsonPrimitive?.long)
        assertFalse("the name is the device's alone", "step_recipe_data" in sent)
        val stored = checkNotNull(graph.recipes.observeStoredRecipe(PASTA).first().recipe)
        assertEquals(PANCAKES, stored.steps[0].stepRecipe?.recipeId)

        val draft = draft()
        ok(repo.updateRecipe(PASTA, draft.first { it.copy(stepRecipe = null) }))
        assertEquals(null, graph.recipes.observeStoredRecipe(PASTA).first().recipe?.steps?.get(0)?.stepRecipe)
        drainAll()
        assertEquals(JsonNull, patchBody().steps()[0]["step_recipe"])
        assertEquals(JsonNull, tandoor.stepsOf(PASTA)[0]["step_recipe"])
    }

    @Test
    fun aStepWithOnlyASubRecipeOrAFileIsNoEmptyStep() = forEachVersion {
        val link = StepRecipeRef(PANCAKES, "Pannkakor")
        val draft = RecipeDraft(name = "Middag", steps = listOf(DraftStep(stepRecipe = link), DraftStep(file = chosenFile()), DraftStep()))
        ok(repo.createRecipe(draft))
        drainAll()
        val post = Json.parseToJsonElement(tandoor.callsTo("POST", "/api/recipe/").single().second).jsonObject
        assertEquals(2, post.steps().size)
        assertEquals(PANCAKES, post.steps()[0]["step_recipe"]?.jsonPrimitive?.long)
        assertEquals("receptblad.pdf", post.steps()[1]["file"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertFalse("path" in post.steps()[1].getValue("file").jsonObject)
    }

    @Test
    fun aChosenFileIsUploadedBeforeTheRecipeAndItsCopyDeleted() = forEachVersion {
        val draft = draft()
        ok(repo.updateRecipe(PASTA, draft.copy(steps = draft.steps.mapIndexed { i, s -> if (i == 1) s.copy(file = chosenFile()) else s })))
        val queued = RecipeEditPayloads.stepFilePathsOf(outbox().single().payload).single()
        assertTrue("an app-private copy", File(queued).isFile)
        val reopened = checkNotNull(repo.observeDraft(PASTA).first())
        assertEquals("the stored copy knows it before it is sent", "receptblad.pdf", reopened.steps[1].file?.name)

        drainAll()
        val calls = tandoor.calls.map { it.first }
        val uploaded = calls.indexOfFirst { it.startsWith("POST /api/user-file/") }
        val patched = calls.indexOfFirst { it.startsWith("PATCH /api/recipe/") }
        assertTrue("$calls", uploaded in 0 until patched)
        val file = patchBody().steps()[1].getValue("file").jsonObject
        assertEquals(setOf("id", "name"), file.keys)
        assertTrue(queued in graph.drinkImages.deleted)
        val stored = checkNotNull(repo.observeDraft(PASTA).first()).steps[1].file
        assertEquals(file["id"]?.jsonPrimitive?.long, stored?.id)
        assertEquals(null, stored?.uri)
    }

    @Test
    fun aFileIsNeverUploadedTwice() = forEachVersion {
        val draft = draft()
        val chosen = draft.steps.mapIndexed { i, s -> if (i < 2) s.copy(file = chosenFile("fil-$i.pdf")) else s }
        ok(repo.updateRecipe(PASTA, draft.copy(steps = chosen)))
        // The second file is refused: the row fails with the first file already in it.
        tandoor.refuseAfterUploads = 1
        drainAll()
        val row = outbox().single()
        assertEquals(OutboxState.FAILED, row.state)
        assertEquals("one copy is left", 1, RecipeEditPayloads.stepFilePathsOf(row.payload).size)
        assertTrue(row.payload.contains("fil-0.pdf"))
        assertTrue("no PATCH went out", tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").isEmpty())

        tandoor.refuseAfterUploads = null
        graph.real.outboxDao().resetFailed(listOf(row.id))
        drainAll()
        val names = tandoor.callsTo("POST", "/api/user-file/").map { it.second }
        assertEquals("fil-0 went up once: $names", 1, names.count { it.contains("fil-0.pdf") })
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aFileTakenOffIsNullAndAnUnreadableOneSavesNothing() = forEachVersion {
        val draft = draft()
        ok(repo.updateRecipe(PASTA, draft.first { it.copy(file = chosenFile()) }))
        drainAll()
        val withFile = draft()
        assertTrue(withFile.steps[0].file?.id != null)
        ok(repo.updateRecipe(PASTA, withFile.first { it.copy(file = null) }))
        drainAll()
        assertEquals(JsonNull, patchBody().steps()[0]["file"])

        val after = draft()
        val gone = StepFile(name = "borta.pdf", uri = "file:///nothing/here.pdf")
        val refused = repo.updateRecipe(PASTA, after.first { it.copy(file = gone) })
        assertEquals(RecipeEditProblem.STEP_FILE_UNREADABLE.toError(), (refused as? Outcome.Failure)?.error)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aSecondSaveBeforeTheFirstIsSentKeepsTheFileAndTheLink() = forEachVersion {
        graph.network.online.value = false
        val draft = draft()
        val link = StepRecipeRef(PANCAKES, "Pannkakor")
        ok(repo.updateRecipe(PASTA, draft.first { it.copy(stepRecipe = link, file = chosenFile()) }))
        val again = checkNotNull(repo.observeDraft(PASTA).first())
        assertEquals(link, again.steps[0].stepRecipe)
        ok(repo.updateRecipe(PASTA, again.copy(name = "Ändrad två gånger")))
        assertEquals("the edits merged", 1, outbox().size)
        graph.network.online.value = true
        drainAll()
        val sent = patchBody().steps()[0]
        assertEquals(PANCAKES, sent["step_recipe"]?.jsonPrimitive?.long)
        assertTrue(sent["file"]?.jsonObject?.containsKey("id") == true)
    }

    @Test
    fun aDiscardedFileLeavesNoCopyBehind() = forEachVersion {
        val draft = draft()
        ok(repo.updateRecipe(PASTA, draft.first { it.copy(file = chosenFile()) }))
        val copy = RecipeEditPayloads.stepFilePathsOf(outbox().single().payload).single()
        tandoor.partnerEdits(PASTA, "Partnern skrev om beskrivningen.")
        drainAll()
        assertEquals(RecipeEditStatus.CONFLICT, repo.observeEditStatus(PASTA).first())
        assertTrue("a conflict uploads nothing", tandoor.callsTo("POST", "/api/user-file/").isEmpty())
        ok(repo.discardEdit(PASTA))
        assertTrue(copy in graph.drinkImages.deleted)
    }

    @Test
    fun aKeptEditFromBeforeTheseFieldsKeepsWhatTheServerHas() = forEachVersion {
        linkOnServer()
        val draft = draft()
        val old = Json.parseToJsonElement(RecipeEditPayloads.encode(draft).toString()).jsonObject
        val steps = old.steps().map { JsonObject(it - "step_recipe" - "step_recipe_data" - "file") }
        val payload = JsonObject(old - "properties" + ("steps" to JsonArray(steps))).toString()
        graph.real.outboxDao().insert(
            OutboxEntity(
                accountId = ACCOUNT_ID,
                op = OutboxOps.PATCH,
                entity = OutboxEntities.RECIPE_EDIT,
                serverId = PASTA,
                payload = payload,
                state = OutboxState.FAILED,
                lastError = RecipeEditPayloads.CONFLICT,
                createdAtEpochMillis = 1,
            ),
        )
        val kept = checkNotNull(repo.observeDraft(PASTA).first())
        assertEquals(PANCAKES, kept.steps[0].stepRecipe?.recipeId)
        ok(repo.updateRecipe(PASTA, kept))
        drainAll()
        assertEquals(PANCAKES, tandoor.stepsOf(PASTA)[0]["step_recipe"]?.jsonPrimitive?.long)
    }

    @Test
    fun aLinkChangedOnTheWebIsAConflictAndALostAnswerIsNot() = forEachVersion {
        val draft = draft()
        val plain = RecipeEditPayloads.stepsFingerprint(tandoor.recipe(PASTA))
        val linked = ("step_recipe" to JsonPrimitive(PANCAKES))
        val steps = tandoor.stepsOf(PASTA).mapIndexed { index, step -> if (index == 0) JsonObject(step + linked) else step }
        val withLink = JsonObject(tandoor.recipe(PASTA)!! + ("steps" to JsonArray(steps)))
        assertNotEquals(plain, RecipeEditPayloads.stepsFingerprint(withLink))
        val ours = RecipeEditPayloads.encode(draft.first { it.copy(stepRecipe = StepRecipeRef(PANCAKES)) })
        assertTrue(RecipeEditPayloads.sameContent(withLink, ours))
        assertFalse(RecipeEditPayloads.sameContent(tandoor.recipe(PASTA)!!, ours))
    }

    @Test
    fun theRecipesOwnValuesGoOnlyWhenChanged() = forEachVersion {
        val energy = PropertyType(id = 40, name = "Energi", unit = "kcal")
        tandoor.propertyTypes += type(energy)
        ok(repo.refreshPropertyTypes())
        assertEquals(listOf(energy), repo.observePropertyTypes().first())
        val draft = draft()
        val value = RecipeProperty(typeId = 40, typeName = "Energi", unit = "kcal", amount = 350.0)
        ok(repo.updateRecipe(PASTA, draft.copy(properties = listOf(value))))
        val shown = graph.recipes.observeStoredRecipe(PASTA).first().recipe?.nutritionValues?.singleOrNull()
        assertEquals("the panel shows it before it is sent", 350.0, shown?.amount)
        assertEquals("kcal", shown?.unit)
        drainAll()
        val sent = patchBody().getValue("properties").jsonArray.single().jsonObject
        assertEquals(setOf("id", "name"), sent.getValue("property_type").jsonObject.keys)
        assertEquals(350.0, sent["property_amount"]?.jsonPrimitive?.content?.toDouble())

        val stored = draft()
        assertEquals(listOf(350.0), stored.properties?.map { it.amount })
        assertTrue(stored.properties?.single()?.id != null)
        ok(repo.updateRecipe(PASTA, stored.copy(name = "Ändrad")))
        drainAll()
        assertFalse("unchanged", "properties" in patchBody())

        val changed = draft()
        ok(repo.updateRecipe(PASTA, changed.copy(properties = emptyList())))
        drainAll()
        assertEquals(JsonArray(emptyList()), patchBody()["properties"])
    }

    @Test
    fun aValueGoesWithTheTypesNameOfRightNowAndAGoneTypeIsDropped() = forEachVersion {
        val value = RecipeProperty(typeId = 40, typeName = "Gammalt namn", unit = "kcal", amount = 350.0)
        val gone = RecipeProperty(typeId = 41, typeName = "Borttagen", unit = "g", amount = 1.0)
        tandoor.propertyTypes += type(PropertyType(id = 40, name = "Energi på webben", unit = "kcal"))
        ok(repo.updateRecipe(PASTA, draft().copy(properties = listOf(value, gone))))
        drainAll()
        val sent = patchBody().getValue("properties").jsonArray.single().jsonObject
        assertEquals("Energi på webben", sent.getValue("property_type").jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun aPropertyTypeReadOnceWorksOfflineAndOfflineDoesNotFail() = forEachVersion {
        tandoor.propertyTypes += type(PropertyType(id = 41, name = "Protein", unit = "g"))
        ok(repo.refreshPropertyTypes())
        graph.network.online.value = false
        repo.refreshPropertyTypes()
        assertEquals(listOf("Protein"), repo.observePropertyTypes().first().map { it.name })
    }

    @Test
    fun theRecipesThatUseARecipeAreFoundThroughEachOther() = forEachVersion {
        linkOnServer()
        // Pasta uses Pannkakor: Pasta may not be Pannkakor's sub-recipe.
        assertEquals(setOf(PASTA), repo.observeRecipesUsing(PANCAKES).first())
        assertTrue(repo.observeRecipesUsing(PASTA).first().isEmpty())
    }

    @Test
    fun theExportNeverShowsAFilesPath() = forEachVersion {
        val draft = draft()
        ok(repo.updateRecipe(PASTA, draft.first { it.copy(file = chosenFile()) }))
        val row = outbox().single()
        val export = RecipeEditPayloads.withoutFilePaths(Json.parseToJsonElement(row.payload)).toString()
        assertTrue(export.contains("receptblad.pdf"))
        assertFalse(export.contains(graph.context.filesDir.parent!!))
        assertFalse(export.contains("\"path\""))
    }

    @Test
    fun demoModeKeepsAFilesNameAndCopiesNothing(): Unit = runBlocking {
        val graph = SyncTestGraph()
        try {
            graph.sources.source.value = ActiveSource.Demo
            assertTrue(graph.demo.ensureSeeded() is Outcome.Success)
            val repo = graph.recipeEdits
            val chosen = StepFile(name = "blad.pdf", uri = "content://example/1")
            val id = ok(repo.createRecipe(RecipeDraft(name = "Demo", steps = listOf(DraftStep(instruction = "Rör.", file = chosen)))))
            val file = repo.observeDraft(id).first()?.steps?.single()?.file
            assertEquals(StepFile(name = "blad.pdf"), file)
            ok(repo.refreshPropertyTypes())
            assertTrue("demo mode sends nothing: ${graph.server.requests}", graph.server.requests.isEmpty())
        } finally {
            graph.close()
        }
    }

    private fun type(type: PropertyType): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(type.id),
            "name" to JsonPrimitive(type.name),
            "unit" to JsonPrimitive(type.unit),
            "order" to JsonPrimitive(type.order),
        ),
    )

    private companion object {
        const val PASTA = 2L
        const val PANCAKES = 3L
    }
}
