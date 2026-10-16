package casa.moller.tandem.feature.recipes

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.recipes.detail.FixtureRecipes
import casa.moller.tandem.feature.recipes.list.RecipeListLayout
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import casa.moller.tandem.feature.recipes.list.RecipeListUiState
import casa.moller.tandem.feature.recipes.list.ServerSearch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6 and Fas 1 "Klart när") of the
 * recipe list and the recipe detail: grid and rows, filters, the keyword and food pickers, offline (dimmed
 * recipes), the detail with ingredient rows, conversions and templates, light and dark, font scale 2
 * and the tablet list-detail.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class RecipeAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(
        darkTheme: Boolean = false,
        chrome: AppChrome = AppChrome(),
        fontScale: Float? = null,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, chrome = chrome, fontScale = fontScale, content = content) }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Composable
    private fun ListScreen(state: RecipeListUiState, selected: Long? = null) {
        RecipeListScreen(state = state, onAction = {}, onOpenRecipe = {}, selectedRecipeId = selected)
    }

    @Test
    fun listGrid() = check { ListScreen(listState()) }

    @Test
    fun listGridDark() = check(darkTheme = true) { ListScreen(listState()) }

    @Test
    fun listRows() = check { ListScreen(listState { copy(layout = RecipeListLayout.LIST) }) }

    @Test
    fun listFontScale2() = check(fontScale = 2f) { ListScreen(listState()) }

    @Test
    fun listOffline() = check(chrome = AppChrome(status = AppStatus.Offline)) { ListScreen(listState { copy(offline = true) }) }

    @Test
    fun listOfflineDark() = check(darkTheme = true, chrome = AppChrome(status = AppStatus.Offline)) {
        ListScreen(listState { copy(offline = true) })
    }

    @Test
    fun searchWithFilters() = check {
        val query = RecipeQuery(
            text = "svamp",
            keywordIds = setOf(SampleRecipes.Baking.id),
            foodIds = setOf(SampleRecipes.food("smör").id),
            maxTotalMinutes = 30,
            minRating = 4.0,
        )
        ListScreen(listState(query) { copy(serverSearch = ServerSearch.DONE, serverOnlyCount = 0) })
    }

    @Test
    fun noRecipesYet() = check { ListScreen(RecipeListUiState(loaded = true)) }

    @Test
    fun keywordSheet() {
        composeRule.setContent { TestFrame { ListScreen(listState()) } }
        val keywords = ApplicationProvider.getApplicationContext<Context>().getString(R.string.recipes_filter_keywords)
        composeRule.onNodeWithText(keywords).performClick()
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun foodSheet() {
        composeRule.setContent { TestFrame { ListScreen(listState()) } }
        val foods = ApplicationProvider.getApplicationContext<Context>().getString(R.string.recipes_filter_foods)
        composeRule.onNodeWithText(foods).performClick()
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletFoodPanelDark() {
        val butter = SampleRecipes.food("smör")
        composeRule.setContent { TestFrame(darkTheme = true) { ListScreen(listState(RecipeQuery(foodIds = setOf(butter.id)))) } }
        composeRule.onNodeWithText(butter.name).performClick()
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun detail() = check { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun detailDark() = check(darkTheme = true) { DetailWithFakes(SampleRecipes.CinnamonBuns.id) }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun detailFontScale2() = check(fontScale = 2f) { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    /** The recorded Tandoor recipe: ingredient rows with conversions (tap to cycle) and templates. */
    @Test
    @Config(qualifiers = PHONE_TALL)
    fun detailWithConversionsAndFractions() = check {
        val fakes = remember {
            val recipe = FixtureRecipes.recipe("recipe-svamppasta.json")
            DetailFakes(
                repository = FakeRecipeRepository(details = listOf(recipe), overviews = emptyList()).apply {
                    servingsChoices.value = mapOf(recipe.id to 2.0)
                },
                preferences = FakePreferencesRepository(UserPreferences(useFractions = true)),
            )
        }
        DetailWithFakes(FixtureRecipes.recipe("recipe-svamppasta.json").id, fakes = fakes)
    }

    @Test
    fun detailNotDownloadedOffline() = check(chrome = AppChrome(status = AppStatus.Offline)) {
        val fakes = remember {
            DetailFakes(
                repository = FakeRecipeRepository().apply {
                    detailOverrides.value = mapOf(
                        SampleRecipes.PeaSoup.id to RecipeDetailState(
                            recipeId = SampleRecipes.PeaSoup.id,
                            overview = SampleRecipes.overview(SampleRecipes.PeaSoup.id),
                            error = TandemError.NetworkUnavailable,
                        ),
                    )
                },
                sync = FakeSyncRepository(SyncStatus(pause = SyncPause.OFFLINE)),
            )
        }
        DetailWithFakes(SampleRecipes.PeaSoup.id, fakes = fakes)
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletListDetail() = check { ListDetail(listState(), SampleRecipes.MushroomPasta.id) }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletListDetailDark() = check(darkTheme = true) { ListDetail(listState(), SampleRecipes.CinnamonBuns.id) }
}
