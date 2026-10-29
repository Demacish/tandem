package casa.moller.tandem.core.sync.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.RecipeDisplay
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.PendingVersionNotice
import casa.moller.tandem.core.model.settings.RecoveryReason
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // Fas S (drycker lane): plus the three demo drinks (recipes with the keyword "Dryck").
        assertEquals(graph.demoContent.recipeIds.size + DEMO_DRINKS, recipes.size)
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
    fun everyDemoRecipeRendersWithoutBrokenTemplatesInBothLanguages() = runBlocking {
        useDemo()
        val languages = listOf(Locale.forLanguageTag("sv-SE") to NumberStyle.Swedish, Locale.ENGLISH to NumberStyle.English)
        for ((locale, numbers) in languages) {
            graph.demoLocale = locale
            assertTrue(graph.demo.ensureSeeded() is Outcome.Success)
            val preferences = graph.demoDatabase.referenceDao().getUserPreference(DEMO_ACCOUNT_ID)
            assertTrue("demo preferences are stored", preferences != null)

            for (id in graph.demoContent.recipeIds) {
                val recipe = graph.recipes.observeRecipe(id).first { it.recipe != null }.recipe!!
                val label = "$locale recipe $id"
                assertTrue("$label has steps", recipe.steps.isNotEmpty())
                assertTrue(recipe.imageUrl.orEmpty().startsWith("file:///android_asset/demo/images/"))
                for (servings in listOf(recipe.servings.toDouble(), recipe.servings * 2.0)) {
                    val display = RecipeDisplay.build(recipe, servings, UserPreferences.Default, numbers)
                    assertTrue("$label has no broken template", display.steps.none { it.hasBrokenTemplate })
                }
            }
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
    fun aLanguageChangeRewritesTheDemoAndKeepsWhatTheUserDid() = runBlocking {
        useDemo()
        graph.demoLocale = Locale.ENGLISH
        graph.demo.ensureSeeded()
        val english = graph.recipes.observeRecipes(RecipeQuery.All).first().map { it.name }.toSet()
        val pinned = graph.recipes.observeRecipes(RecipeQuery.All).first().filter { it.pinned }.map { it.id }.toSet()

        // The language picker: the new locale arrives with the configuration change.
        graph.demo.ensureSeeded(Locale.forLanguageTag("sv-SE"))

        val swedish = graph.recipes.observeRecipes(RecipeQuery.All).first()
        assertNotEquals(english, swedish.map { it.name }.toSet())
        assertEquals(pinned, swedish.filter { it.pinned }.map { it.id }.toSet())
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
        assertEquals("the photos stay too", 0, graph.imageCache.clears.get())

        graph.accountStore.remove(ACCOUNT_ID)
        graph.mirror.onSession(Session.NoAccount)

        assertTrue(recipes.getRecipeIds(ACCOUNT_ID).isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        assertEquals(null, graph.real.accountDao().get(ACCOUNT_ID))
        assertTrue("the image cache is emptied with the account", graph.imageCache.clears.get() >= 1)
    }

    @Test
    fun rowsALateResponseWroteAfterTheLogoutAreRemovedAtTheNextSessionChange() = runBlocking {
        graph.mirror.onSession(Session.LoggedIn(graph.account, "2.6.13", ServerCompatibility.SUPPORTED))
        graph.engine.run(SyncReason.FIRST_SYNC)
        graph.accountStore.remove(ACCOUNT_ID)
        graph.mirror.onSession(Session.NoAccount)
        // A detail and a page that were on their way: written without a server_account row.
        val recipes = graph.real.recipeDao()
        recipes.applyIndex(ACCOUNT_ID, listOf(RecipeIndexEntry(1, "Sen pyttipanna")))
        graph.real.syncStateDao().upsert(SyncStateEntity(accountId = ACCOUNT_ID, entity = "late"))
        graph.real.outboxDao().enqueueViewLog(ACCOUNT_ID, 1, "{}", 1)
        // Another stored account keeps its rows.
        val other = graph.account.copy(id = OTHER_ACCOUNT)
        graph.accountStore.upsert(other, "")
        recipes.applyIndex(OTHER_ACCOUNT, listOf(RecipeIndexEntry(5, "Köttbullar")))
        graph.imageCache.clears.set(0)

        graph.mirror.onSession(Session.LoggedIn(other, "2.6.13", ServerCompatibility.SUPPORTED))

        assertTrue(recipes.getRecipeIds(ACCOUNT_ID).isEmpty())
        assertTrue(graph.real.syncStateDao().observe(ACCOUNT_ID).first().isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        assertEquals(listOf(5L), recipes.getRecipeIds(OTHER_ACCOUNT))
        assertEquals(listOf(OTHER_ACCOUNT), graph.real.accountDao().getStoredAccountIds())
        assertEquals(1, graph.imageCache.clears.get())
    }

    @Test
    fun clearingAppDataAfterARecoveryEmptiesTheImageCache() = runBlocking {
        graph.mirror.onSession(Session.LoggedIn(graph.account, "2.6.13", ServerCompatibility.SUPPORTED))
        graph.accountStore.clearAll()

        // Recovery: nothing is removed while the store may just be unreadable.
        graph.health.report(RecoveryReason.ACCOUNTS_UNREADABLE)
        graph.mirror.onSession(Session.NoAccount)
        assertEquals(ACCOUNT_ID, graph.real.accountDao().get(ACCOUNT_ID)?.id)
        assertEquals(0, graph.imageCache.clears.get())

        // "Clear app data" acknowledges it; the session is NoAccount.
        graph.health.acknowledge()
        graph.mirror.onSession(Session.NoAccount)
        assertEquals(null, graph.real.accountDao().get(ACCOUNT_ID))
        assertEquals(1, graph.imageCache.clears.get())
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

    // --- In-app notice when no notification could be posted (plan B18, komplettering-3) ---

    private fun loggedIn(version: String, compatibility: ServerCompatibility = ServerCompatibility.SUPPORTED): Session.LoggedIn {
        val account = graph.account.copy(serverVersion = version)
        graph.sources.source.value = ActiveSource.Remote(account, graph.api)
        return Session.LoggedIn(account, version, compatibility)
    }

    @Test
    fun aTestedVersionIsToldInTheAppOnceWhenNoNotificationWasPosted() = runBlocking {
        graph.notifier.posted = false
        graph.mirror.onSession(loggedIn("2.6.13"))
        // The first run stores the version without any notice.
        assertNull(graph.settings.state.value.versionNotice)
        assertNull(graph.serverStatus.observeVersionNotice().first())

        graph.mirror.onSession(loggedIn("2.6.15"))
        assertEquals(PendingVersionNotice(ACCOUNT_ID, "2.6.13", "2.6.15"), graph.settings.state.value.versionNotice)
        val change = graph.serverStatus.observeVersionNotice().first()
        assertEquals(ServerVersionChange("2.6.13", "2.6.15", ServerCompatibility.SUPPORTED), change)

        graph.serverStatus.markVersionNoticeShown(change!!)
        assertNull(graph.settings.state.value.versionNotice)
        assertNull(graph.serverStatus.observeVersionNotice().first())

        // The same version again (the next check, the next app start): nothing new.
        graph.mirror.onSession(loggedIn("2.6.15"))
        assertNull(graph.settings.state.value.versionNotice)
    }

    @Test
    fun noInAppNoticeWhenTheNotificationWasPosted() = runBlocking {
        graph.notifier.posted = true
        graph.mirror.onSession(loggedIn("2.6.13"))
        graph.mirror.onSession(loggedIn("2.6.15"))

        assertEquals(1, graph.notifier.changes.size)
        assertNull(graph.settings.state.value.versionNotice)
        assertNull(graph.serverStatus.observeVersionNotice().first())
    }

    @Test
    fun noInAppNoticeForAnUntestedVersionWhichHasTheSafeModeBanner() = runBlocking {
        graph.notifier.posted = false
        graph.mirror.onSession(loggedIn("2.6.13"))
        graph.mirror.onSession(loggedIn("2.6.15"))
        assertNotNull(graph.settings.state.value.versionNotice)

        // A newer, untested version replaces the notice that was never shown: one message per change.
        graph.mirror.onSession(loggedIn("2.7.0", ServerCompatibility.UNTESTED_NEWER))
        assertEquals(2, graph.notifier.changes.size)
        assertNull(graph.settings.state.value.versionNotice)
        assertNull(graph.serverStatus.observeVersionNotice().first())
    }

    @Test
    fun noInAppNoticeInDemoModeOrForAnAccountThatIsGone() = runBlocking {
        val notice = PendingVersionNotice(ACCOUNT_ID, "2.6.13", "2.6.15")
        graph.settings.state.value = AppSettings(versionNotice = notice)
        loggedIn("2.6.15")
        assertNotNull(graph.serverStatus.observeVersionNotice().first())

        useDemo()
        assertNull(graph.serverStatus.observeVersionNotice().first())

        // After a logout the notice of the removed account is dropped.
        graph.accountStore.clearAll()
        graph.mirror.onSession(Session.NoAccount)
        assertNull(graph.settings.state.value.versionNotice)
    }

    @Test
    fun theDemoDatabaseIsSeparateFromTheRealOne() {
        assertNotEquals(graph.databases.get(DatabaseKind.REAL), graph.databases.get(DatabaseKind.DEMO))
    }

    private companion object {
        const val OTHER_ACCOUNT = "account-b"
        const val DEMO_DRINKS = 3
    }
}
