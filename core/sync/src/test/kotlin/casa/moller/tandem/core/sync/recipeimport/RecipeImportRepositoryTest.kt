package casa.moller.tandem.core.sync.recipeimport

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.recipeedit.FakeRecipeEditTandoor
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * The server-side import (plan §8.9 "Importera recept") against [FakeRecipeImportTandoor] in the
 * recorded shapes of 2.6.13 and 2.7.1: a link becomes a draft with foods and units by name, the
 * source's picture and the duplicates; a text is read on the device; a page without a recipe or
 * an unreachable link is "nothing found"; a
 * photo goes to the AI import with the space's provider, or is refused without one; the imported
 * draft saved through the editor's repository POSTs the recipe and then sets its picture from the
 * web (`image_url`); guests and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecipeImportRepositoryTest {
    private class Env(
        val graph: SyncTestGraph,
        val tandoor: FakeRecipeImportTandoor,
        val edits: FakeRecipeEditTandoor,
        val version: String,
    ) {
        val repo = graph.recipeImports

        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                if (result == DrainResult.DONE && outbox().none { it.state != OutboxState.FAILED }) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${outbox()}")
        }

        suspend fun outbox() = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()
    }

    private fun forEachVersion(block: suspend Env.() -> Unit) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version)
            val edits = FakeRecipeEditTandoor(version)
            val tandoor = FakeRecipeImportTandoor(edits, version).also { it.install(graph.server) }
            try {
                runBlocking {
                    val account = graph.account
                    graph.real.accountDao().upsert(ServerAccountEntity(account.id, account.baseUrl, account.userId, account.spaceId))
                    assertTrue(graph.engine.run(SyncReason.FIRST_SYNC).error == null)
                    Env(graph, tandoor, edits, version).block()
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

    @Test
    fun aLinkBecomesADraftWithFoodsUnitsPictureAndDuplicates() = forEachVersion {
        val result = ok(repo.import(RecipeImportSource.Link(" ${FakeRecipeImportTandoor.KNOWN_URL} ")))
        val draft = result.draft
        assertEquals("Pannkakor", draft.name)
        assertEquals(4, draft.servings)
        assertEquals(25, draft.workingMinutes)
        assertEquals(FakeRecipeImportTandoor.KNOWN_URL, draft.sourceUrl)
        assertEquals(FakeRecipeImportTandoor.IMAGE, draft.imageUrl)
        assertEquals(listOf(FakeRecipeImportTandoor.IMAGE, "${FakeRecipeImportTandoor.IMAGE}?2"), result.imageUrls)
        assertEquals(listOf("Pannkakor"), result.duplicates.map { it.name })
        // Every keyword comes along (import_keyword is false for all in the recordings); a known one keeps its id.
        assertEquals(listOf(5L to "Husmanskost", null to "Mellanmål", null to "Skippad"), draft.keywords.map { it.id to it.name })
        assertEquals(2, draft.steps.size)
        val lines = draft.steps[0].ingredients
        assertEquals(DraftIngredient(amount = 2.5, unit = "dl", food = "vetemjöl"), lines[0])
        assertEquals(DraftIngredient(food = "salt", noAmount = true), lines[1])
        // A line the parser could not read keeps its text as the note.
        assertEquals(DraftIngredient(note = "en nypa kärlek", noAmount = true), lines[2])
        assertEquals(DraftIngredient(amount = 2.0, unit = "msk", food = "smör", note = "till stekning"), draft.steps[1].ingredients[0])
        val (call, body) = tandoor.calls.single()
        assertEquals("POST /api/recipe-from-source/", call)
        assertTrue(body, body.contains("\"url\":\"${FakeRecipeImportTandoor.KNOWN_URL}\""))
    }

    @Test
    fun aTextIsReadOnTheDeviceWithTheStoredUnits() = forEachVersion {
        val text = """
            Pannkakor
            4 portioner

            Ingredienser
            2,5 dl vetemjöl
            6 dl mjölk
            3 ägg
            salt

            Gör så här
            1. Vispa ihop mjöl och hälften av mjölken.
            2. Vispa i resten av mjölken och äggen.
        """.trimIndent()
        val result = ok(repo.import(RecipeImportSource.Text(text)))
        val draft = result.draft
        assertEquals("Pannkakor", draft.name)
        assertEquals(4, draft.servings)
        assertEquals(2, draft.steps.size)
        assertEquals(listOf("vetemjöl", "mjölk", "ägg", "salt"), draft.steps[0].ingredients.map { it.food })
        assertEquals(listOf("dl", "dl", null, null), draft.steps[0].ingredients.map { it.unit })
        assertEquals(DraftIngredient(food = "salt", noAmount = true), draft.steps[0].ingredients[3])
        assertEquals("Vispa ihop mjöl och hälften av mjölken.", draft.steps[0].instruction)
        assertEquals("Vispa i resten av mjölken och äggen.", draft.steps[1].instruction)
        assertTrue("no request for a text: ${tandoor.calls}", tandoor.calls.isEmpty())
    }

    @Test
    fun aPageWithoutARecipeOrAnUnreachableLinkIsNothingFound() = forEachVersion {
        val noRecipe = RecipeImportSource.Link(FakeRecipeImportTandoor.NO_RECIPE_URL)
        assertEquals(RecipeImportProblem.NOTHING_FOUND.toError(), failure(repo.import(noRecipe)))
        val unreachable = RecipeImportSource.Link("https://recipes.invalid/x")
        assertEquals(RecipeImportProblem.NOTHING_FOUND.toError(), failure(repo.import(unreachable)))
        assertEquals(RecipeImportProblem.EMPTY_INPUT.toError(), failure(repo.import(RecipeImportSource.Link("  "))))
        assertEquals(RecipeImportProblem.EMPTY_INPUT.toError(), failure(repo.import(RecipeImportSource.Text(""))))
        assertEquals(2, tandoor.calls.size)
    }

    @Test
    fun aPhotoGoesToTheAiImportWithTheSpacesProvider() = forEachVersion {
        val photo = RecipeImportSource.File(ByteArray(10) { it.toByte() }, "image/jpeg", "recept.jpg")
        assertEquals(RecipeImportProblem.AI_UNAVAILABLE.toError(), failure(repo.import(photo)))
        assertTrue(tandoor.calls.none { it.first.startsWith("POST /api/ai-import/") })
        tandoor.providerId = 7
        val result = ok(repo.import(photo))
        assertEquals("Pannkakor", result.draft.name)
        val (_, body) = tandoor.calls.last { it.first == "POST /api/ai-import/" }
        assertTrue(body, body.contains("name=\"ai_provider_id\"") && body.contains("\r\n7\r\n"))
        assertTrue(body, body.contains("filename=\"recept.jpg\""))
        val empty = RecipeImportSource.File(ByteArray(0), "image/jpeg", "x.jpg")
        assertEquals(RecipeImportProblem.EMPTY_INPUT.toError(), failure(repo.import(empty)))
        val huge = RecipeImportSource.File(ByteArray(RecipeImportProblem.MAX_FILE_BYTES + 1), "application/pdf", "x.pdf")
        assertEquals(RecipeImportProblem.FILE_TOO_LARGE.toError(), failure(repo.import(huge)))
    }

    @Test
    fun theImportedDraftSavedInTheEditorGetsItsPictureFromTheWeb() = forEachVersion {
        val draft = ok(repo.import(RecipeImportSource.Link(FakeRecipeImportTandoor.KNOWN_URL))).draft
        val id = ok(graph.recipeEdits.createRecipe(draft))
        assertTrue(id < 0)
        val rows = outbox()
        assertEquals(listOf(OutboxOps.CREATE, OutboxOps.RECIPE_IMAGE), rows.map { it.op })
        assertEquals(rows[0].id, rows[1].dependsOn)
        assertTrue(rows[1].payload.contains(FakeRecipeImportTandoor.IMAGE))
        drainAll()
        assertTrue(outbox().isEmpty())
        val created = edits.recipeNamed("Pannkakor")!!
        val imageCall = edits.callsTo("PUT", "/api/recipe/").single()
        val sent = imageCall.second
        assertTrue(sent, sent.contains("name=\"image_url\"") && sent.contains(FakeRecipeImportTandoor.IMAGE))
        assertTrue(created.toString(), created["image"].toString().contains("/media/recipes/"))
    }

    @Test
    fun aGuestAndDemoModeCannotImport() = forEachVersion {
        val link = RecipeImportSource.Link(FakeRecipeImportTandoor.KNOWN_URL)
        graph.permissions.setGuest(true)
        assertEquals(DataProblem.READ_ONLY.toError(), failure(repo.import(link)))
        assertEquals(DataProblem.READ_ONLY.toError(), failure(repo.import(RecipeImportSource.Text("Pannkakor"))))
        graph.permissions.setGuest(false)
        graph.sources.source.value = ActiveSource.Demo
        assertEquals(RecipeImportProblem.DEMO_MODE.toError(), failure(repo.import(link)))
        assertFalse(repo.observeAiImportAvailable().first())
        // A text needs no server: it works in demo mode too.
        assertEquals("Pannkakor", ok(repo.import(RecipeImportSource.Text("Pannkakor\n2 dl mjölk"))).draft.name)
        assertTrue(tandoor.calls.isEmpty())
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 6
    }
}
