package casa.moller.tandem.feature.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.navigation.TimersKey
import kotlinx.serialization.Serializable

/**
 * Navigation 3 keys of the shell. They are @Serializable so that [rememberNavBackStack] can save
 * every tab's back stack across configuration changes and process death.
 *
 * Feature destinations have their own keys in their feature module (e.g. `RecipeDetailKey` in
 * feature:recipes, also @Serializable); the tab stacks hold any [NavKey]. The tab roots
 * ([TodayKey], [RecipesKey], ...) stay here: the shell maps them to the features' routes.
 */
@Serializable
sealed interface ShellKey : NavKey

@Serializable
data object TodayKey : ShellKey

@Serializable
data object RecipesKey : ShellKey

@Serializable
data object WeekKey : ShellKey

@Serializable
data object ShoppingKey : ShellKey

@Serializable
data object MoreKey : ShellKey

/** Mer → Språk. */
@Serializable
data object LanguageKey : ShellKey

/** Mer → Om Tandem. */
@Serializable
data object AboutKey : ShellKey

/** Mer → Om Tandem → Licenser. */
@Serializable
data object LicensesKey : ShellKey

/** The full text of one bundled licence. */
@Serializable
data class LicenseTextKey(val license: BundledLicense) : ShellKey

/** The five top-level destinations (plan 6.1), in the order of the navigation bar. */
enum class ShellTab(val root: ShellKey) {
    TODAY(TodayKey),
    RECIPES(RecipesKey),
    WEEK(WeekKey),
    SHOPPING(ShoppingKey),
    MORE(MoreKey),
}

/**
 * One back stack per tab plus the selected tab. What the shell shows is [backStack]: the Today
 * stack with the current tab's stack on top (each stack decorated on its own, see
 * `ShellNavDisplay`), so system back goes
 * up inside a tab first, then to Today, and only leaves the app from Today (Material navigation
 * guidance; the Navigation 3 "multiple back stacks" recipe).
 *
 * The stacks are plain mutable lists, so the logic is unit-testable without Compose.
 */
@Stable
class ShellNavigationState(
    private val stacks: Map<ShellTab, MutableList<NavKey>>,
    initialTab: ShellTab = ShellTab.TODAY,
    private val onTabChanged: (ShellTab) -> Unit = {},
) {
    init {
        require(ShellTab.entries.all { stacks[it]?.isNotEmpty() == true }) { "every tab needs a non-empty stack" }
    }

    var currentTab: ShellTab by mutableStateOf(initialTab)
        private set

    /** The keys to display, bottom to top. Never empty. */
    val backStack: List<NavKey>
        get() = if (currentTab == ShellTab.TODAY) {
            stack(ShellTab.TODAY)
        } else {
            stack(ShellTab.TODAY) + stack(currentTab)
        }

    /** The key on top of the current tab, i.e. the visible screen. */
    val currentKey: NavKey
        get() = stack(currentTab).last()

    /**
     * Selects [tab]. Selecting the tab that is already shown returns to its first screen
     * (Material behaviour for re-selecting a destination).
     */
    fun selectTab(tab: ShellTab) {
        if (tab == currentTab) {
            val stack = stack(tab)
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            currentTab = tab
            onTabChanged(tab)
        }
    }

    /**
     * Opens [key] on top of the current tab. Keys on top of the stack for which [replace] is true
     * are removed first (never the tab's first screen): picking another recipe in the list of a
     * list-detail layout replaces the open detail instead of stacking one detail per tap.
     */
    fun navigate(key: NavKey, replace: (NavKey) -> Boolean = { false }) {
        val stack = stack(currentTab)
        while (stack.size > 1 && replace(stack.last())) stack.removeAt(stack.lastIndex)
        if (stack.last() != key) stack.add(key)
    }

    /**
     * Opens what a tapped timer notification or the lock-screen alarm points at (Phase 1b
     * integration): cook mode for the timer's recipe at its step, or the timer list for a timer
     * without a recipe, on top of the current tab. Cook mode for the same recipe that is already
     * on screen stays as it is (it shows its timers, and its page, servings and "Klart!" draft
     * live in its entry); an open timer list is replaced instead of stacked.
     */
    fun openTimer(request: TimerOpenRequest) {
        val recipeId = request.recipeId
        val current = currentKey
        when {
            recipeId == null -> navigate(TimersKey)
            current is CookKey && current.recipeId == recipeId -> Unit
            else -> navigate(CookKey(recipeId, startStep = request.stepIndex ?: 0), replace = { it is TimersKey })
        }
    }

    /**
     * True when the visible screen is a [FullScreenDestination] that hides the shell's navigation:
     * on a compact window (bottom bar) always, on wider windows (rail) only when the destination
     * asks for it ([FullScreenDestination.hidesNavigationOnAllWidths], e.g. cook mode).
     */
    fun hidesNavigation(compactWidth: Boolean): Boolean {
        val destination = currentKey as? FullScreenDestination ?: return false
        return compactWidth || destination.hidesNavigationOnAllWidths
    }

    /**
     * System back / the top bar's back arrow. Returns false when there is nothing to go back to
     * inside the shell (on Today's first screen); the system then leaves the app.
     */
    fun goBack(): Boolean {
        val stack = stack(currentTab)
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }

            currentTab != ShellTab.TODAY -> {
                currentTab = ShellTab.TODAY
                onTabChanged(ShellTab.TODAY)
                true
            }

            else -> false
        }
    }

    /** The back stack of [tab], bottom to top, whether the tab is shown or not. Never empty. */
    fun stackOf(tab: ShellTab): List<NavKey> = stack(tab)

    private fun stack(tab: ShellTab): MutableList<NavKey> = stacks.getValue(tab)
}

/** Remembers the shell's back stacks and selected tab, saved across process death. */
@Composable
fun rememberShellNavigationState(): ShellNavigationState {
    val today: NavBackStack<NavKey> = rememberNavBackStack(TodayKey)
    val recipes: NavBackStack<NavKey> = rememberNavBackStack(RecipesKey)
    val week: NavBackStack<NavKey> = rememberNavBackStack(WeekKey)
    val shopping: NavBackStack<NavKey> = rememberNavBackStack(ShoppingKey)
    val more: NavBackStack<NavKey> = rememberNavBackStack(MoreKey)
    var savedTab by rememberSaveable { mutableStateOf(ShellTab.TODAY) }
    return remember(today, recipes, week, shopping, more) {
        ShellNavigationState(
            stacks = mapOf(
                ShellTab.TODAY to today,
                ShellTab.RECIPES to recipes,
                ShellTab.WEEK to week,
                ShellTab.SHOPPING to shopping,
                ShellTab.MORE to more,
            ),
            initialTab = savedTab,
            onTabChanged = { savedTab = it },
        )
    }
}
