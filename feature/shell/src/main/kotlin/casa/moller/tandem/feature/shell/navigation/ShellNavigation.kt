package casa.moller.tandem.feature.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.navigation.TimersKey
import casa.moller.tandem.feature.mealplan.navigation.WeekAgendaKey
import casa.moller.tandem.feature.pantry.navigation.MakeNowKey
import casa.moller.tandem.feature.recipes.navigation.RecipeDetailKey
import casa.moller.tandem.feature.recipes.navigation.RecipeEditorKey
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    private val cookRecipes: MutableMap<CookKey, Set<Long>> = mutableStateMapOf(),
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
     * A page an already open cook mode should turn to ([openCook]): read by that cook entry
     * (`CookRoute`'s page request) and cleared with [onCookPageRequestHandled]. Not saved: it is
     * handled as soon as the entry is shown.
     */
    var cookPageRequest: CookPageRequest? by mutableStateOf(null)
        private set

    /** The shortcut "Ny vara" ([ShellOpenRequest.AddItem]): the Handla tab opens its add panel, then [onAddItemHandled]. */
    var addItemRequested: Boolean by mutableStateOf(false)
        private set

    /**
     * Opens what a tapped timer notification or the lock-screen alarm points at (Phase 1b
     * integration): cook mode for the timer's recipe at its step ([openCook]), or the timer list
     * for a timer without a recipe or of another source than [activeSource] (fix-ui, 0.2.0
     * review: a demo timer must not open the real account's recipe with the same id, see
     * core:model `TimerSource`). An open timer list is replaced instead of stacked.
     */
    fun openTimer(request: TimerOpenRequest, activeSource: String? = null) {
        val recipeId = request.recipeId
        val sameSource = request.sourceKey == null || request.sourceKey == activeSource
        if (recipeId == null || !sameSource) {
            navigate(TimersKey, replace = { it is TimersKey })
        } else {
            openCook(recipeId, request.stepIndex)
        }
    }

    /**
     * Fas 3 (import lane): something another app shared to Tandem opens "Importera recept" on
     * the Recipes tab (an import already open there is replaced, so two shares never stack).
     */
    fun openImport(request: ShareImportRequest) {
        selectTab(ShellTab.RECIPES)
        navigate(
            RecipeImportKey(
                sharedText = request.text,
                sharedFileUri = request.fileUri,
                sharedMimeType = request.mimeType,
                sharedPageUris = request.pageUris,
            ),
            replace = { it is RecipeImportKey || (it is RecipeEditorKey && it.recipeId == null) },
        )
    }

    /**
     * Fas 3 (widgets lane): a launcher shortcut, a widget or the quick settings tile opened the
     * app at [request] ([ShellOpenRequest]): a tab, a recipe, the import or the barcode scanner.
     *
     * @param cookKey what "Laga" opens for a recipe ([ShellOpenRequest.Cook]); the shell passes
     *   feature:cook's `cookKeyAlongside`, so a recipe with running timers comes along.
     */
    fun open(request: ShellOpenRequest, cookKey: (Long) -> CookKey = { CookKey(it) }) {
        when (request) {
            is ShellOpenRequest.Tab -> selectTab(request.tab)

            is ShellOpenRequest.Cook -> startCook(request.recipeId, cookKey)

            ShellOpenRequest.AddItem -> {
                selectTab(ShellTab.SHOPPING)
                addItemRequested = true
            }

            is ShellOpenRequest.Recipe -> {
                selectTab(ShellTab.RECIPES)
                navigate(RecipeDetailKey(request.recipeId), replace = { it is RecipeDetailKey })
            }

            ShellOpenRequest.Import -> {
                selectTab(ShellTab.RECIPES)
                navigate(RecipeImportKey(), replace = { it is RecipeImportKey })
            }

            // Unlike a shortcut this never starts the tab afresh: the review stays as it was left, and
            // with no import in the stack (the task is gone too) the new page finds the kept answer.
            ShellOpenRequest.ImportResult -> {
                if (currentTab != ShellTab.RECIPES) selectTab(ShellTab.RECIPES)
                if (stack(ShellTab.RECIPES).none { it is RecipeImportKey }) navigate(RecipeImportKey())
            }

            ShellOpenRequest.BarcodeScan -> {
                selectTab(ShellTab.MORE)
                navigate(BarcodeScanKey(), replace = { it is BarcodeScanKey })
            }

            // Fas 4 (skafferinotis lane): the expiry notification; Hemma lives under Mer.
            ShellOpenRequest.UseUp -> {
                selectTab(ShellTab.MORE)
                navigate(MakeNowKey(useUpFirst = true), replace = { it is MakeNowKey })
            }

            // Fas 5 (planera lane): the evening reminder; the week of the day with that day chosen.
            is ShellOpenRequest.WeekDay -> {
                selectTab(ShellTab.WEEK)
                navigate(WeekAgendaKey(weekStart = request.date), replace = { it is WeekAgendaKey })
            }

            // Fas 5 (butik lane): the reminder near a store; store mode over Handla, sorted for that store.
            is ShellOpenRequest.StoreMode -> {
                selectTab(ShellTab.SHOPPING)
                navigate(StoreModeKey(supermarketId = request.storeId), replace = { it is StoreModeKey })
            }
        }
    }

    /** Cook mode for [recipeId]: an open one is shown as it is (no second session), else [cookKey]'s on the Today tab. */
    private fun startCook(recipeId: Long, cookKey: (Long) -> CookKey) {
        val isRecipe: (NavKey) -> Boolean = { it is CookKey && recipeId in cookRecipesOf(it) }
        if (stack(currentTab).any(isRecipe) || stack(ShellTab.TODAY).any(isRecipe)) {
            openCook(recipeId, step = null)
        } else {
            selectTab(ShellTab.TODAY)
            navigate(cookKey(recipeId))
        }
    }

    /** The add panel was opened for [addItemRequested]. */
    fun onAddItemHandled() {
        addItemRequested = false
    }

    /**
     * A timer tapped in the app (the timer bar on every tab, the timer list): like [openTimer]
     * for a notification, with the timer's own recipe, step and source.
     */
    fun openTimerOf(timer: CookTimer, activeSource: String?) {
        val request = TimerOpenRequest(
            recipeId = timer.recipeId?.takeIf { it > 0 },
            stepIndex = timer.stepIndex?.takeIf { it >= 0 },
            sourceKey = timer.sourceKey,
        )
        openTimer(request, activeSource)
    }

    /**
     * Cook mode for [recipeId], at [step] when given (a timer's step), without ever holding two
     * cook sessions for one recipe (fix-ui, 0.2.0 review): when the shown stack (the current
     * tab's, or Today's below it) already has cook mode for the recipe, everything above it is
     * removed and the open session turns to [step] ([cookPageRequest]), keeping its servings,
     * page state and "Klart!" draft. Otherwise a new cook mode opens at [step] (an open timer
     * list on top is replaced).
     */
    fun openCook(recipeId: Long, step: Int?) {
        // Fas 4 (two recipes at once): a cook mode whose second tab is the recipe counts too.
        val isRecipe: (NavKey) -> Boolean = { it is CookKey && recipeId in cookRecipesOf(it) }
        val tab = when {
            stack(currentTab).any(isRecipe) -> currentTab
            stack(ShellTab.TODAY).any(isRecipe) -> ShellTab.TODAY
            else -> null
        }
        if (tab == null) {
            navigate(CookKey(recipeId, startStep = step ?: 0), replace = { it is TimersKey })
            return
        }
        if (tab != currentTab) {
            currentTab = tab
            onTabChanged(tab)
        }
        val stack = stack(tab)
        while (!isRecipe(stack.last())) stack.removeAt(stack.lastIndex)
        // A second entry for the same recipe further down (stacked before this rule) is dropped too.
        for (index in stack.lastIndex - 1 downTo 1) {
            if (isRecipe(stack[index])) stack.removeAt(index)
        }
        if (step != null) cookPageRequest = CookPageRequest(recipeId, step)
    }

    /**
     * The recipes open in the cook entry of [key]: what it reported ([onCookRecipesChanged]), else
     * what it opened with. Fas 4 (two recipes at once, plan 8.1); what was reported is saved with
     * the back stacks ([CookRecipesSaver]), so a timer notification tapped right after process
     * death finds the cook entry whose second tab is the timer's recipe, before that entry is composed.
     */
    fun cookRecipesOf(key: CookKey): Set<Long> = cookRecipes[key] ?: key.recipeIds.toSet()

    /** The cook entry of [key] has [recipeIds] open now ("Laga ett recept till", a closed tab). Closed entries are forgotten. */
    fun onCookRecipesChanged(key: CookKey, recipeIds: Set<Long>) {
        cookRecipes.keys.retainAll { open -> stacks.values.any { open in it } }
        cookRecipes[key] = recipeIds
    }

    /** The cook entry of [recipeId] turned to the requested page. */
    fun onCookPageRequestHandled(recipeId: Long) {
        if (cookPageRequest?.recipeId == recipeId) cookPageRequest = null
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

/**
 * A page ([CookPageRequest.step], 0-based) the open cook mode of [CookPageRequest.recipeId] should
 * show ([ShellNavigationState.openCook]); feature:cook's type since Fas 4, so its route can select
 * the recipe's tab.
 */
typealias CookPageRequest = casa.moller.tandem.feature.cook.navigation.CookPageRequest

/** One entry of [CookRecipesSaver]. */
@Serializable
private data class SavedCookRecipes(val key: CookKey, val recipeIds: List<Long>)

private val savedCookRecipes = ListSerializer(SavedCookRecipes.serializer())

/** [ShellNavigationState]'s recipes per cook entry across process death, as JSON like the back stacks' keys. */
internal val CookRecipesSaver: Saver<SnapshotStateMap<CookKey, Set<Long>>, String> = Saver(
    save = { recipes -> Json.encodeToString(savedCookRecipes, recipes.map { (key, ids) -> SavedCookRecipes(key, ids.toList()) }) },
    restore = { saved ->
        val entries = try {
            Json.decodeFromString(savedCookRecipes, saved)
        } catch (e: SerializationException) {
            // Saved by another version of the app: the cook entries report again when they are shown.
            emptyList()
        }
        mutableStateMapOf<CookKey, Set<Long>>().apply { entries.forEach { put(it.key, it.recipeIds.toSet()) } }
    },
)

/** Remembers the shell's back stacks and selected tab, saved across process death. */
@Composable
fun rememberShellNavigationState(): ShellNavigationState {
    val today: NavBackStack<NavKey> = rememberNavBackStack(TodayKey)
    val recipes: NavBackStack<NavKey> = rememberNavBackStack(RecipesKey)
    val week: NavBackStack<NavKey> = rememberNavBackStack(WeekKey)
    val shopping: NavBackStack<NavKey> = rememberNavBackStack(ShoppingKey)
    val more: NavBackStack<NavKey> = rememberNavBackStack(MoreKey)
    var savedTab by rememberSaveable { mutableStateOf(ShellTab.TODAY) }
    val cookRecipes = rememberSaveable(saver = CookRecipesSaver) { mutableStateMapOf() }
    return remember(today, recipes, week, shopping, more, cookRecipes) {
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
            cookRecipes = cookRecipes,
        )
    }
}
