package casa.moller.tandem.feature.shell.navigation

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.navigation.TimersKey
import casa.moller.tandem.feature.mealplan.navigation.WeekAgendaKey
import casa.moller.tandem.feature.pantry.navigation.MakeNowKey
import casa.moller.tandem.feature.recipes.navigation.RecipeDetailKey
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationStateTest {

    private val savedTabs = mutableListOf<ShellTab>()
    private val navigation = ShellNavigationState(
        stacks = ShellTab.entries.associateWith { NavBackStack<NavKey>(it.root) },
        onTabChanged = { savedTabs += it },
    )

    @Test
    fun startsOnToday() {
        assertEquals(ShellTab.TODAY, navigation.currentTab)
        assertEquals(listOf<NavKey>(TodayKey), navigation.backStack)
    }

    @Test
    fun otherTabsSitOnTopOfToday() {
        navigation.selectTab(ShellTab.MORE)
        assertEquals(listOf<NavKey>(TodayKey, MoreKey), navigation.backStack)
        assertEquals(listOf(ShellTab.MORE), savedTabs)

        navigation.navigate(LanguageKey)
        assertEquals(listOf<NavKey>(TodayKey, MoreKey, LanguageKey), navigation.backStack)
        assertEquals(LanguageKey, navigation.currentKey)
    }

    @Test
    fun backGoesUpInsideTheTabThenToToday() {
        navigation.selectTab(ShellTab.MORE)
        navigation.navigate(AboutKey)
        navigation.navigate(LicensesKey)

        assertTrue(navigation.goBack())
        assertEquals(AboutKey, navigation.currentKey)
        assertTrue(navigation.goBack())
        assertEquals(MoreKey, navigation.currentKey)
        assertTrue(navigation.goBack())
        assertEquals(ShellTab.TODAY, navigation.currentTab)
        assertFalse("back on Today leaves the app", navigation.goBack())
    }

    @Test
    fun eachTabKeepsItsOwnStack() {
        navigation.selectTab(ShellTab.MORE)
        navigation.navigate(LanguageKey)
        navigation.selectTab(ShellTab.RECIPES)
        assertEquals(listOf<NavKey>(TodayKey, RecipesKey), navigation.backStack)

        navigation.selectTab(ShellTab.MORE)
        assertEquals(LanguageKey, navigation.currentKey)
    }

    @Test
    fun reselectingATabReturnsToItsFirstScreen() {
        navigation.selectTab(ShellTab.MORE)
        navigation.navigate(AboutKey)
        navigation.navigate(LicenseTextKey(BundledLicense.DM_SANS))

        navigation.selectTab(ShellTab.MORE)

        assertEquals(listOf<NavKey>(TodayKey, MoreKey), navigation.backStack)
    }

    @Test
    fun openingTheSameScreenTwiceDoesNotStackIt() {
        navigation.selectTab(ShellTab.MORE)
        navigation.navigate(LanguageKey)
        navigation.navigate(LanguageKey)
        assertEquals(listOf<NavKey>(TodayKey, MoreKey, LanguageKey), navigation.backStack)
    }

    @Test
    fun aTimerOfTheSecondTabFindsItsCookEntryAfterProcessDeath() {
        val recipes = mutableStateMapOf<CookKey, Set<Long>>()
        val stacks = ShellTab.entries.associateWith { NavBackStack<NavKey>(it.root) }
        val before = ShellNavigationState(stacks = stacks, cookRecipes = recipes)
        val key = CookKey(1)
        before.navigate(key)
        before.onCookRecipesChanged(key, setOf(1, 2))

        // Process death: the back stacks and the saved recipes come back, nothing is composed yet.
        val saved = requireNotNull(with(CookRecipesSaver) { SaverScope { true }.save(recipes) })
        val restored = ShellNavigationState(stacks = stacks, cookRecipes = requireNotNull(CookRecipesSaver.restore(saved)))
        restored.openTimer(TimerOpenRequest(recipeId = 2, stepIndex = 1))

        assertEquals(listOf<NavKey>(TodayKey, key), restored.backStack)
        assertEquals(CookPageRequest(2, 1), restored.cookPageRequest)
    }

    @Test
    fun savedCookRecipesOfAnotherAppVersionAreDropped() {
        assertEquals(emptyMap<CookKey, Set<Long>>(), CookRecipesSaver.restore("""[{"old":1}]""")?.toMap())
    }

    @Test
    fun aClosedCookEntryIsForgotten() {
        val closed = CookKey(1)
        navigation.navigate(closed)
        navigation.onCookRecipesChanged(closed, setOf(1, 2))
        navigation.goBack()
        val open = CookKey(5)
        navigation.navigate(open)
        navigation.onCookRecipesChanged(open, setOf(5))

        assertEquals(setOf(1L), navigation.cookRecipesOf(closed))
    }

    @Test
    fun restoredTabIsUsed() {
        val restored = ShellNavigationState(
            stacks = ShellTab.entries.associateWith { NavBackStack<NavKey>(it.root) },
            initialTab = ShellTab.WEEK,
        )
        assertEquals(listOf<NavKey>(TodayKey, WeekKey), restored.backStack)
    }

    @Test
    fun pickingAnotherRecipeInTheListReplacesTheOpenDetail() {
        navigation.selectTab(ShellTab.RECIPES)
        val fromList = { id: Long -> navigation.navigate(RecipeDetailKey(id, inListDetail = true), replace = { it is RecipeDetailKey }) }
        fromList(101)
        // A sub-recipe opened from the detail stacks on top of it ...
        navigation.navigate(RecipeDetailKey(105, inListDetail = true))
        // ... but the next pick in the list replaces both.
        fromList(103)
        assertEquals(listOf<NavKey>(TodayKey, RecipesKey, RecipeDetailKey(103, inListDetail = true)), navigation.backStack)
        assertTrue(navigation.goBack())
        assertEquals(RecipesKey, navigation.currentKey)
    }

    @Test
    fun replaceNeverRemovesTheTabsFirstScreen() {
        navigation.navigate(RecipeDetailKey(101), replace = { true })
        assertEquals(listOf<NavKey>(TodayKey, RecipeDetailKey(101)), navigation.backStack)
    }

    @Test
    fun fullScreenDestinationsHideTheBarOnlyOnCompactWindows() {
        assertFalse(navigation.hidesNavigation(compactWidth = true))
        navigation.navigate(RecipeDetailKey(101))
        assertTrue(navigation.hidesNavigation(compactWidth = true))
        assertFalse("the rail stays beside a recipe", navigation.hidesNavigation(compactWidth = false))
        navigation.selectTab(ShellTab.MORE)
        navigation.navigate(AboutKey)
        assertFalse(navigation.hidesNavigation(compactWidth = true))
    }

    @Test
    fun aTappedTimerOpensCookModeAtItsStepOnTheCurrentTab() {
        navigation.selectTab(ShellTab.RECIPES)
        navigation.navigate(RecipeDetailKey(6))
        navigation.openTimer(TimerOpenRequest(recipeId = 6, stepIndex = 1))
        assertEquals(listOf<NavKey>(TodayKey, RecipesKey, RecipeDetailKey(6), CookKey(6, startStep = 1)), navigation.backStack)
        assertTrue("cook mode hides the navigation", navigation.hidesNavigation(compactWidth = false))
    }

    /** Fas 4, komplettering 3: the notification of a finished AI import. */
    @Test
    fun theImportResultIsWhereItWasLeftElseANewPageFindsIt() {
        // The task is gone: a page of its own, which reads the kept answer.
        navigation.open(ShellOpenRequest.ImportResult)
        assertEquals(listOf<NavKey>(TodayKey, RecipesKey, RecipeImportKey()), navigation.backStack)

        // Left with a shared link in another tab: back to it as it was, not a new page.
        navigation.openImport(ShareImportRequest(text = "https://example.com/x"))
        val left = navigation.backStack
        navigation.selectTab(ShellTab.WEEK)
        navigation.open(ShellOpenRequest.ImportResult)
        assertEquals(left, navigation.backStack)

        // On the tab already: a shortcut would start it afresh, this does not.
        navigation.open(ShellOpenRequest.ImportResult)
        assertEquals(left, navigation.backStack)
    }

    @Test
    fun anOpenRequestSelectsTheTabOrOpensTheScreen() {
        // Fas 3 (widgets lane): shortcuts, widgets and the tile.
        navigation.open(ShellOpenRequest.Tab(ShellTab.SHOPPING))
        assertEquals(listOf<NavKey>(TodayKey, ShoppingKey), navigation.backStack)
        navigation.open(ShellOpenRequest.Recipe(6))
        assertEquals(listOf<NavKey>(TodayKey, RecipesKey, RecipeDetailKey(6)), navigation.backStack)
        navigation.open(ShellOpenRequest.Recipe(7))
        assertEquals("one recipe at a time", listOf<NavKey>(TodayKey, RecipesKey, RecipeDetailKey(7)), navigation.backStack)
        // A request on the tab already shown starts that tab afresh (selectTab's rule), like a shortcut should.
        navigation.open(ShellOpenRequest.Import)
        assertEquals(listOf<NavKey>(TodayKey, RecipesKey, RecipeImportKey()), navigation.backStack)
        navigation.open(ShellOpenRequest.BarcodeScan)
        assertEquals(listOf<NavKey>(TodayKey, MoreKey, BarcodeScanKey()), navigation.backStack)
        // Fas 4 (skafferinotis lane): the expiry notification opens "Kan laga nu" with "Använd upp" first.
        navigation.open(ShellOpenRequest.UseUp)
        assertEquals(listOf<NavKey>(TodayKey, MoreKey, MakeNowKey(useUpFirst = true)), navigation.backStack)
        // Fas 5 (planera lane): the evening reminder opens Veckan at the meal's day, one such page at a time.
        navigation.open(ShellOpenRequest.WeekDay("2026-11-14"))
        assertEquals(listOf<NavKey>(TodayKey, WeekKey, WeekAgendaKey(weekStart = "2026-11-14")), navigation.backStack)
        navigation.open(ShellOpenRequest.WeekDay("2026-11-15"))
        assertEquals(listOf<NavKey>(TodayKey, WeekKey, WeekAgendaKey(weekStart = "2026-11-15")), navigation.backStack)
        // Fas 5 (butik lane): the reminder near a store opens store mode sorted for that store, one at a time.
        navigation.open(ShellOpenRequest.StoreMode(4))
        assertEquals(listOf<NavKey>(TodayKey, ShoppingKey, StoreModeKey(supermarketId = 4)), navigation.backStack)
        navigation.open(ShellOpenRequest.StoreMode(5))
        assertEquals(listOf<NavKey>(TodayKey, ShoppingKey, StoreModeKey(supermarketId = 5)), navigation.backStack)
        // Fas 3 (import lane): a share opens the import on the Recipes tab, replacing an open import.
        navigation.openImport(ShareImportRequest(text = "https://example.com/x"))
        assertEquals(
            listOf<NavKey>(TodayKey, RecipesKey, RecipeImportKey(sharedText = "https://example.com/x")),
            navigation.backStack,
        )
    }

    @Test
    fun theShortcutNewItemOpensHandlaWithItsAddPanel() {
        navigation.open(ShellOpenRequest.AddItem)
        assertEquals(listOf<NavKey>(TodayKey, ShoppingKey), navigation.backStack)
        assertTrue(navigation.addItemRequested)
        navigation.onAddItemHandled()
        assertFalse(navigation.addItemRequested)
    }

    @Test
    fun theWidgetsCookRequestGoesThroughLagaAndNeverOpensASecondSession() {
        // cookKeyAlongside's answer when another recipe's timers run.
        val alongside = CookKey(6, alongsideRecipeId = 9)
        navigation.selectTab(ShellTab.WEEK)
        navigation.open(ShellOpenRequest.Cook(6)) { alongside }
        assertEquals(listOf<NavKey>(TodayKey, alongside), navigation.backStack)
        navigation.open(ShellOpenRequest.Cook(9)) { CookKey(it) }
        assertEquals("the recipe is already open as a tab", listOf<NavKey>(TodayKey, alongside), navigation.backStack)
    }

    @Test
    fun aTimerOfTheOpenCookModeKeepsItAsItIs() {
        navigation.navigate(CookKey(6, servings = 3.0, startStep = 0))
        navigation.openTimer(TimerOpenRequest(recipeId = 6, stepIndex = 1))
        assertEquals(
            "no second cook mode, the open one keeps its page and draft",
            listOf<NavKey>(TodayKey, CookKey(6, 3.0, 0)),
            navigation.backStack,
        )
    }

    @Test
    fun aTimerWithoutARecipeOpensTheTimerListOnce() {
        navigation.openTimer(TimerOpenRequest(recipeId = null, stepIndex = null))
        navigation.openTimer(TimerOpenRequest(recipeId = null, stepIndex = null))
        assertEquals(listOf<NavKey>(TodayKey, TimersKey), navigation.backStack)
        // A recipe timer from the list replaces the list instead of stacking on it.
        navigation.openTimer(TimerOpenRequest(recipeId = 2, stepIndex = null))
        assertEquals(listOf<NavKey>(TodayKey, CookKey(2, startStep = 0)), navigation.backStack)
    }

    // --- fix-ui (0.2.0 review): one cook session per recipe, the timer's step, the timer's source ---

    @Test
    fun aTimerOfTheOpenCookModeTurnsItToTheTimersStep() {
        navigation.navigate(CookKey(6, servings = 3.0, startStep = 0))
        navigation.openTimer(TimerOpenRequest(recipeId = 6, stepIndex = 3))

        assertEquals(listOf<NavKey>(TodayKey, CookKey(6, 3.0, 0)), navigation.backStack)
        assertEquals(CookPageRequest(recipeId = 6, step = 3), navigation.cookPageRequest)
        navigation.onCookPageRequestHandled(recipeId = 7)
        assertEquals("another recipe's entry does not clear it", CookPageRequest(6, 3), navigation.cookPageRequest)
        navigation.onCookPageRequestHandled(recipeId = 6)
        assertEquals(null, navigation.cookPageRequest)
    }

    @Test
    fun aTimerOfACookModeLowerInTheStackReturnsToItInsteadOfASecondSession() {
        navigation.navigate(CookKey(1))
        navigation.navigate(CookKey(2, startStep = 1))
        navigation.openTimer(TimerOpenRequest(recipeId = 1, stepIndex = 2))

        assertEquals(listOf<NavKey>(TodayKey, CookKey(1)), navigation.backStack)
        assertEquals(CookPageRequest(1, 2), navigation.cookPageRequest)
    }

    @Test
    fun theTimerListOnTopOfCookModeGoesAwayWhenItsTimerIsOpened() {
        navigation.navigate(CookKey(1))
        navigation.navigate(TimersKey)
        navigation.openTimerOf(timer(recipeId = 1, stepIndex = 0), activeSource = null)

        assertEquals(listOf<NavKey>(TodayKey, CookKey(1)), navigation.backStack)
        assertEquals(CookPageRequest(1, 0), navigation.cookPageRequest)
    }

    @Test
    fun noKeyIsEverTwiceOnTheStack() {
        navigation.navigate(CookKey(1))
        navigation.openTimer(TimerOpenRequest(recipeId = 1, stepIndex = 0))
        navigation.openTimer(TimerOpenRequest(recipeId = 1, stepIndex = null))

        assertEquals(listOf<NavKey>(TodayKey, CookKey(1)), navigation.backStack)
        assertEquals(navigation.backStack.size, navigation.backStack.toSet().size)
    }

    @Test
    fun cookModeInTodaysStackIsReusedFromAnotherTab() {
        navigation.navigate(CookKey(4))
        navigation.selectTab(ShellTab.RECIPES)
        navigation.navigate(RecipeDetailKey(9))
        navigation.openTimer(TimerOpenRequest(recipeId = 4, stepIndex = 1))

        assertEquals(ShellTab.TODAY, navigation.currentTab)
        assertEquals(listOf<NavKey>(TodayKey, CookKey(4)), navigation.backStack)
    }

    @Test
    fun aTimerOfAnotherSourceOpensTheTimerListNotThisSourcesRecipe() {
        // Started on demo recipe 7, opened after logging in to a real account.
        navigation.openTimer(TimerOpenRequest(recipeId = 7, stepIndex = 0, sourceKey = "demo"), activeSource = "account-1")
        assertEquals(listOf<NavKey>(TodayKey, TimersKey), navigation.backStack)

        navigation.openTimerOf(timer(recipeId = 7, stepIndex = 0, sourceKey = "demo"), activeSource = "account-1")
        assertEquals("the list is not stacked twice", listOf<NavKey>(TodayKey, TimersKey), navigation.backStack)

        navigation.openTimer(TimerOpenRequest(recipeId = 7, stepIndex = 0, sourceKey = "account-1"), activeSource = "account-1")
        assertEquals(listOf<NavKey>(TodayKey, CookKey(7, startStep = 0)), navigation.backStack)
    }

    private fun timer(recipeId: Long?, stepIndex: Int?, sourceKey: String? = null): CookTimer = CookTimer.start(
        id = 1,
        label = "Pasta",
        duration = 10.minutes,
        now = Instant.fromEpochSeconds(0),
        recipeId = recipeId,
        stepIndex = stepIndex,
        sourceKey = sourceKey,
    )
}
