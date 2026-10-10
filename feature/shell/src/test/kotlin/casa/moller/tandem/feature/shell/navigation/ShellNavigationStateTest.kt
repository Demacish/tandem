package casa.moller.tandem.feature.shell.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.feature.recipes.navigation.RecipeDetailKey
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
}
