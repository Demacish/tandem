package casa.moller.tandem.core.sync.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.assistant.AssistantLockCheck
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.shopping.ShoppingInput
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.model.assistant.AssistantRefusal
import casa.moller.tandem.core.model.assistant.AssistantResult
import casa.moller.tandem.core.model.assistant.AssistantSettings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.shopping.ShoppingSyncEnv
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.testing.data.FakeAssistantSettingsRepository
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 5 (funktioner lane, plan §8.7 "App Functions"): the assistant's actions on the REAL
 * repositories of a [casa.moller.tandem.core.sync.testing.SyncTestGraph] and a Tandoor answering in
 * the recorded shapes of 2.6.13 and 2.7.1. "Klart när": "lägg till mjölk på listan" puts "mjölk"
 * on the list through the outbox, parsed like Handla's "Lägg till", and the server gets it when
 * the outbox drains. Plus every refusal (off, locked, logged out, guest, safe mode) and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DefaultAssistantActionsTest {

    private class Setup(val env: ShoppingSyncEnv) {
        val settings = FakeAssistantSettingsRepository(AssistantSettings(enabled = true))

        @Volatile
        var unlocked = true
        val days = FakeCalendarDays(env.graph.clock.today())
        val actions = DefaultAssistantActions(
            settings = settings,
            lock = AssistantLockCheck { unlocked },
            sessions = env.graph.sessions,
            permissions = env.graph.permissions,
            shopping = env.graph.shopping,
            parser = env.graph.parser,
            mealPlans = env.graph.mealPlans,
            recipes = env.graph.recipes,
            days = days,
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(env.graph.clock.millis)
            },
        )

        /** The `shopping_entry` rows waiting in the real database's outbox. */
        suspend fun entryRows(): Int = env.graph.real.outboxDao().getAllFor(ACCOUNT_ID, OutboxEntities.SHOPPING_ENTRY).size
    }

    private fun forEachVersion(block: suspend Setup.() -> Unit) = ShoppingSyncEnv.forEachVersion { Setup(this).block() }

    private fun <T> done(result: AssistantResult<T>): T = when (result) {
        is AssistantResult.Done -> result.value
        is AssistantResult.Refused -> throw AssertionError("refused: $result")
    }

    private fun refusal(result: AssistantResult<*>): AssistantRefusal? = (result as? AssistantResult.Refused)?.refusal

    // ------------------------------------------------------------------ "Klart när"

    @Test
    fun milkGoesOnTheListThroughTheOutboxAndReachesTheServer() = forEachVersion {
        env.offline()

        val added = done(actions.addToShoppingList("mjölk"))

        assertEquals(listOf("mjölk"), added.items.map { it.food })
        val entry = env.named("mjölk")
        assertNotNull("[${env.version}] on the list at once", entry)
        assertTrue("[${env.version}] waits in the outbox", entry!!.pending || entry.isLocalOnly)
        assertEquals(1, entryRows())
        assertTrue("[${env.version}] nothing went out offline", env.tandoor.calls.isEmpty())

        env.online()
        env.drainAll()
        assertEquals(listOf("mjölk"), env.tandoor.all().map { it.food })
    }

    @Test
    fun theTextIsParsedLikeTypingItIntoLaggTill() = forEachVersion {
        env.offline()
        val text = "mjölk, 2 dl grädde; 6 ägg"
        val expected = ShoppingInput.parse(env.graph.parser, ShoppingInput.splitItems(text)).map { ShoppingInput.draft(it, emptySet()) }

        val added = done(actions.addToShoppingList(text))

        assertEquals(expected.map { it.foodName }, added.items.map { it.food })
        assertEquals(listOf("mjölk", "grädde", "ägg"), added.items.map { it.food })
        val cream = env.named("grädde")!!
        assertEquals(2.0, cream.amount, 0.0)
        assertEquals("dl", cream.unit?.name)
        assertEquals(6.0, env.named("ägg")!!.amount, 0.0)
        assertEquals(3, entryRows())
    }

    @Test
    fun anAmountAndUnitReplaceWhatTheParserFoundForOneItemOnly() = forEachVersion {
        env.offline()

        done(actions.addToShoppingList("mjölk", amount = 2.0, unit = "l"))
        val milk = env.named("mjölk")!!
        assertEquals(2.0, milk.amount, 0.0)
        assertEquals("l", milk.unit?.name)

        assertEquals(AssistantRefusal.AMOUNT_FOR_SEVERAL_ITEMS, refusal(actions.addToShoppingList("mjölk, bröd", amount = 2.0)))
        assertEquals(AssistantRefusal.INVALID_AMOUNT, refusal(actions.addToShoppingList("bröd", amount = -1.0)))
        assertEquals(AssistantRefusal.INVALID_AMOUNT, refusal(actions.addToShoppingList("bröd", amount = Double.NaN)))
        assertEquals(AssistantRefusal.NOTHING_TO_ADD, refusal(actions.addToShoppingList(" , \n ")))
        assertEquals("only the milk", 1, entryRows())
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun turnedOffOrLockedRefusesEverythingUntilAllowed() = forEachVersion {
        env.offline()
        settings.state.value = AssistantSettings(enabled = false)
        assertEquals(AssistantRefusal.TURNED_OFF, refusal(actions.addToShoppingList("mjölk")))
        assertEquals(AssistantRefusal.TURNED_OFF, refusal(actions.shoppingList()))
        assertEquals(AssistantRefusal.TURNED_OFF, refusal(actions.mealPlan()))
        assertEquals(AssistantRefusal.TURNED_OFF, refusal(actions.searchRecipes("gröt")))
        assertEquals(AssistantRefusal.TURNED_OFF, refusal(actions.recipe(1)))

        settings.state.value = AssistantSettings(enabled = true)
        unlocked = false
        assertEquals(AssistantRefusal.LOCKED, refusal(actions.addToShoppingList("mjölk")))
        assertEquals(AssistantRefusal.LOCKED, refusal(actions.shoppingList()))
        assertEquals(AssistantRefusal.LOCKED, refusal(actions.mealPlan()))
        assertEquals(AssistantRefusal.LOCKED, refusal(actions.searchRecipes("gröt")))
        assertEquals(AssistantRefusal.LOCKED, refusal(actions.recipe(1)))
        assertEquals("nothing stored while refused", 0, entryRows())

        // "Tillåt assistenten när appen är låst".
        settings.state.value = AssistantSettings(enabled = true, allowWhenLocked = true)
        done(actions.addToShoppingList("mjölk"))
        assertEquals(1, entryRows())
    }

    @Test
    fun loggedOutGuestAndSafeModeAreRefusedClearly() = forEachVersion {
        env.offline()
        val loggedIn = env.graph.sessions.sessionState.value as Session.LoggedIn

        env.graph.sessions.sessionState.value = Session.NoAccount
        assertEquals(AssistantRefusal.NOT_LOGGED_IN, refusal(actions.addToShoppingList("mjölk")))
        assertEquals(AssistantRefusal.NOT_LOGGED_IN, refusal(actions.shoppingList()))
        env.graph.sessions.sessionState.value = Session.RecoveryNeeded(emptySet())
        assertEquals(AssistantRefusal.NOT_LOGGED_IN, refusal(actions.mealPlan()))

        // A guest reads but never writes (#42).
        env.graph.sessions.sessionState.value = loggedIn
        env.graph.permissions.setGuest(true)
        assertEquals(AssistantRefusal.READ_ONLY, refusal(actions.addToShoppingList("mjölk")))
        done(actions.shoppingList())
        done(actions.mealPlan())
        env.graph.permissions.setGuest(false)

        // Safe mode: changes would wait unsent, so none are taken; reading works.
        env.graph.sessions.sessionState.value = loggedIn.copy(compatibility = ServerCompatibility.UNTESTED_NEWER)
        assertEquals(AssistantRefusal.SAFE_MODE, refusal(actions.addToShoppingList("mjölk")))
        done(actions.shoppingList())
        assertEquals(0, entryRows())
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun theListHasTheUncheckedItemsOnly() = forEachVersion {
        env.tandoor.seed("mjölk", 2.0, "l")
        val bread = env.tandoor.seed("bröd")
        env.read()
        assertTrue(env.repo.setChecked(listOf(bread), true) is Outcome.Success)

        val items = done(actions.shoppingList())

        assertEquals(listOf("mjölk"), items.map { it.food })
        assertEquals(2.0, items.single().amount!!, 0.0)
        assertEquals("l", items.single().unit)
        assertEquals(AssistantRefusal.LIST_NOT_FOUND, refusal(actions.shoppingList("Helgen")))
    }

    // ------------------------------------------------------------------ demo mode

    @Test
    fun demoModeWorksOnTheDemoDataWithoutTheNetwork() = forEachVersion {
        env.graph.sources.source.value = ActiveSource.Demo
        env.graph.sessions.sessionState.value = Session.Demo
        assertTrue(env.graph.demo.ensureSeeded() is Outcome.Success)
        val before = env.graph.server.requests.size

        // A named list in any case; an unknown one names the lists there are.
        val added = done(actions.addToShoppingList("kaffe", list = "veckohandling"))
        assertEquals("Veckohandling", added.list)
        val missing = actions.addToShoppingList("kaffe", list = "Fest") as AssistantResult.Refused
        assertEquals(AssistantRefusal.LIST_NOT_FOUND, missing.refusal)
        assertTrue("${missing.detail}", missing.detail!!.contains("Veckohandling") && missing.detail!!.contains("Helgen"))

        val items = done(actions.shoppingList("Veckohandling"))
        assertTrue(items.any { it.food == "kaffe" })
        assertTrue(items.any { it.food == "mjölk" })
        assertTrue(items.all { "Veckohandling" in it.lists })

        val today = days.today()
        val planned = env.graph.mealPlans.observeWeek(MealPlanWeek.startOf(today)).first().days.first { it.date == today }.meals
        val day = done(actions.mealPlan())
        assertEquals(today, day.date)
        assertFalse("the demo plans something for today", day.meals.isEmpty())
        assertEquals(planned.map { it.displayTitle }.sorted(), day.meals.map { it.title }.sorted())

        val found = done(actions.searchRecipes("gröt"))
        val stored = env.graph.recipes.observeRecipes(RecipeQuery(text = "gröt")).first()
        assertEquals(stored.map { it.id }, found.map { it.id })
        assertTrue(found.isNotEmpty())
        assertEquals(found.first().name, done(actions.recipe(found.first().id)).name)
        assertEquals(AssistantRefusal.RECIPE_NOT_FOUND, refusal(actions.recipe(987_654)))
        assertEquals(AssistantRefusal.EMPTY_QUERY, refusal(actions.searchRecipes("  ")))

        assertEquals("demo mode sends nothing", before, env.graph.server.requests.size)
    }
}
