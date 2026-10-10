package casa.moller.tandem.feature.shell.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
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
}
