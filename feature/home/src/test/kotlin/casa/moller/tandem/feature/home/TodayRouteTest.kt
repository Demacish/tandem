package casa.moller.tandem.feature.home

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.testing.data.FakeHomeRepository
import casa.moller.tandem.core.testing.data.FakeRecipeBookRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.home.ui.TODAY_LIST_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Today route end to end with the real ViewModel on the core:testing fakes. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp-port-xhdpi")
class TodayRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val home = FakeHomeRepository()
    private val sync = FakeSyncRepository()
    private val openedRecipes = mutableListOf<Long>()
    private var openedRecipesTab = 0
    private var openedWeek = 0

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun setContent() {
        val viewModel = HomeViewModel(home, sync, currentHour = { 18 })
        composeRule.setContent {
            TandemTheme {
                TodayRoute(
                    onOpenRecipe = { openedRecipes += it },
                    onOpenRecipes = { openedRecipesTab++ },
                    onOpenWeek = { openedWeek++ },
                    viewModel = viewModel,
                    booksViewModel = TodayBooksViewModel(FakeRecipeBookRepository(), FakeSpacePermissionsRepository()),
                )
            }
        }
    }

    @Test
    fun tonightOpensTheRecipe() {
        setContent()
        // The tonight card comes first; the pasta is also in a carousel further down.
        composeRule.onAllNodesWithText(SampleRecipes.MushroomPasta.name).onFirst().assertIsDisplayed().performClick()
        assertEquals(listOf(SampleRecipes.MushroomPasta.id), openedRecipes)
    }

    @Test
    fun carouselsOpenRecipesAndAllRecipesSwitchesTab() {
        setContent()
        val list = composeRule.onNodeWithTag(TODAY_LIST_TAG)
        list.performScrollToNode(hasText(string(R.string.home_all_recipes)))
        composeRule.onNode(hasText(string(R.string.home_all_recipes)) and hasClickAction()).performClick()
        assertEquals(1, openedRecipesTab)
        list.performScrollToNode(hasText(SampleRecipes.Pancakes.name))
        composeRule.onNodeWithText(SampleRecipes.Pancakes.name).performClick()
        assertEquals(listOf(SampleRecipes.Pancakes.id), openedRecipes)
    }

    @Test
    fun nothingPlannedLeadsToTheRecipesNotToThePlaceholderWeek() {
        home.upcomingMeals.value = UpcomingMeals(today = SampleRecipes.Today)
        setContent()
        composeRule.onNodeWithText(string(R.string.home_nothing_planned_title)).assertIsDisplayed()
        // The Week tab is a placeholder in 0.1.0: no "Plan the week" button leads there.
        composeRule.onAllNodes(hasText(string(R.string.home_plan_week))).assertCountEquals(0)
        composeRule.onNode(hasText(string(R.string.home_browse_recipes)) and hasClickAction()).performClick()
        assertEquals(1, openedRecipesTab)
        assertEquals(0, openedWeek)
    }

    @Test
    fun syncNowRefreshesAndAsksForASync() {
        setContent()
        val list = composeRule.onNodeWithTag(TODAY_LIST_TAG)
        list.performScrollToNode(hasText(string(R.string.home_sync_now)))
        composeRule.onNode(hasText(string(R.string.home_sync_now)) and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertEquals(2, home.refreshCount)
        assertEquals(listOf(SyncReason.USER_REFRESH), sync.requests)
    }
}
