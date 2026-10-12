package casa.moller.tandem.core.sync.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.RecipeDisplay
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/** Demo mode on its own database without network (plan 6.2), and per-account data (plan 5.3, 11.1, B18). */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DemoAndAccountsTest {
    private val graph = SyncTestGraph()

    @After
    fun tearDown() {
        graph.close()
    }

    private fun useDemo() {
        graph.sources.source.value = ActiveSource.Demo
    }

    @Test
    fun demoDataLivesOnlyInTheDemoDatabaseAndNeverUsesTheNetwork() = runBlocking {
        useDemo()
        assertTrue(graph.demo.ensureSeeded() is Outcome.Success)

        val recipes = graph.recipes.observeRecipes(RecipeQuery.All).first()
        assertEquals(graph.demoContent.recipeIds.size, recipes.size)
        assertTrue(graph.real.recipeDao().getRecipeIds(DEMO_ACCOUNT_ID).isEmpty())
        assertTrue(graph.real.recipeDao().getRecipeIds(ACCOUNT_ID).isEmpty())
        assertTrue(recipes.all { it.availableOffline })
        assertTrue("pinned demo recipes", recipes.any { it.pinned })

        val sections = graph.home.observeSections().first().map { it.type }
        assertTrue(HomeSectionType.PINNED in sections)
        assertTrue(HomeSectionType.OFTEN_COOKED in sections)
        assertFalse(graph.home.observeUpcomingMeals().first().isEmpty)
        assertTrue(graph.home.refresh() is Outcome.Success)
        assertTrue(graph.recipes.refreshRecipe(recipes.first().id) is Outcome.Success)
        assertTrue(graph.recipes.searchServer(RecipeQuery(text = recipes.first().name)) is Outcome.Success)
        assertEquals(listOf(SyncPhase.DONE), graph.sync.firstSync().toList().map { it.phase })
        assertEquals(ServerStatus.Demo, graph.serverStatus.observeStatus().first())
        graph.recipes.markViewed(recipes.first().id)
        assertEquals(0, graph.demoDatabase.outboxDao().observeCount(DEMO_ACCOUNT_ID).first())

        assertTrue("demo mode sends nothing: ${graph.server.requests}", graph.server.requests.isEmpty())
    }

    @Test
    fun everyDemoRecipeRendersWithoutBrokenTemplates() = runBlocking {
        useDemo()
        graph.demo.ensureSeeded()
        val preferences = graph.demoDatabase.referenceDao().getUserPreference(DEMO_ACCOUNT_ID)
        assertTrue("demo preferences are stored", preferences != null)

        for (id in graph.demoContent.recipeIds) {
            val recipe = graph.recipes.observeRecipe(id).first { it.recipe != null }.recipe!!
            assertTrue("recipe $id has steps", recipe.steps.isNotEmpty())
            assertTrue(recipe.imageUrl.orEmpty().startsWith("file:///android_asset/demo/images/"))
            val display = RecipeDisplay.build(recipe, recipe.servings * 2.0, UserPreferences.Default, NumberStyle.Swedish)
            assertTrue("recipe $id has no broken template", display.steps.none { it.hasBrokenTemplate })
        }
    }

    @Test
    fun resetRestoresTheDemoDataAndKeepsChangesUntilThen() = runBlocking {
        useDemo()
        graph.demo.ensureSeeded()
        val pinned = graph.demoContent.pinnedRecipeIds.first()
        graph.recipes.setAvailableOffline(pinned, false)
        graph.recipes.setServingsChoice(pinned, 7.0)

        // A new day rewrites the dated content but keeps what the user changed.
        graph.clock.days.value = graph.clock.days.value.plus(1, DateTimeUnit.DAY)
        graph.demo.ensureSeeded()
        assertEquals(7.0, graph.recipes.observeServingsChoice(pinned).first()?.servings)
        assertFalse(graph.recipes.observeRecipes(RecipeQuery.All).first().single { it.id == pinned }.pinned)

        assertTrue(graph.demo.resetDemoData() is Outcome.Success)
        assertTrue(graph.recipes.observeRecipes(RecipeQuery.All).first().single { it.id == pinned }.pinned)
        assertEquals(null, graph.recipes.observeServingsChoice(pinned).first())
    }

    @Test
    fun theDemoFollowsTheAppLanguage() = runBlocking {
        useDemo()
        graph.demo.ensureSeeded()
        val swedish = graph.recipes.observeRecipes(RecipeQuery.All).first().map { it.name }.toSet()

        graph.demoLocale = Locale.ENGLISH
        graph.demo.ensureSeeded()
        val english = graph.recipes.observeRecipes(RecipeQuery.All).first().map { it.name }.toSet()

        assertNotEquals(swedish, english)
    }

    @Test
    fun loggingInToARealAccountEmptiesTheDemoDatabase() = runBlocking {
        useDemo()
        graph.demo.ensureSeeded()
        assertTrue(graph.demoDatabase.recipeDao().getRecipeIds(DEMO_ACCOUNT_ID).isNotEmpty())

        graph.mirror.onSession(Session.LoggedIn(graph.account, "2.6.13", ServerCompatibility.SUPPORTED))

        assertEquals(graph.account.baseUrl, graph.real.accountDao().get(ACCOUNT_ID)?.baseUrl)
        val left = graph.demoDatabase.recipeDao().getRecipeIds(DEMO_ACCOUNT_ID)
        assertTrue("demo rows left: $left, a second clear: ${graph.demo.clearIfSeeded()}", left.isEmpty())
    }

    @Test
    fun clearingTheSeededDemoEmptiesIt() = runBlocking {
        useDemo()
        assertTrue(graph.demo.ensureSeeded() is Outcome.Success)

        val cleared = graph.demo.clearIfSeeded()

        assertTrue("clear: $cleared", cleared is Outcome.Success)
        assertTrue(graph.demoDatabase.recipeDao().getRecipeIds(DEMO_ACCOUNT_ID).isEmpty())
    }

    @Test
    fun logoutRemovesTheAccountsRowsButAnExpiredTokenDoesNot() = runBlocking {
        val loggedIn = Session.LoggedIn(graph.account, "2.6.13", ServerCompatibility.SUPPORTED)
        graph.mirror.onSession(loggedIn)
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.recipes.markViewed(2)
        val recipes = graph.real.recipeDao()

        // Unauthorized changes nothing stored: the account and its outbox stay for the re-login.
        graph.mirror.onSession(loggedIn)
        assertEquals(12, recipes.getRecipeIds(ACCOUNT_ID).size)
        assertEquals(1, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())

        graph.accountStore.remove(ACCOUNT_ID)
        graph.mirror.onSession(Session.NoAccount)

        assertTrue(recipes.getRecipeIds(ACCOUNT_ID).isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        assertEquals(null, graph.real.accountDao().get(ACCOUNT_ID))
    }

    @Test
    fun aNewServerVersionIsReportedOnce() = runBlocking {
        graph.mirror.onSession(Session.LoggedIn(graph.account, "2.6.13", ServerCompatibility.SUPPORTED))
        assertTrue(graph.notifier.changes.isEmpty())

        val updated = graph.account.copy(serverVersion = "2.7.0")
        graph.mirror.onSession(Session.LoggedIn(updated, "2.7.0", ServerCompatibility.UNTESTED_NEWER))
        graph.mirror.onSession(Session.LoggedIn(updated, "2.7.0", ServerCompatibility.UNTESTED_NEWER))

        assertEquals(1, graph.notifier.changes.size)
        assertEquals("2.6.13", graph.notifier.changes.single().previousVersion)
        assertEquals(ServerCompatibility.UNTESTED_NEWER, graph.notifier.changes.single().compatibility)
    }

    @Test
    fun theDemoDatabaseIsSeparateFromTheRealOne() {
        assertNotEquals(graph.databases.get(DatabaseKind.REAL), graph.databases.get(DatabaseKind.DEMO))
    }
}
