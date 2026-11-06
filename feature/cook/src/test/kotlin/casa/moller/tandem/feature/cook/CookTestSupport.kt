package casa.moller.tandem.feature.cook

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.testing.data.FakeCookLogRepository
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.ui.timer.LocalTimerClock
import casa.moller.tandem.core.ui.timer.LocalTimerTicks
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.session.CookRecipePickerContent
import casa.moller.tandem.feature.cook.session.CookSessionAction
import casa.moller.tandem.feature.cook.session.CookSessionUi
import casa.moller.tandem.feature.cook.session.CookSessionViewModel
import casa.moller.tandem.feature.cook.ui.CookLayout
import casa.moller.tandem.feature.cook.ui.CookScreen
import casa.moller.tandem.feature.cook.ui.FoldBounds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking

/** Compact phone (Pixel 9 class, portrait). */
internal const val PHONE = "w412dp-h915dp-port-xhdpi"

/** A tall phone: a whole step page with its ingredients. */
internal const val PHONE_TALL = "w412dp-h1400dp-port-xhdpi"

/** Phone in landscape: two panes. */
internal const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"

/** Expanded tablet in landscape (Galaxy Tab S10+ class). */
internal const val TABLET = "w1280dp-h800dp-land-hdpi"

/** Tablet in portrait: still two panes (plan 10 "Klart när"). */
internal const val TABLET_PORTRAIT = "w800dp-h1280dp-port-hdpi"

/** A foldable half open on the table (layout forced, Robolectric has no hinge). */
internal const val FOLDABLE = "w412dp-h915dp-port-xhdpi"

/** Its fold in window pixels (xhdpi: 1830 px tall), as the window would report it. */
internal val FOLDABLE_FOLD = FoldBounds(top = 905f, bottom = 925f)

/** Split screen: a phone window about 420 dp tall. */
internal const val PHONE_SHORT = "w412dp-h420dp-port-xhdpi"

/** Fakes behind a real [CookViewModel], so tests and screenshots show what the app computes. */
class CookFakes(
    val clock: TestClock = TestClock(),
    val recipes: FakeRecipeRepository = FakeRecipeRepository(),
    val preferences: FakePreferencesRepository = FakePreferencesRepository(),
    val settings: FakeSettingsRepository = FakeSettingsRepository(),
    val timers: FakeTimerController = FakeTimerController(clock),
    val cookLog: FakeCookLogRepository = FakeCookLogRepository(),
    val sync: FakeSyncRepository = FakeSyncRepository(),
    val handle: SavedStateHandle = SavedStateHandle(),
    // Phase 2 (books lane, #42): a guest's "Klart!" saves nothing.
    val permissions: FakeSpacePermissionsRepository = FakeSpacePermissionsRepository(),
) {
    internal fun viewModel(key: CookKey, savedState: SavedStateHandle = handle): CookViewModel =
        CookViewModel(key, savedState, recipes, preferences, settings, timers, cookLog, sync, clock, permissions)

    /** Fas 4: the cook session of [key] over these fakes (its own SavedStateHandle). */
    internal fun sessionViewModel(key: CookKey, savedState: SavedStateHandle = SavedStateHandle()): CookSessionViewModel =
        CookSessionViewModel(key, savedState, recipes)

    /** Fas 4: starts a timer of [recipeId] (with its name, as cook mode does). */
    fun startTimerOf(recipeId: Long, recipeName: String, stepIndex: Int, label: String, duration: Duration) {
        runBlocking { timers.start(label, duration, recipeId, stepIndex, recipeName = recipeName) }
    }

    /** Starts a timer from step [stepIndex]'s first suggestion of the sample pasta, like a tap on its button. */
    fun startTimer(stepIndex: Int, label: String, duration: Duration) {
        runBlocking { timers.start(label, duration, SampleRecipes.MushroomPasta.id, stepIndex) }
    }

    /** The sample pasta with [count] more ingredients in its first step: a side pane that has to scroll. */
    fun withManyIngredients(count: Int = 10): CookFakes = apply {
        val pasta = SampleRecipes.MushroomPasta
        val extra = (1..count).map { n ->
            Ingredient(id = 90_000L + n, food = Food(id = 900L + n, name = "krydda $n"), amount = n.toDouble(), order = 10 + n)
        }
        val busy = pasta.copy(
            steps = pasta.steps.mapIndexed { index, step -> if (index == 0) step.copy(ingredients = step.ingredients + extra) else step },
        )
        recipes.details.value = recipes.details.value + (pasta.id to busy)
    }

    /** The pasta's second step with a photo (Tandoor `Step.file`, drawn under the text). */
    fun withStepImage(): CookFakes = apply {
        val pasta = SampleRecipes.MushroomPasta
        val withPhoto = pasta.copy(
            steps = pasta.steps.mapIndexed { index, step ->
                if (index == 1) step.copy(imageUrl = "https://example.com/media/pasta.jpg") else step
            },
        )
        recipes.details.value = recipes.details.value + (pasta.id to withPhoto)
    }

    /** No stored detail, and fetching it fails with [error] (deleted, offline, refused login...). */
    fun withoutDetail(error: TandemError): CookFakes = apply {
        val id = SampleRecipes.MushroomPasta.id
        recipes.details.value = recipes.details.value - id
        recipes.detailOverrides.value = mapOf(id to RecipeDetailState(recipeId = id, error = error))
    }

    /** One timer ringing (the mushrooms, step 1) and one running (the pasta, step 2). */
    fun withRingingTimer(): CookFakes = apply {
        startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        startTimer(1, "Pastan · 8–10 minuter (10 min)", 10.minutes)
        timers.advanceTimeBy(5.minutes)
    }
}

/** Theme, a frozen timer clock and an optional font scale around a screen under test. */
@Composable
internal fun TestFrame(
    darkTheme: Boolean = false,
    fontScale: Float? = null,
    clock: TestClock = TestClock(),
    content: @Composable () -> Unit,
) {
    TandemTheme(darkTheme = darkTheme) {
        val density = LocalDensity.current
        val scaled = if (fontScale == null) density else Density(density.density, fontScale)
        CompositionLocalProvider(
            LocalDensity provides scaled,
            LocalTimerClock provides clock,
            LocalTimerTicks provides false,
            content = content,
        )
    }
}

/** Cook mode driven by a real ViewModel over [fakes]. */
@Composable
internal fun CookWithFakes(
    key: CookKey = CookKey(SampleRecipes.MushroomPasta.id),
    fakes: CookFakes = remember { CookFakes() },
    layout: CookLayout? = null,
    modifier: Modifier = Modifier,
    fold: FoldBounds? = null,
    onClose: () -> Unit = {},
    prepare: (CookViewModel) -> Unit = {},
    // Fas 4: one recipe, and "Laga ett recept till" offered like in the app.
    session: CookSessionUi = CookSessionUi(canAdd = true),
    onSessionAction: (CookSessionAction) -> Unit = {},
) {
    val viewModel = remember(key) { fakes.viewModel(key).also(prepare) }
    val state by viewModel.uiState.collectAsState()
    val currentOnClose by rememberUpdatedState(onClose)
    // Like CookRoute: leave after "Klart!" was saved.
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect -> if (effect is CookEffect.Finished) currentOnClose() }
    }
    CookScreen(
        state = state,
        onAction = viewModel::onAction,
        onClose = onClose,
        layout = layout,
        fold = fold,
        modifier = modifier,
        session = session,
        onSessionAction = onSessionAction,
    )
}

/**
 * Fas 4 (two recipes at once): cook mode for the session of [sessionKey] (by default the pasta and the
 * buns, the buns shown unless [showFirst]), driven like CookRoute by a real [CookSessionViewModel]
 * and one real [CookViewModel] per open recipe over [fakes]. ✕ and a saved "Klart!" close the shown
 * recipe; the last one calls [onClose]. The picker ("Laga ett recept till") is drawn over it when open.
 */
@Composable
internal fun CookSessionWithFakes(
    fakes: CookFakes,
    sessionKey: CookKey = CookKey(SampleRecipes.CinnamonBuns.id, alongsideRecipeId = SampleRecipes.MushroomPasta.id),
    showFirst: Boolean = false,
    layout: CookLayout? = null,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    sessionViewModel: CookSessionViewModel = remember {
        fakes.sessionViewModel(sessionKey).also { session ->
            sessionKey.alongsideRecipeId?.takeIf { showFirst }?.let { session.onAction(CookSessionAction.SelectRecipe(it)) }
        }
    },
) {
    val viewModels = remember { mutableMapOf<String, CookViewModel>() }
    val session by sessionViewModel.session.collectAsState()
    val picker by sessionViewModel.picker.collectAsState()
    val models = session.recipes.associate { recipe ->
        recipe.recipeId to viewModels.getOrPut(recipe.viewModelKey) { fakes.viewModel(recipe.toCookKey(), SavedStateHandle()) }
    }
    val states = models.mapValues { (_, model) -> model.uiState.collectAsState().value }
    val active = session.activeRecipeId
    val viewModel = models.getValue(active)
    val currentOnClose by rememberUpdatedState(onClose)
    val closeRecipe: (Long) -> Unit = { id ->
        if (session.isTwo) sessionViewModel.onAction(CookSessionAction.CloseRecipe(id)) else currentOnClose()
    }
    val currentCloseRecipe by rememberUpdatedState(closeRecipe)
    val ids = session.recipeIds.toSet()
    LaunchedEffect(ids) { models.values.forEach { it.onAction(CookAction.SetOtherRecipes(ids)) } }
    models.forEach { (id, model) ->
        key(id) {
            LaunchedEffect(model) { model.effects.collect { effect -> if (effect is CookEffect.Finished) currentCloseRecipe(id) } }
        }
    }
    val closedRecipeId by sessionViewModel.closedRecipeId.collectAsState()
    CookScreen(
        state = states.getValue(active),
        onAction = viewModel::onAction,
        onClose = { closeRecipe(active) },
        onCloseTab = if (session.isTwo) ({ sessionViewModel.onAction(CookSessionAction.CloseRecipe(active, undoable = true)) }) else null,
        layout = layout,
        modifier = modifier,
        session = CookSessionUi.of(session, states, closedRecipeId),
        onSessionAction = { action ->
            sessionViewModel.onAction(action)
            if (action is CookSessionAction.OpenTimer) models[action.recipeId]?.onAction(CookAction.GoToPage(action.step))
        },
    )
    if (picker.open) {
        Surface { CookRecipePickerContent(state = picker, onAction = sessionViewModel::onAction) }
    }
}
