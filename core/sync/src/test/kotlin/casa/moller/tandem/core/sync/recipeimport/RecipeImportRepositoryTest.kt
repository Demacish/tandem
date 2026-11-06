package casa.moller.tandem.core.sync.recipeimport

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.recipeedit.FakeRecipeEditTandoor
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    // Fas 4 review (fix-data; plan §8.4 step 3): the caption or the page's text, when nothing else could read the link.
    @Test
    fun aTextGoesToTheAiImportWhenAskedFor() = forEachVersion {
        val text = RecipeImportSource.AiText("  Pannkakor: 3 dl mjöl, 6 dl mjölk och 3 ägg.  ")
        assertEquals("no provider", RecipeImportProblem.AI_UNAVAILABLE.toError(), failure(repo.import(text)))
        assertTrue(tandoor.calls.none { it.first.startsWith("POST /api/ai-import/") })
        tandoor.providerId = 7

        assertEquals("Pannkakor", ok(repo.import(text)).draft.name)

        val (_, body) = tandoor.calls.last { it.first == "POST /api/ai-import/" }
        assertTrue(body, body.contains("name=\"ai_provider_id\"") && body.contains("\r\n7\r\n"))
        assertTrue(body, body.contains("name=\"text\"") && body.contains("\r\nPannkakor: 3 dl mjöl, 6 dl mjölk och 3 ägg.\r\n"))
        assertFalse("no file", body.contains("filename="))
        assertEquals(RecipeImportProblem.EMPTY_INPUT.toError(), failure(repo.import(RecipeImportSource.AiText(" "))))
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
        val imageCall = edits.callsTo("PUT", "/api/recipe/").single()
        // The fixtures hold a "Pannkakor" too (fas1): the created one is the recipe the image went to.
        val createdId = Regex("/api/recipe/(\\d+)/image/").find(imageCall.first)!!.groupValues[1].toLong()
        val created = edits.recipe(createdId)!!
        val sent = imageCall.second
        assertTrue(sent, sent.contains("name=\"image_url\"") && sent.contains(FakeRecipeImportTandoor.IMAGE))
        assertTrue(created.toString(), created["image"].toString().contains("/media/recipes/"))
    }

    @Test
    fun aPageReadInTheAppGoesToTheServerWithItsLink() = forEachVersion {
        // #38: the site blocks the server (400 "Connection Refused."), the app's WebView reads it.
        val blocked = "https://example.com/blockerad"
        assertEquals(RecipeImportProblem.NOTHING_FOUND.toError(), failure(repo.import(RecipeImportSource.Link(blocked))))
        val html = "<html><head><script type=\"application/ld+json\">{}</script></head><body>100% smör</body></html>"
        val result = ok(repo.import(RecipeImportSource.Page(blocked, html)))
        assertEquals("Pannkakor", result.draft.name)
        assertEquals(blocked, result.draft.sourceUrl)
        val (_, body) = tandoor.calls.last()
        val sent = Json.parseToJsonElement(body) as JsonObject
        assertEquals(blocked, (sent["url"] as JsonPrimitive).content)
        assertEquals(html, (sent["data"] as JsonPrimitive).content)
        assertEquals(RecipeImportProblem.EMPTY_INPUT.toError(), failure(repo.import(RecipeImportSource.Page(blocked, " "))))
    }

    @Test
    fun theBackgroundSyncIsPausedWhileTheServerImports() = forEachVersion {
        // Plan §10 Fas 3 "Klart när": "bakgrundssynken pausas under tiden" (a link, a page, a photo, the step sort).
        val seen = mutableListOf<Boolean>()
        tandoor.duringCall = { seen += graph.guard.check(ACCOUNT_ID, background = true) != null }
        tandoor.providerId = 7
        ok(repo.import(RecipeImportSource.Link(FakeRecipeImportTandoor.KNOWN_URL)))
        ok(repo.import(RecipeImportSource.Page("https://example.com/x", "<html></html>")))
        ok(repo.import(RecipeImportSource.File(ByteArray(10) { it.toByte() }, "image/jpeg", "recept.jpg")))
        ok(repo.sortIngredientsIntoSteps(SampleTwoSteps))
        assertEquals(listOf(true, true, true, true), seen)
        // Requests the user starts still go out, and the pause ends with the import.
        assertEquals(null, graph.guard.check(ACCOUNT_ID, background = true))
    }

    @Test
    fun theAiMovesTheIngredientLinesToTheirSteps() = forEachVersion {
        // #40: refused without a provider; with one, the last line of step 1 moves to step 2.
        assertEquals(RecipeImportProblem.AI_UNAVAILABLE.toError(), failure(repo.sortIngredientsIntoSteps(SampleTwoSteps)))
        assertTrue(tandoor.calls.none { it.first.startsWith("POST /api/ai-step-sort/") })
        tandoor.providerId = 7
        val sorted = ok(repo.sortIngredientsIntoSteps(SampleTwoSteps))
        assertEquals(listOf("vetemjöl", "mjölk"), sorted.steps[0].ingredients.map { it.food })
        assertEquals(listOf("smör"), sorted.steps[1].ingredients.map { it.food })
        assertEquals(SampleTwoSteps.steps.map { it.instruction }, sorted.steps.map { it.instruction })
        assertEquals(SampleTwoSteps.imageUrl, sorted.imageUrl)
        val (_, body) = tandoor.calls.last { it.first == "POST /api/ai-step-sort/" }
        assertTrue(body, body.contains("\"food\":{\"name\":\"smör\"}"))
        // An AI that drops a step: the draft is kept as it was.
        tandoor.stepSortDropsAStep = true
        assertEquals(RecipeImportProblem.STEP_SORT_CHANGED.toError(), failure(repo.sortIngredientsIntoSteps(SampleTwoSteps)))
        graph.permissions.setGuest(true)
        assertEquals(DataProblem.READ_ONLY.toError(), failure(repo.sortIngredientsIntoSteps(SampleTwoSteps)))
        graph.permissions.setGuest(false)
        graph.sources.source.value = ActiveSource.Demo
        assertEquals(RecipeImportProblem.DEMO_MODE.toError(), failure(repo.sortIngredientsIntoSteps(SampleTwoSteps)))
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
        /** An AI import's draft: every line in the first step. */
        val SampleTwoSteps = RecipeDraft(
            name = "Pannkakor",
            steps = listOf(
                DraftStep(
                    instruction = "Vispa en smet.",
                    ingredients = listOf(
                        DraftIngredient(amount = 2.5, unit = "dl", food = "vetemjöl"),
                        DraftIngredient(amount = 6.0, unit = "dl", food = "mjölk"),
                        DraftIngredient(amount = 2.0, unit = "msk", food = "smör"),
                    ),
                ),
                DraftStep(instruction = "Stek i smör."),
            ),
            imageUrl = "https://example.com/bild.jpg",
        )

        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 6
    }
}
