package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/** What the recipe editor's tests share: one [SyncTestGraph] and one [FakeRecipeEditTandoor] per recorded version. */
internal class RecipeEditEnv(val graph: SyncTestGraph, val tandoor: FakeRecipeEditTandoor, val version: String) {
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

    companion object {
        private val VERSIONS = listOf("2.6.13", "2.7.1")
        private const val MAX_DRAINS = 10

        /** Runs [block] once per recorded version, each with a fresh graph and fake, after a first sync. */
        fun forEachVersion(block: suspend RecipeEditEnv.() -> Unit) {
            for (version in VERSIONS) {
                val graph = SyncTestGraph(serverVersion = version)
                val tandoor = FakeRecipeEditTandoor(version).also { it.install(graph.server) }
                try {
                    runBlocking {
                        val account = graph.account
                        graph.real.accountDao().upsert(ServerAccountEntity(account.id, account.baseUrl, account.userId, account.spaceId))
                        assertTrue(graph.engine.run(SyncReason.FIRST_SYNC).error == null)
                        RecipeEditEnv(graph, tandoor, version).block()
                    }
                } finally {
                    graph.close()
                }
            }
        }
    }
}

internal fun newRecipe(name: String = "Fiskgratäng", photo: File? = null) = RecipeDraft(
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
