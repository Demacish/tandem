package casa.moller.tandem.feature.recipes

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.accessibility.assertNoWordBreaks
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.recipes.list.RecipeListLayout
import casa.moller.tandem.feature.recipes.list.RecipeListUiState
import casa.moller.tandem.feature.recipes.list.ServerSearch
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tester's phone at the largest text and display size (Galaxy S21 FE: 320 × 693 dp). */
internal const val PHONE_LARGEST = "w320dp-h693dp-port-xhdpi"

/** The same phone at its normal display size (360 × 780 dp), where B18 showed at font scale 1.0. */
internal const val PHONE_SMALL = "w360dp-h780dp-port-xhdpi"

private val Offline = AppChrome(status = AppStatus.Offline)

/** Under 45 minutes: a filtered list with a result count over the sample recipes. */
private val Quick = RecipeQuery(maxTotalMinutes = 45)

/**
 * The device test of 2026-11-14 at font scale 2.0 on 320 dp (B22) and the offline search's count
 * (B18): the recipe view's "Ingredienser" and the servings stepper, the list as rows and as a grid,
 * and the offline search header. No word breaks inside a word, the ATF checks pass, and the
 * screenshots go into the goldens ("devicetest" in the name).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LARGEST)
class RecipesLargeTextTest : RecipesScreenshotTest("phone_largest") {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun detailServings() {
        showDetail()
        composeRule.onNodeWithText(string(R.string.recipe_detail_servings_label)).performScrollTo()
        captureNow("devicetest_detail_servings_font2_light")
        composeRule.assertNoWordBreaks(string(R.string.recipe_detail_servings_label))
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST")
    fun detailIngredientsSwedish() {
        showDetail()
        val heading = string(R.string.recipe_detail_ingredients)
        val hint = string(R.string.recipe_detail_ingredients_hint)
        // The heading at the top of the screen, under the top bar, so the rows follow it.
        composeRule.onNodeWithText(heading).performScrollTo()
        val top = composeRule.onNodeWithText(heading).getUnclippedBoundsInRoot().top
        val offset = with(composeRule.density) { (top - HEADING_TOP).toPx() }
        composeRule.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(heading))).onLast()
            .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, offset) }
        composeRule.waitForIdle()
        captureNow("devicetest_detail_ingredients_font2_light_sv")
        // B22: "Ingredienser" was one letter per line beside "Tryck för att bocka av".
        composeRule.assertNoWordBreaks(heading, hint)
        val headingBounds = composeRule.onNodeWithText(heading).getUnclippedBoundsInRoot()
        val hintBounds = composeRule.onNodeWithText(hint).getUnclippedBoundsInRoot()
        assertTrue("hint under the heading", hintBounds.top >= headingBounds.bottom)
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST")
    fun detailServingsDarkSwedish() {
        showDetail(darkTheme = true)
        composeRule.onNodeWithText(string(R.string.recipe_detail_servings_label)).performScrollTo()
        captureNow("devicetest_detail_servings_font2_dark_sv")
        composeRule.assertNoWordBreaks(string(R.string.recipe_detail_servings_label), "4 portioner")
    }

    @Test
    fun listAsRows() {
        val state = listState { copy(layout = RecipeListLayout.LIST) }
        show(state)
        captureNow("devicetest_list_rows_font2_light")
        // B22: the name was "Banan taco…" and the time one word per line.
        composeRule.assertNoWordBreaks(state.recipes.first().name)
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST")
    fun listAsRowsDarkSwedish() {
        show(listState { copy(layout = RecipeListLayout.LIST) }, darkTheme = true)
        captureNow("devicetest_list_rows_font2_dark_sv")
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST")
    fun listAsGridSwedish() {
        val state = listState()
        show(state)
        captureNow("devicetest_list_grid_font2_light_sv")
        composeRule.assertNoWordBreaks(state.recipes.first().name)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST")
    fun offlineSearchSwedish() {
        val state = offlineSearch()
        show(state, chrome = Offline)
        captureNow("devicetest_list_search_offline_font2_light_sv")
        // B18: the count was squeezed to about 16 dp, one or two letters per line.
        composeRule.assertNoWordBreaks(count(state))
        composeRule.assertAccessible()
    }

    private fun showDetail(darkTheme: Boolean = false) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = 2f) { DetailWithFakes(SampleRecipes.MushroomPasta.id) } }
        composeRule.waitForIdle()
    }

    private fun show(state: RecipeListUiState, darkTheme: Boolean = false, chrome: AppChrome = AppChrome()) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, chrome = chrome, fontScale = 2f) { ListScreen(state) } }
        composeRule.waitForIdle()
    }

    private fun captureNow(name: String) {
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/recipes/${PREFIX}_$name.png")
    }

    private fun count(state: RecipeListUiState): String =
        context.resources.getQuantityString(R.plurals.recipes_result_count, state.recipes.size, state.recipes.size)

    private companion object {
        const val PREFIX = "phone_largest"

        /** Below the top bar at font scale 2. */
        val HEADING_TOP = 96.dp
    }
}

/** B18 as it showed on the S21 FE: 360 dp, font scale 1.0, Swedish, offline search with a count. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE_SMALL")
class RecipesOfflineSearchSmallPhoneTest : RecipesScreenshotTest("phone_small") {

    @Test
    fun offlineSearchCount() {
        val state = offlineSearch()
        capture("devicetest_list_search_offline_light_sv", chrome = Offline) { ListScreen(state) }
        val count = ApplicationProvider.getApplicationContext<Context>().resources
            .getQuantityString(R.plurals.recipes_result_count, state.recipes.size, state.recipes.size)
        // The hint itself is hidden from TalkBack inside its IconLabel; the screenshot shows it.
        composeRule.assertNoWordBreaks(count)
        composeRule.assertAccessible()
    }
}

private fun offlineSearch(): RecipeListUiState = listState(Quick) { copy(offline = true, serverSearch = ServerSearch.OFFLINE) }
