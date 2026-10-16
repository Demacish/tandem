package casa.moller.tandem.feature.recipes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.recipes.detail.DETAIL_STEP_PANE_TAG
import casa.moller.tandem.feature.recipes.detail.FixtureRecipes
import casa.moller.tandem.feature.recipes.detail.RecipeDetailViewModel
import casa.moller.tandem.feature.recipes.list.RecipeListLayout
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import casa.moller.tandem.feature.recipes.list.RecipeListUiState
import casa.moller.tandem.feature.recipes.list.ServerSearch
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val Demo = AppChrome(status = AppStatus.Demo, showDemoBanner = true)
private val Offline = AppChrome(status = AppStatus.Offline)

/**
 * Screenshots of the recipe list and the recipe detail (plan 6.5/6.6 test matrix): light and
 * dark, phone, medium and expanded windows, English and Swedish, font scale 2.0. Recorded in CI
 * and uploaded in the "screenshots" artifact (build/outputs/roborazzi/recipes/).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class RecipesScreenshotTest(private val device: String) {

    @get:Rule
    val composeRule = createComposeRule()

    protected fun capture(
        name: String,
        darkTheme: Boolean = false,
        chrome: AppChrome = AppChrome(),
        fontScale: Float? = null,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, chrome = chrome, fontScale = fontScale, content = content) }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/recipes/${device}_$name.png")
    }

    protected fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Composable
    protected fun ListScreen(state: RecipeListUiState, selected: Long? = null) {
        RecipeListScreen(state = state, onAction = {}, onOpenRecipe = {}, selectedRecipeId = selected)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class RecipesPhoneScreenshotTest : RecipesScreenshotTest("phone") {

    @Test
    fun list() = capture("list_light") { ListScreen(listState()) }

    @Test
    fun listDark() = capture("list_dark", darkTheme = true) { ListScreen(listState()) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun listSwedish() = capture("list_light_sv") { ListScreen(listState()) }

    @Test
    fun listAsRows() = capture("list_rows_light") { ListScreen(listState { copy(layout = RecipeListLayout.LIST) }) }

    @Test
    fun listAsRowsDark() = capture("list_rows_dark", darkTheme = true) { ListScreen(listState { copy(layout = RecipeListLayout.LIST) }) }

    @Test
    fun listDemo() = capture("list_demo_light", chrome = Demo) { ListScreen(listState()) }

    @Test
    fun listOffline() = capture("list_offline_light", chrome = Offline) { ListScreen(listState { copy(offline = true) }) }

    @Test
    fun listOfflineDark() = capture("list_offline_dark", darkTheme = true, chrome = Offline) {
        ListScreen(listState { copy(offline = true) })
    }

    @Test
    fun searchSearchingTheServer() = capture("list_search_light") {
        ListScreen(listState(RecipeQuery(text = "svamp", maxTotalMinutes = 30)) { copy(serverSearch = ServerSearch.SEARCHING) })
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun searchWithFiltersSwedish() = capture("list_search_filters_light_sv") {
        val query = RecipeQuery(keywordIds = setOf(SampleRecipes.Baking.id), minRating = 4.0)
        ListScreen(listState(query) { copy(serverSearch = ServerSearch.DONE, serverOnlyCount = 0) })
    }

    @Test
    fun searchOffline() = capture("list_search_offline_light", chrome = Offline) {
        ListScreen(listState(RecipeQuery(text = "soppa")) { copy(offline = true, serverSearch = ServerSearch.OFFLINE) })
    }

    @Test
    fun nothingMatches() = capture("list_empty_no_match_light") {
        ListScreen(listState(RecipeQuery(text = "lasagne")) { copy(serverSearch = ServerSearch.DONE) })
    }

    @Test
    fun noRecipesYet() = capture("list_empty_light") { ListScreen(RecipeListUiState(loaded = true)) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun noRecipesYetSwedish() = capture("list_empty_light_sv") { ListScreen(RecipeListUiState(loaded = true)) }

    @Test
    fun noRecipesOffline() = capture("list_empty_offline_dark", darkTheme = true, chrome = Offline) {
        ListScreen(RecipeListUiState(loaded = true, offline = true))
    }

    @Test
    fun firstSync() = capture("list_first_sync_light") { ListScreen(RecipeListUiState(loaded = true, syncing = true)) }

    @Test
    fun listFontScale2() = capture("list_font_scale_2_light", fontScale = 2f) { ListScreen(listState()) }

    /** The offline hint under the filters wraps instead of being cut off (plan 6.6). */
    @Test
    fun listOfflineFontScale2() = capture("list_offline_font_scale_2_light", chrome = Offline, fontScale = 2f) {
        ListScreen(listState { copy(offline = true) })
    }

    /** A sync that failed (here: blocked by a proxy) explains the empty list instead of "No recipes yet". */
    @Test
    fun noRecipesAfterAFailedSync() = capture("list_empty_sync_failed_light") {
        ListScreen(RecipeListUiState(loaded = true, syncError = TandemError.Blocked))
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun noRecipesAfterAFailedSyncSwedish() = capture("list_empty_sync_failed_light_sv") {
        ListScreen(RecipeListUiState(loaded = true, syncError = TandemError.ServerError(502)))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun keywordPicker() {
        composeRule.setContent { TestFrame { ListScreen(listState()) } }
        val keywords = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.recipes_filter_keywords)
        composeRule.onNodeWithText(keywords).performClick()
        composeRule.waitForIdle()
        captureScreenRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/recipes/phone_keyword_picker_light.png")
    }

    @Test
    fun detail() = capture("detail_light") { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    @Test
    fun detailDark() = capture("detail_dark", darkTheme = true) { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun detailSwedish() = capture("detail_light_sv") { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    @Test
    fun detailFontScale2() = capture("detail_font_scale_2_light", fontScale = 2f) { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    /** The saved-version line wraps at font scale 2.0: the date stays visible. */
    @Test
    fun detailSavedVersionFontScale2() = capture("detail_saved_version_font_scale_2_light", chrome = Offline, fontScale = 2f) {
        DetailWithFakes(SampleRecipes.PeaSoup.id, fakes = remember { savedVersionFakes() })
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun detailSavedVersionFontScale2Swedish() = capture("detail_saved_version_font_scale_2_light_sv", chrome = Offline, fontScale = 2f) {
        DetailWithFakes(SampleRecipes.PeaSoup.id, fakes = remember { savedVersionFakes() })
    }

    @Test
    fun detailNotDownloadedOffline() = capture("detail_not_downloaded_offline_light", chrome = Offline) {
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
    fun detailError() = capture("detail_error_light") {
        val fakes = remember {
            DetailFakes(
                repository = FakeRecipeRepository().apply {
                    detailOverrides.value = mapOf(999L to RecipeDetailState(recipeId = 999, error = TandemError.Timeout))
                },
            )
        }
        DetailWithFakes(999, fakes = fakes)
    }

    @Test
    fun detailDemo() = capture("detail_demo_light", chrome = AppChrome(status = AppStatus.Demo)) {
        DetailWithFakes(SampleRecipes.CinnamonBuns.id, fakes = remember { DetailFakes(sessions = FakeSessionRepository(Session.Demo)) })
    }
}

/** Whole recipes on a tall phone, to review every section in one image. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class RecipesTallPhoneScreenshotTest : RecipesScreenshotTest("phone_tall") {

    @Test
    fun detailScaledWithFractions() = capture("detail_scaled_fractions_light") {
        val fakes = remember {
            DetailFakes(
                repository = FakeRecipeRepository().apply { servingsChoices.value = mapOf(SampleRecipes.MushroomPasta.id to 6.0) },
                preferences = FakePreferencesRepository(UserPreferences(useFractions = true)),
                handle = SavedStateHandle(mapOf(RecipeDetailViewModel.KEY_STRUCK to longArrayOf(10111, 10112))),
            )
        }
        DetailWithFakes(SampleRecipes.MushroomPasta.id, fakes = fakes)
    }

    /** A recorded Tandoor recipe with templates in its steps, halved, fractions on (web parity, plan §10 Fas 1). */
    @Test
    fun detailTemplatesHalvedWithFractions() = capture("detail_templates_fractions_light") {
        DetailWithFakes(MUSHROOM_PASTA_FIXTURE_ID, fakes = remember { fixtureFakes(servings = 2.0, useFractions = true) })
    }

    /** The same recipe doubled in Swedish: decimal comma, plural names, templates scaled. */
    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun detailTemplatesDoubledSwedish() = capture("detail_templates_light_sv") {
        DetailWithFakes(MUSHROOM_PASTA_FIXTURE_ID, fakes = remember { fixtureFakes(servings = 8.0, useFractions = false) })
    }

    @Test
    fun detailHeadersAndSubRecipeDark() = capture("detail_buns_dark", darkTheme = true) { DetailWithFakes(SampleRecipes.CinnamonBuns.id) }

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun detailSavedVersionSwedish() = capture("detail_saved_version_light_sv", chrome = Offline) {
        DetailWithFakes(SampleRecipes.PeaSoup.id, fakes = remember { savedVersionFakes() })
    }
}

/** The pea soup's stored copy from 5 Oct 2026, shown offline after a failed refresh ("Saved version from …"). */
private fun savedVersionFakes(): DetailFakes = DetailFakes(
    repository = FakeRecipeRepository().apply {
        detailOverrides.value = mapOf(
            SampleRecipes.PeaSoup.id to RecipeDetailState(
                recipeId = SampleRecipes.PeaSoup.id,
                overview = SampleRecipes.overview(SampleRecipes.PeaSoup.id),
                recipe = SampleRecipes.PeaSoup,
                fetchedAt = kotlin.time.Instant.parse("2026-10-05T12:00:00Z"),
                error = TandemError.NetworkUnavailable,
            ),
        )
    },
    sync = FakeSyncRepository(SyncStatus(pause = SyncPause.OFFLINE)),
)

/** The recorded Tandoor 2.6.13 "Krämig svamppasta" (api-schema/fixtures, with templates and conversions). */
private const val MUSHROOM_PASTA_FIXTURE_ID = 2L

/** Fakes that hold only the recorded mushroom pasta, at [servings] with `use_fractions` = [useFractions]. */
private fun fixtureFakes(servings: Double, useFractions: Boolean): DetailFakes {
    val recipe = FixtureRecipes.recipe("recipe-svamppasta.json")
    check(recipe.id == MUSHROOM_PASTA_FIXTURE_ID) { "unexpected fixture id ${recipe.id}" }
    return DetailFakes(
        repository = FakeRecipeRepository(details = listOf(recipe), overviews = emptyList()).apply {
            servingsChoices.value = mapOf(recipe.id to servings)
        },
        preferences = FakePreferencesRepository(UserPreferences(useFractions = useFractions)),
    )
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class RecipesTabletScreenshotTest : RecipesScreenshotTest("tablet") {

    @Test
    fun listDetail() = capture("list_detail_light") { ListDetail(listState(), SampleRecipes.MushroomPasta.id) }

    @Test
    fun listDetailDark() = capture("list_detail_dark", darkTheme = true) { ListDetail(listState(), SampleRecipes.MushroomPasta.id) }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun listDetailSwedish() = capture("list_detail_light_sv") { ListDetail(listState(), SampleRecipes.CinnamonBuns.id) }

    @Test
    fun listDetailFontScale2() = capture("list_detail_font_scale_2_light", fontScale = 2f) {
        ListDetail(listState(), SampleRecipes.MushroomPasta.id)
    }

    @Test
    fun detailFullWidth() = capture("detail_full_width_light") { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    /**
     * Plan 6.5: the ingredients stay in view while the steps scroll. The step pane is scrolled to
     * its end; the ingredient pane has not moved.
     */
    @Test
    fun detailStepsScrolledKeepTheIngredientsInView() {
        composeRule.setContent {
            TestFrame { DetailWithFakes(MUSHROOM_PASTA_FIXTURE_ID, fakes = remember { fixtureFakes(servings = 4.0, useFractions = false) }) }
        }
        composeRule.waitForIdle()
        val ingredients = composeRule.onNodeWithText(string(R.string.recipe_detail_ingredients))
        ingredients.assertIsDisplayed()
        composeRule.onNodeWithTag(DETAIL_STEP_PANE_TAG).performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, 20_000f) }
        composeRule.waitForIdle()
        ingredients.assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/recipes/tablet_detail_steps_scrolled_light.png")
    }

    @Test
    fun listOnly() = capture("list_light") { ListScreen(listState()) }

    /** Plan 6.5: on expanded windows the keyword filter is a side panel beside the recipes, not a bottom sheet. */
    @Test
    fun keywordPanelBesideTheList() {
        composeRule.setContent { TestFrame { ListScreen(listState()) } }
        composeRule.onNodeWithText(string(R.string.recipes_filter_keywords)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(string(R.string.recipes_keywords_close)).assertIsDisplayed()
        composeRule.onNodeWithText(SampleRecipes.MushroomPasta.name).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/recipes/tablet_list_keyword_panel_light.png")
    }

    /** In the narrow list pane beside a recipe, the panel takes the pane's place; the recipe stays open. */
    @Test
    fun keywordPanelInTheListPane() {
        composeRule.setContent { TestFrame { ListDetail(listState(), SampleRecipes.MushroomPasta.id) } }
        composeRule.onNodeWithText(string(R.string.recipes_filter_keywords)).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(SampleRecipes.Fika.name).onFirst().assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/recipes/tablet_list_detail_keyword_panel_light.png")
        composeRule.onNodeWithContentDescription(string(R.string.recipes_keywords_close)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.recipes_filter_keywords)).assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = MEDIUM)
class RecipesMediumScreenshotTest : RecipesScreenshotTest("medium") {
    // Medium windows show one pane at a time (the shell's list-detail scene splits only when expanded).

    @Test
    fun list() = capture("list_light") { ListScreen(listState()) }

    @Test
    fun listDark() = capture("list_dark", darkTheme = true) { ListScreen(listState()) }

    @Test
    fun detail() = capture("detail_light") { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    @Test
    fun detailDark() = capture("detail_dark", darkTheme = true) { DetailWithFakes(SampleRecipes.MushroomPasta.id) }

    @Test
    @Config(qualifiers = "sv-$MEDIUM")
    fun detailSwedish() = capture("detail_light_sv") { DetailWithFakes(SampleRecipes.CinnamonBuns.id) }
}
