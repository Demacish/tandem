package casa.moller.tandem.feature.shell

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.core.ui.timer.LocalTimerClock
import casa.moller.tandem.core.ui.timer.LocalTimerTicks
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import casa.moller.tandem.feature.recipes.books.RecipeCollection
import casa.moller.tandem.feature.shell.navigation.ShellFeatures
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.ui.PlaceholderTab
import casa.moller.tandem.feature.shell.ui.ShellScreenScaffold
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal val ACCOUNT = ServerAccount(
    id = "account-1",
    baseUrl = "https://tandoor.example.com",
    username = "anna",
    displayName = "Anna Andersson",
    userId = 7,
    spaceId = 1,
    spaceName = "Hemma",
    tokenId = 42,
    serverVersion = "2.6.13",
    addedAt = Instant.parse("2026-10-09T12:00:00Z"),
    tokenOrigin = TokenOrigin.CREATED,
)

internal val LOGGED_IN = Session.LoggedIn(ACCOUNT, "2.6.13", ServerCompatibility.SUPPORTED)

internal val CONNECTED_STATE = ShellUiState(
    mode = LOGGED_IN.toShellMode(),
    appVersion = AppVersion("0.0.1-dev", 1, "sideload"),
)

internal val DEMO_STATE = CONNECTED_STATE.copy(mode = ShellMode.Demo)

/** The account card with what Tandem found on the server (fix-ui, 0.2.0 review). */
internal val FEATURES_STATE = CONNECTED_STATE.copy(
    serverFeatures = setOf(ServerFeature.RECIPE_INDEX, ServerFeature.VIEW_LOG, ServerFeature.COOK_LOG, ServerFeature.HOUSEHOLDS),
)

/** Logged in with a token the user pasted (it is never revoked by the app). */
internal val PASTED_STATE = CONNECTED_STATE.copy(
    mode = LOGGED_IN.copy(account = ACCOUNT.copy(tokenOrigin = TokenOrigin.PASTED)).toShellMode(),
)

internal class FakeSessionRepository(initial: Session = LOGGED_IN) : SessionRepository {
    val state = MutableStateFlow(initial)
    val logouts = mutableListOf<Boolean>()
    var refreshes = 0
    var refreshResult: Outcome<ServerInfo> =
        Outcome.Success(ServerInfo("https://tandoor.example.com/", "2.6.14", ServerCompatibility.SUPPORTED))
    var logoutResult: Outcome<TokenRevocation> = Outcome.Success(TokenRevocation.REVOKED)

    /** When set, refreshServerInfo waits for it (to observe the "refreshing" state). */
    var refreshGate: CompletableDeferred<Unit>? = null

    /** When set, logout waits for it (the token revocation is still running). */
    var logoutGate: CompletableDeferred<Unit>? = null

    override val session: Flow<Session> = state

    override suspend fun refreshServerInfo(): Outcome<ServerInfo> {
        refreshes++
        refreshGate?.await()
        val result = refreshResult
        val current = state.value
        if (result is Outcome.Success && current is Session.LoggedIn) {
            state.value = current.copy(serverVersion = result.value.version)
        }
        return result
    }

    override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> {
        logouts += revokeToken
        logoutGate?.await()
        val result = logoutResult
        if (result is Outcome.Success) state.value = Session.NoAccount
        return result
    }

    override suspend fun startDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun leaveDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override fun acknowledgeRecovery() = Unit

    override suspend fun clearAppData(): Outcome<Unit> = Outcome.Success(Unit)
}

internal class FakeAppearanceRepository : AppearanceRepository {
    val mode = MutableStateFlow(ThemeMode.SYSTEM)
    var result: Outcome<Unit> = Outcome.Success(Unit)

    override val themeMode: Flow<ThemeMode> = mode

    override suspend fun setThemeMode(mode: ThemeMode): Outcome<Unit> {
        if (result is Outcome.Success) this.mode.value = mode
        return result
    }
}

internal class RecordingActions : ShellActions {
    val logouts = mutableListOf<Boolean>()
    var refreshes = 0

    override fun refreshServerInfo() {
        refreshes++
    }

    override fun onAppResumed() = Unit

    override fun logout(revokeToken: Boolean) {
        logouts += revokeToken
    }

    var demoResets = 0

    override fun resetDemoData() {
        demoResets++
    }

    var serverUpdateDismissals = 0

    override fun dismissServerUpdate() {
        serverUpdateDismissals++
    }

    // Phase 1b (inställningar lane)
    var syncRetries = 0
    val stoppedTimers = mutableListOf<Long>()

    override fun retrySync() {
        syncRetries++
    }

    override fun stopTimer(id: Long) {
        stoppedTimers += id
    }
}

/**
 * Shell tests never render the real feature routes (they create ViewModels with Hilt): Today and
 * Recipes show the Phase 0 placeholders, the recipe detail a minimal page with a back arrow.
 */
internal class PlaceholderShellFeatures(private val state: ShellUiState) : ShellFeatures {
    @Composable
    override fun Today(
        onOpenRecipe: (Long) -> Unit,
        onOpenRecipes: () -> Unit,
        onOpenWeek: () -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
        onOpenWantToCook: () -> Unit,
    ) {
        Placeholder(ShellTab.TODAY)
    }

    @Composable
    override fun Recipes(onOpenRecipe: (Long) -> Unit, selectedRecipeId: Long?, onOpenCollection: (RecipeCollection) -> Unit) {
        Placeholder(ShellTab.RECIPES)
    }

    @Composable
    override fun RecipeDetail(
        recipeId: Long,
        onBack: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
        onOpenOnWeb: (String) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    ) {
        ShellScreenScaffold(title = "Recipe $recipeId", state = state, onNavigateBack = onBack) { padding ->
            Text(text = "Recipe detail $recipeId", modifier = Modifier.padding(padding))
        }
    }

    @Composable
    override fun EmptyRecipePane() {
        Text(text = "Pick a recipe")
    }

    @Composable
    override fun Cook(key: CookKey, onClose: () -> Unit, pageRequest: Int?, onPageRequestHandled: () -> Unit) {
        ShellScreenScaffold(title = "Cook ${key.recipeId}", state = state, onNavigateBack = onClose) { padding ->
            // The step a tapped timer asked for stays visible, so tests can see it arrived.
            val page = pageRequest?.let { " at step ${it + 1}" }.orEmpty()
            Text(text = "Cook mode ${key.recipeId}$page", modifier = Modifier.padding(padding))
        }
    }

    @Composable
    override fun Timers(onBack: () -> Unit, onOpenCook: (CookTimer) -> Unit, activeSource: String?) {
        ShellScreenScaffold(title = "Timers", state = state, onNavigateBack = onBack) { padding ->
            Text(text = "Timers", modifier = Modifier.padding(padding))
        }
    }

    // --- Phase 2 (grund lane): Handla and Veckan keep their placeholder tabs (and screenshots). ---

    @Composable
    override fun Shopping(
        listId: Long?,
        supermarketId: Long?,
        onOpenStoreMode: (StoreModeKey) -> Unit,
        onOpenListChoice: () -> Unit,
        onOpenHistory: () -> Unit,
        onOpenRecipe: (Long) -> Unit,
    ) {
        Placeholder(ShellTab.SHOPPING)
    }

    @Composable
    override fun StoreMode(key: StoreModeKey, onClose: () -> Unit) = Page("Store mode", onClose)

    @Composable
    override fun ShoppingListChoice(onChosen: (listId: Long?, supermarketId: Long?) -> Unit, onDismiss: () -> Unit) =
        Page("Lists and stores", onDismiss)

    @Composable
    override fun ShoppingHistory(onBack: () -> Unit) = Page("Bought before", onBack)

    @Composable
    override fun Week(
        weekStart: String?,
        onOpenRecipe: (Long) -> Unit,
        onPlanMeal: (PlanMealKey) -> Unit,
        onWeekShopping: (WeekShoppingKey) -> Unit,
        onStartCooking: (Long, Double?) -> Unit,
    ) {
        Placeholder(ShellTab.WEEK)
    }

    @Composable
    override fun PlanMeal(key: PlanMealKey, onDone: () -> Unit) = Page("Plan a meal ${key.date}", onDone)

    @Composable
    override fun WeekShopping(key: WeekShoppingKey, onDone: () -> Unit, onOpenShoppingList: () -> Unit) =
        Page("Shop for the week ${key.weekStart}", onDone)

    @Composable
    override fun Books(
        onOpenBook: (Long) -> Unit,
        onOpenFavorites: () -> Unit,
        onOpenWantToCook: () -> Unit,
        onBack: () -> Unit,
        selectedBookId: Long?,
    ) = Page("Books", onBack)

    @Composable
    override fun BookDetail(bookId: Long, onOpenRecipe: (Long) -> Unit, onBack: (() -> Unit)?) = Page("Book $bookId", onBack ?: {})

    @Composable
    override fun Favorites(onOpenRecipe: (Long) -> Unit, onBack: (() -> Unit)?) = Page("Favourites", onBack ?: {})

    @Composable
    override fun WantToCook(onOpenRecipe: (Long) -> Unit, onPlanMeal: (Long) -> Unit, onBack: (() -> Unit)?) =
        Page("Want to cook", onBack ?: {})

    @Composable
    private fun Page(title: String, onBack: () -> Unit) {
        ShellScreenScaffold(title = title, state = state, onNavigateBack = onBack) { padding ->
            Text(text = title, modifier = Modifier.padding(padding))
        }
    }

    @Composable
    private fun Placeholder(tab: ShellTab) {
        val chrome = LocalAppChrome.current
        PlaceholderTab(
            tab = tab,
            state = state,
            showDemoBanner = chrome.showDemoBanner,
            onDismissDemoBanner = chrome.onDismissDemoBanner,
            onConnectTandoor = chrome.onConnectTandoor,
        )
    }
}

// --- Phase 1b (inställningar lane): timers for the timer bar, on a clock that does not tick. ---

/** The timer bar's "now" in tests (core:testing's sample evening, 2000-01-15 18:00 UTC). */
internal val HUD_NOW: Instant = TestClock().now()

/** Started 2 min 21 s ago for 10 min: 7:39 left; cook mode's step 3 of recipe 101. */
internal val PASTA_TIMER: CookTimer =
    CookTimer.start(1, "Pasta", 10.minutes, HUD_NOW - 141.seconds, recipeId = 101, stepIndex = 2)

/** Paused with 4:00 left; no recipe (a timer from the timer list). */
internal val SAUCE_TIMER: CookTimer =
    CookTimer.start(2, "Sås", 5.minutes, HUD_NOW - 1.minutes).pause(HUD_NOW)

/** Rang a moment ago. */
internal val OVEN_TIMER: CookTimer =
    CookTimer.start(3, "Ugnen", 20.minutes, HUD_NOW - 20.minutes, recipeId = 103, stepIndex = 0).ring(HUD_NOW)

/** A real cook-mode label (step name · the text's words), running with 7:39 left. */
internal val LONG_TIMER: CookTimer = CookTimer.start(
    4,
    "Krämig svamppasta med parmesan · ca 20–25 minuter (25 min)",
    25.minutes,
    HUD_NOW - (17.minutes + 21.seconds),
    recipeId = 101,
    stepIndex = 1,
)

/** The same kind of label, ringing. */
internal val LONG_RINGING_TIMER: CookTimer =
    CookTimer.start(5, "Pastan · 8–10 minuter (10 min)", 10.minutes, HUD_NOW - 10.minutes, recipeId = 101, stepIndex = 2).ring(HUD_NOW)

/** [content] with the timer bar's clock fixed at [HUD_NOW] and no ticking (tests stay idle). */
@Composable
internal fun FrozenTimers(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTimerClock provides TestClock(HUD_NOW),
        LocalTimerTicks provides false,
        content = content,
    )
}
