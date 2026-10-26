package casa.moller.tandem.core.sync.mealplan

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.database.entity.UserPreferenceEntity
import casa.moller.tandem.core.domain.mealplan.WeekShoppingMerge
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.WeekShoppingPreview
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.shopping.FakeShoppingTandoor
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * "Handla för veckan" (feature #16, plan 6.2 steps 1-4; Phase 2 "Klart när": with
 * `mealplan_autoadd_shopping` both on and off there are NO duplicate rows), end to end against a
 * stateful Tandoor with the meal plan ([FakeMealPlanTandoor]) and the shopping list
 * ([FakeShoppingTandoor]) answering in the recorded shapes of BOTH 2.6.13 and 2.7.1 (`fas2/`
 * fixtures of the grund lane):
 *
 * - **Off** (the app adds): one list recipe linked to each meal, sent through the outbox; asking
 *   again, on this phone or another one, adds nothing.
 * - **On** (the server added the rows: the web with the preference on, `addshopping`): such a meal is
 *   "redan på listan" whether the server's `shopping` flag says so or only the rows' `mealplan` link
 *   does, and only the other meals are added.
 * - A meal planned in the app with "Lägg på listan" ticked counts as on the list from the start
 *   (also offline, before the server answered), so the server's rows are the only ones.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WeekShoppingSyncTest {
    private class Env(val graph: SyncTestGraph, val meals: FakeMealPlanTandoor, val shop: FakeShoppingTandoor, val version: String) {
        val repo = graph.mealPlans

        suspend fun preview(): WeekShoppingPreview = ok(repo.weekShoppingPreview(MONDAY, SUNDAY))

        /** Everything selectable, with the default ticks (on-hand foods unticked). */
        fun everything(preview: WeekShoppingPreview): WeekShoppingSelection {
            val included = preview.meals.filter { WeekShoppingMerge.selectable(it) }.mapTo(HashSet()) { it.meal.id }
            val chosen = WeekShoppingMerge.defaultChosen(WeekShoppingMerge.lines(preview.meals, included))
            return WeekShoppingMerge.selection(preview.meals, included, chosen)
        }

        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                if (result == DrainResult.DONE && graph.real.outboxDao().observeCount(ACCOUNT_ID).first() == 0) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${graph.real.outboxDao().observeAll(ACCOUNT_ID).first()}")
        }

        /** The server's list recipes per meal (a meal with two would be a duplicate). */
        fun listRecipesByMeal(): Map<Long, Int> = shop.listRecipes().mapNotNull { it.mealplan }.groupingBy { it }.eachCount()

        fun <T> ok(outcome: Outcome<T>): T {
            assertTrue("[$version] $outcome", outcome is Outcome.Success)
            return (outcome as Outcome.Success).value
        }
    }

    private fun forEachVersion(block: suspend Env.() -> Unit) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version)
            val meals = FakeMealPlanTandoor(version)
            val shop = FakeShoppingTandoor(version, graph.clock)
            graph.server.handler = { request -> meals.answer(request) ?: shop.answer(request) }
            // `addshopping`: Tandoor puts the recipe on the list, linked to the meal (FACTS "Fas 2").
            meals.onAddShopping = { meal -> shop.serverAddMeal(meal.id, meal.recipe ?: 0, meal.servings, listOf("grädde", "pasta")) }
            try {
                runBlocking {
                    prepare(graph)
                    Env(graph, meals, shop, version).block()
                }
            } finally {
                graph.close()
            }
        }
    }

    private suspend fun prepare(graph: SyncTestGraph) {
        val account = graph.account
        val row = ServerAccountEntity(id = account.id, baseUrl = account.baseUrl, userId = account.userId, spaceId = account.spaceId)
        graph.real.accountDao().upsert(row)
        graph.real.referenceDao().replaceMealTypes(
            account.id,
            listOf(
                MealTypeEntity(account.id, FakeMealPlanTandoor.DINNER, "Middag", 0, "18:00:00"),
                MealTypeEntity(account.id, FakeMealPlanTandoor.BREAKFAST, "Frukost", 2, "07:30:00"),
            ),
        )
        // The recipes' ingredients are needed to choose them (stored details, as after opening them once).
        listOf(PASTA, MEATBALLS).forEach { assertTrue(graph.details.fetch(graph.remote, it, isBackground = false) is Outcome.Success) }
    }

    private suspend fun autoAdd(graph: SyncTestGraph, on: Boolean) {
        graph.real.referenceDao().upsertUserPreference(
            UserPreferenceEntity(
                accountId = ACCOUNT_ID,
                useFractions = false,
                ingredientDecimals = 2,
                defaultUnit = "g",
                useKj = false,
                showStepIngredients = true,
                defaultMealTypeId = null,
                shoppingAutoSyncSeconds = 5,
                mealPlanAutoAddShopping = on,
                mealPlanAutoExcludeOnHand = true,
                mealPlanAutoIncludeRelated = false,
                defaultDelayHours = 4.0,
                filterToSupermarket = false,
                shoppingRecentDays = 7,
                shoppingAddOnHand = false,
                commentsEnabled = true,
            ),
        )
    }

    /** Off: the app adds each meal once; asking again (here or on another phone) adds nothing. */
    @Test
    fun autoAddOffTheAppAddsEveryMealOnce() = forEachVersion {
        autoAdd(graph, on = false)
        val pasta = meals.web("2026-10-13T00:00:00", recipe = PASTA)
        val meatballs = meals.web("2026-10-15T00:00:00", recipe = MEATBALLS)
        val first = preview()
        assertFalse(first.autoAddOnServer)
        assertEquals(setOf(pasta, meatballs), first.meals.map { it.meal.id }.toSet())
        assertTrue("[$version] nothing is on the list yet", first.meals.none { it.alreadyOnList })
        assertTrue(first.meals.all { it.ingredients.isNotEmpty() })
        val selection = everything(first)
        assertEquals(setOf(pasta, meatballs), selection.ingredientIdsByMeal.keys)

        ok(repo.addWeekToShopping(selection))
        drainAll()
        assertEquals("[$version] one list recipe per meal", mapOf(pasta to 1, meatballs to 1), listRecipesByMeal())
        val rows = shop.all().groupBy { entry -> shop.listRecipe(entry.listRecipe ?: -1)?.mealplan }
        assertEquals(selection.ingredientIdsByMeal.getValue(pasta).size, rows.getValue(pasta).size)

        // Again on this phone: both are on the list now, and nothing is sent.
        val again = preview()
        assertTrue("[$version] ${again.meals}", again.meals.all { it.alreadyOnList })
        val posts = shop.requests("POST", "/api/shopping-list-recipe/").size
        ok(repo.addWeekToShopping(selection))
        drainAll()
        assertEquals(posts, shop.requests("POST", "/api/shopping-list-recipe/").size)

        // Another phone of the household reads the list and sees the meals as on it.
        val phoneB = SyncTestGraph(server = graph.server, databasePrefix = "b-", serverVersion = version)
        try {
            prepare(phoneB)
            val other = ok(phoneB.mealPlans.weekShoppingPreview(MONDAY, SUNDAY))
            assertTrue("[$version] ${other.meals}", other.meals.isNotEmpty() && other.meals.all { it.alreadyOnList })
            ok(phoneB.mealPlans.addWeekToShopping(selection))
            assertTrue(phoneB.real.outboxDao().getAllFor(ACCOUNT_ID, OutboxEntities.SHOPPING_LIST_RECIPE).isEmpty())
        } finally {
            // Not phoneB.close(): the server is this phone's too.
            phoneB.scope.cancel()
            phoneB.real.close()
            phoneB.demoDatabase.close()
        }
        assertEquals(mapOf(pasta to 1, meatballs to 1), listRecipesByMeal())
    }

    /** On: rows the server added for a meal (flag or only the link) are never added a second time. */
    @Test
    fun autoAddOnMealsTheServerPutOnTheListAreSkipped() = forEachVersion {
        autoAdd(graph, on = true)
        // The web planned these with the preference on: the server added their rows.
        val flagged = meals.web("2026-10-13T00:00:00", recipe = PASTA)
        shop.serverAddMeal(flagged, PASTA, 4.0, listOf("grädde"))
        meals.partnerEdit(flagged) { shopping = true }
        val linkedOnly = meals.web("2026-10-14T00:00:00", recipe = MEATBALLS)
        shop.serverAddMeal(linkedOnly, MEATBALLS, 4.0, listOf("köttfärs"))
        val fresh = meals.web("2026-10-16T00:00:00", recipe = PASTA)

        val preview = preview()
        assertTrue(preview.autoAddOnServer)
        val onList = preview.meals.filter { it.alreadyOnList }.map { it.meal.id }.toSet()
        assertEquals("[$version] the server's rows mark the meal", setOf(flagged, linkedOnly), onList)
        val selection = everything(preview)
        assertEquals(setOf(fresh), selection.ingredientIdsByMeal.keys)
        // Even a selection that names them (an old screen) adds nothing for them.
        val stale = selection.ingredientIdsByMeal + (flagged to setOf(1L)) + (linkedOnly to setOf(1L))
        ok(repo.addWeekToShopping(WeekShoppingSelection(stale)))
        drainAll()
        assertEquals("[$version] no duplicates", mapOf(flagged to 1, linkedOnly to 1, fresh to 1), listRecipesByMeal())
    }

    /** A meal planned in the app with "Lägg på listan" ticked is on the list before the server answers. */
    @Test
    fun aMealPlannedWithAddShoppingIsNeverAddedByTheAppToo() = forEachVersion {
        autoAdd(graph, on = true)
        graph.network.online.value = false
        val day = LocalDate(2026, 10, 16)
        val draft = MealPlanDraft(day, FakeMealPlanTandoor.DINNER, recipeId = PASTA, servings = 4.0, addToShopping = true)
        val local = ok(repo.create(draft))
        assertTrue(local < 0)
        val offline = preview()
        assertTrue("[$version] ${offline.meals}", offline.meals.single { it.meal.id == local }.alreadyOnList)
        ok(repo.addWeekToShopping(WeekShoppingSelection(mapOf(local to setOf(1L, 2L)))))
        assertTrue(graph.real.outboxDao().getAllFor(ACCOUNT_ID, OutboxEntities.SHOPPING_LIST_RECIPE).isEmpty())

        graph.network.online.value = true
        drainAll()
        val serverId = meals.all().single().id
        assertTrue(meals.meal(serverId)!!.shopping)
        val online = preview()
        assertTrue(online.meals.single { it.meal.id == serverId }.alreadyOnList)
        ok(repo.addWeekToShopping(everything(online)))
        drainAll()
        assertEquals("[$version] only the server's rows", mapOf(serverId to 1), listRecipesByMeal())
    }

    /** A planned recipe nobody opened yet is read for the preview (online), so its ingredients can be chosen. */
    @Test
    fun aPlannedRecipeThatWasNeverOpenedIsReadForThePreview() = forEachVersion {
        val shakshuka = meals.web("2026-10-17T00:00:00", recipe = NEVER_OPENED)
        assertTrue(graph.real.recipeDao().getDetail(ACCOUNT_ID, NEVER_OPENED) == null)
        val preview = preview()
        assertTrue("[$version] ${preview.meals}", preview.meals.single { it.meal.id == shakshuka }.ingredients.isNotEmpty())
        assertTrue(graph.server.requestsTo("GET", "/api/recipe/$NEVER_OPENED/").isNotEmpty())
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        val MONDAY = LocalDate(2026, 10, 12)
        val SUNDAY = LocalDate(2026, 10, 18)

        /** Recipes with recorded details in FakeTandoorServer (svamppasta, köttbullar). */
        const val PASTA = 2L
        const val MEATBALLS = 5L

        /** Shakshuka: recorded, but not fetched before the test. */
        const val NEVER_OPENED = 6L
        const val MAX_DRAINS = 10
    }
}
