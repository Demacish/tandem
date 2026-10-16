package casa.moller.tandem.feature.recipes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.feature.recipes.detail.RecipeDetailScreen
import casa.moller.tandem.feature.recipes.detail.RecipeDetailViewModel
import casa.moller.tandem.feature.recipes.list.RecipeListAction
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import casa.moller.tandem.feature.recipes.list.RecipeListUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Compact phone (Pixel 9 class, portrait). */
internal const val PHONE = "w412dp-h915dp-port-xhdpi"

/** A phone screenshot tall enough for a whole recipe. */
internal const val PHONE_TALL = "w412dp-h2400dp-port-xhdpi"

/** Expanded tablet in landscape (Galaxy Tab S10+ class). */
internal const val TABLET = "w1280dp-h800dp-land-hdpi"

/** Medium window in portrait (small tablet, unfolded foldable). */
internal const val MEDIUM = "w700dp-h1000dp-port-hdpi"

/** The recipe list for [query] over the sample recipes, as the ViewModel would show it. */
internal fun listState(
    query: RecipeQuery = RecipeQuery.All,
    repository: FakeRecipeRepository = FakeRecipeRepository(),
    transform: RecipeListUiState.() -> RecipeListUiState = { this },
): RecipeListUiState {
    val recipes = runBlocking { repository.observeRecipes(query).first() }
    val foods = runBlocking { repository.observeFoods().first() }
    return RecipeListUiState(query = query, recipes = recipes, loaded = true, keywords = SampleRecipes.keywords, foods = foods).transform()
}

/** Theme, app chrome and font scale around a screen under test. */
@Composable
internal fun TestFrame(
    darkTheme: Boolean = false,
    chrome: AppChrome = AppChrome(),
    fontScale: Float? = null,
    content: @Composable () -> Unit,
) {
    TandemTheme(darkTheme = darkTheme) {
        val density = LocalDensity.current
        val scaled = if (fontScale == null) density else Density(density.density, fontScale)
        CompositionLocalProvider(LocalAppChrome provides chrome, LocalDensity provides scaled, content = content)
    }
}

/** Fakes behind a real [RecipeDetailViewModel], so screenshots show what the app computes. */
internal class DetailFakes(
    val repository: FakeRecipeRepository = FakeRecipeRepository(),
    val sync: FakeSyncRepository = FakeSyncRepository(),
    val preferences: FakePreferencesRepository = FakePreferencesRepository(),
    val sessions: SessionRepository = FakeSessionRepository(),
    val handle: SavedStateHandle = SavedStateHandle(),
)

/** The recipe detail driven by a real ViewModel over [fakes]. */
@Composable
internal fun DetailWithFakes(
    recipeId: Long,
    modifier: Modifier = Modifier,
    fakes: DetailFakes = remember { DetailFakes() },
    onBack: () -> Unit = {},
) {
    val viewModel = remember(recipeId) {
        RecipeDetailViewModel(recipeId, fakes.handle, fakes.repository, fakes.preferences, fakes.sync, fakes.sessions)
    }
    val state by viewModel.uiState.collectAsState()
    RecipeDetailScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenUrl = {},
        onOpenRecipe = {},
        modifier = modifier,
    )
}

/** List and detail side by side, like the shell's list-detail scene on wide windows. */
@Composable
internal fun ListDetail(list: RecipeListUiState, recipeId: Long, fakes: DetailFakes = remember { DetailFakes() }) {
    Row {
        RecipeListScreen(
            state = list,
            onAction = { _: RecipeListAction -> },
            onOpenRecipe = {},
            selectedRecipeId = recipeId,
            modifier = Modifier.width(LIST_PANE_WIDTH),
        )
        VerticalDivider(Modifier.fillMaxHeight())
        DetailWithFakes(recipeId = recipeId, fakes = fakes, modifier = Modifier.weight(1f))
    }
}

/** The list pane's width in Material's list-detail scaffold (its default preferred pane width). */
private val LIST_PANE_WIDTH = 360.dp
