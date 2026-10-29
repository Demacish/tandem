package casa.moller.tandem.core.sync.drink

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.drink.DrinkProblem
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Drinks and "Passar med" (#43, B20, plan §8.8) against a stateful Tandoor answering in the
 * recorded shapes of BOTH 2.6.13 and 2.7.1 ([FakeDrinkTandoor]): a drink created as a recipe with
 * "Dryck" and its type, pairings from both sides, a pairing with a kind of drink, the recipe read
 * again right before the PATCH (a partner's keyword is kept), a drink created offline, paired and
 * then synced (temporary ids rewritten), guests and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DrinkRepositoryTest {
    private class Env(val graph: SyncTestGraph, val tandoor: FakeDrinkTandoor, val version: String) {
        val repo = graph.drinks

        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                if (result == DrainResult.DONE && outbox().isEmpty()) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${outbox()}")
        }

        suspend fun outbox() = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()
    }

    private fun forEachVersion(block: suspend Env.() -> Unit) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version)
            val tandoor = FakeDrinkTandoor(version).also { it.install(graph.server) }
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

    // ------------------------------------------------------------------ a drink and both sides

    @Test
    fun aDrinkIsARecipeWithDryckAndAPairingFromTheDishLinksBoth() = forEachVersion {
        val created = ok(repo.createDrink(DrinkDraft(name = "Rioja Crianza", description = "Ca 120 kr", types = listOf("Vin"))))
        assertTrue(created.pending && created.id < 0)
        drainAll()

        val server = tandoor.created.values.single()
        assertTrue("[$version] internal like the web", server.internal)
        val post = tandoor.callsTo("POST", "/api/recipe/").single().second
        assertTrue("[$version] steps: [] is required: $post", post.contains("\"steps\":[]"))
        val dryck = checkNotNull(tandoor.keywordNamed("Dryck"))
        assertEquals("[$version] the type sits under Dryck", dryck.id, tandoor.keywordNamed("Vin")?.parent)
        assertEquals(listOf("Dryck", "Vin"), tandoor.keywordNamesOf(server.id).sorted())

        val drink = repo.observeDrinks().first().single()
        assertEquals(Drink(id = server.id, name = "Rioja Crianza", types = listOf("Vin"), note = "Ca 120 kr"), drink)
        assertEquals(listOf(drink), repo.observeDrinks(type = "vin").first())
        assertTrue(repo.observeIsDrink(server.id).first())
        assertFalse(repo.observeIsDrink(PASTA).first())

        val pairing = ok(repo.pairWithDrink(PASTA, server.id))
        assertEquals("Rioja Crianza", pairing.name)
        // Shown at once, before anything was sent.
        assertEquals(listOf("Rioja Crianza"), repo.observeRecipePairings(PASTA).first().drinks.map { it.name })
        assertEquals(listOf(PASTA), repo.observeRecipePairings(server.id).first().dishIds)
        drainAll()

        val keyword = checkNotNull(tandoor.keywordNamed("Rioja Crianza"))
        assertEquals("[$version] the pairing sits under Passar med", tandoor.keywordNamed("Passar med")?.id, keyword.parent)
        assertTrue("Rioja Crianza" in tandoor.keywordNamesOf(PASTA))
        assertTrue("Rioja Crianza" in tandoor.keywordNamesOf(server.id))
        val dish = repo.observeRecipePairings(PASTA).first()
        assertEquals(listOf(server.id), dish.drinks.map { it.id })
        assertEquals(keyword.id, dish.pairings.single().keywordId)
        assertFalse(dish.pairings.single().isTypeOnly)
        assertEquals(listOf(PASTA), repo.observeRecipePairings(server.id).first().dishIds)

        // From the drink's side too, with the same keyword (no second "Rioja Crianza").
        ok(repo.pairWithDrink(PANCAKES, server.id))
        drainAll()
        assertEquals(listOf(PASTA, PANCAKES), repo.observeRecipePairings(server.id).first().dishIds.sorted())
        assertEquals(1, tandoor.keywords.values.count { it.name == "Rioja Crianza" })

        ok(repo.removePairing(PANCAKES, keyword.id))
        drainAll()
        assertFalse("Rioja Crianza" in tandoor.keywordNamesOf(PANCAKES))
        assertEquals(listOf(PASTA), repo.observeRecipePairings(server.id).first().dishIds)
    }

    @Test
    fun aPairingWithAKindOfDrinkIsTextAndItsNameMustBeFree() = forEachVersion {
        val pending = ok(repo.addNewPairing(SALMON_DISH, "Kraftigt rött"))
        assertTrue(pending.isTypeOnly)
        assertEquals(listOf("Kraftigt rött"), repo.observeRecipePairings(SALMON_DISH).first().pairings.map { it.name })
        drainAll()

        val keyword = checkNotNull(tandoor.keywordNamed("Kraftigt rött"))
        assertEquals(tandoor.keywordNamed("Passar med")?.id, keyword.parent)
        val stored = repo.observeRecipePairings(SALMON_DISH).first().pairings.single()
        assertEquals(keyword.id, stored.keywordId)
        assertTrue("no drink carries it: shown as text", stored.isTypeOnly)
        assertEquals(listOf("Kraftigt rött"), repo.observePairings().first().map { it.name })

        // A dish keyword's name would be reused (and moved) by Tandoor: refused before anything is queued.
        val dishKeyword = tandoor.keywordNamesOf(PASTA).first()
        assertEquals(DrinkProblem.NAME_TAKEN.toError(), failure(repo.addNewPairing(PASTA, dishKeyword)))
        assertEquals(DrinkProblem.NAME_EMPTY.toError(), failure(repo.addNewPairing(PASTA, "  ")))
        assertTrue(outbox().isEmpty())

        // Added and removed again before the sync: nothing is sent.
        val friskt = ok(repo.addNewPairing(PANCAKES, "Friskt vitt"))
        ok(repo.removePairing(PANCAKES, friskt.keywordId))
        assertTrue("[$version] both cancel: ${outbox()}", outbox().isEmpty())
    }

    @Test
    fun theRecipeIsReadAgainBeforeThePatchSoAPartnersKeywordStays() = forEachVersion {
        val before = tandoor.keywordNamesOf(PASTA)
        ok(repo.addNewPairing(PASTA, "Kraftigt rött"))
        // Meanwhile the partner adds "Helg" on the web.
        tandoor.partnerAdds(PASTA, "Helg")
        drainAll()

        val after = tandoor.keywordNamesOf(PASTA)
        assertTrue("[$version] kept: $after", after.containsAll(before + "Helg" + "Kraftigt rött"))
        val reads = tandoor.callsTo("GET", "/api/recipe/$PASTA/")
        assertTrue("[$version] read right before the PATCH", reads.isNotEmpty())
    }

    @Test
    fun aDrinkCreatedOfflineCanBePairedBeforeItIsSent() = forEachVersion {
        val photo = File(graph.context.cacheDir, "drink-photo-$version.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        graph.network.online.value = false
        val created = ok(repo.createDrink(DrinkDraft(name = "Husets rosé", types = listOf("Vin"), imageUri = photo.path)))
        assertTrue(created.id < 0)
        ok(repo.pairWithDrink(PANCAKES, created.id))

        val pendingDish = repo.observeRecipePairings(PANCAKES).first()
        assertEquals(listOf(created.id), pendingDish.drinks.map { it.id })
        assertTrue(pendingDish.drinks.single().pending)
        assertEquals(listOf(PANCAKES), repo.observeRecipePairings(created.id).first().dishIds)
        assertEquals(
            setOf(OutboxOps.CREATE, OutboxOps.RECIPE_IMAGE),
            outbox().filter { it.entity == OutboxEntities.DRINK }.map { it.op }.toSet(),
        )

        graph.network.online.value = true
        drainAll()

        val server = tandoor.created.values.single()
        assertTrue("[$version] the photo was uploaded", server.hasImage)
        assertEquals(listOf("Dryck", "Husets rosé", "Vin"), tandoor.keywordNamesOf(server.id).sorted())
        assertTrue("Husets rosé" in tandoor.keywordNamesOf(PANCAKES))
        assertEquals(1, tandoor.keywords.values.count { it.name == "Husets rosé" })
        assertEquals(1, graph.drinkImages.deleted.size)
        // The temporary id still finds the drink (a screen may hold it).
        assertEquals(listOf(PANCAKES), repo.observeRecipePairings(created.id).first().dishIds)
        assertEquals(listOf(server.id), repo.observeRecipePairings(PANCAKES).first().drinks.map { it.id })
        assertEquals(listOf(server.id), repo.observeDrinks().first().map { it.id })
        assertTrue(graph.real.drinkDraftDao().getDrafts(ACCOUNT_ID).isEmpty())
    }

    @Test
    fun aGuestSeesPairingsButChangesNothing() = forEachVersion {
        val drink = ok(repo.createDrink(DrinkDraft(name = "Cava")))
        drainAll()
        val server = tandoor.created.values.single().id
        graph.permissions.setGuest(true)
        val readOnly = DataProblem.READ_ONLY.toError()
        assertEquals(readOnly, failure(repo.pairWithDrink(PASTA, server)))
        assertEquals(readOnly, failure(repo.addNewPairing(PASTA, "Bubbel")))
        assertEquals(readOnly, failure(repo.addPairing(PASTA, 1)))
        assertEquals(readOnly, failure(repo.removePairing(PASTA, 1)))
        assertEquals(readOnly, failure(repo.createDrink(DrinkDraft(name = "Prosecco"))))
        assertTrue("a guest queues nothing: ${outbox()}", outbox().isEmpty())
        assertEquals(listOf("Cava"), repo.observeDrinks().first().map { it.name })
        assertTrue(drink.pending)
    }

    // ------------------------------------------------------------------ demo mode

    @Test
    fun demoModeHasDrinksAndPairingsWithoutTheNetwork(): Unit = runBlocking {
        val graph = SyncTestGraph()
        try {
            graph.sources.source.value = ActiveSource.Demo
            assertTrue(graph.demo.ensureSeeded() is Outcome.Success)
            val repo = graph.drinks
            assertEquals(setOf("Rioja Crianza", "Ljus lager", "Äppelmust"), repo.observeDrinks().first().map { it.name }.toSet())
            assertEquals(listOf("Rioja Crianza"), repo.observeDrinks(type = "Vin").first().map { it.name })
            assertEquals(setOf("Ljus lager", "Äppelmust"), repo.observeRecipePairings(DEMO_TACOS).first().drinks.map { it.name }.toSet())
            val salmon = repo.observeRecipePairings(DEMO_SALMON).first().pairings.single()
            assertEquals("Friskt vitt", salmon.name)
            assertTrue(salmon.isTypeOnly)

            val added = ok(repo.addNewPairing(DEMO_SALMON, "Rosé"))
            assertTrue(added.keywordId > 0)
            assertEquals(listOf("Friskt vitt", "Rosé"), repo.observeRecipePairings(DEMO_SALMON).first().pairings.map { it.name })
            val drink = ok(repo.createDrink(DrinkDraft(name = "Lingondricka", types = listOf("Alkoholfritt"))))
            assertTrue(drink.id > 0 && !drink.pending)
            ok(repo.pairWithDrink(DEMO_SALMON, drink.id))
            assertTrue(drink.id in repo.observeRecipePairings(DEMO_SALMON).first().drinks.map { it.id })

            assertEquals(0, graph.demoDatabase.outboxDao().observeCount(DEMO_ACCOUNT_ID).first())
            assertTrue("demo mode sends nothing: ${graph.server.requests}", graph.server.requests.isEmpty())
            // Drinks are no dinner suggestions on Today.
            val drinks = repo.observeDrinks().first().map { it.id }.toSet()
            val suggestions = graph.home.observeSections().first().filter { it.type != HomeSectionType.PINNED }
            assertTrue(suggestions.none { section -> section.recipes.any { it.id in drinks } })
        } finally {
            graph.close()
        }
    }

    @Test
    fun aPhotoIsScaledToAtMost1600Pixels() {
        assertEquals(1600 to 1200, DrinkImageStore.scaledSize(4000, 3000))
        assertEquals(533 to 1600, DrinkImageStore.scaledSize(1000, 3000))
        assertEquals(1200 to 800, DrinkImageStore.scaledSize(1200, 800))
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 10
        const val SALMON_DISH = 1L
        const val PASTA = 2L
        const val PANCAKES = 3L
        const val DEMO_SALMON = 1L
        const val DEMO_TACOS = 10L
    }
}
