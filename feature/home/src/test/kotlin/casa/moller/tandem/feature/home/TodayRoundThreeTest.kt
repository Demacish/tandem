package casa.moller.tandem.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.accessibility.assertNoWordBreaks
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.feature.home.ui.TODAY_LIST_TAG
import casa.moller.tandem.feature.home.ui.TodayScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tester's phone at the default text size (Galaxy S21 FE: 360 × 780 dp). */
private const val PHONE_SMALL = "w360dp-h780dp-port-xhdpi"

/** The same phone at the largest text and display size (320 × 693 dp, font scale 2.0). */
private const val PHONE_LARGEST_TEXT = "w320dp-h693dp-port-xhdpi"

/**
 * Enhetstestet 2026-11-14, omgång 3, the owner's screenshots and F1:
 * - S1: "Dina nedladdningar" and "Sparade med Ladda ner offline" were squeezed into narrow wrapped
 *   lines beside "Alla recept". The heading has priority: "Alla recept" stays beside it only while
 *   the heading and the subtitle each fit on one line, otherwise it moves under them.
 * - F1: the text under the carousels said "after the first sync" also after it had finished.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_SMALL)
class TodayRoundThreeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val downloads = HomeSection(HomeSectionType.PINNED, SampleRecipes.overviews.take(4))

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun s1HeadingSwedish() {
        show(sampleHomeState().copy(sections = listOf(downloads)))
        scrollTo("section-PINNED")
        assertHeadingHasPriority()
        composeRule.assertAccessible()
        capture("phone_small_devicetest_today_heading_light_sv")
    }

    @Test
    fun s1HeadingDark() {
        show(sampleHomeState().copy(sections = listOf(downloads)), darkTheme = true)
        scrollTo("section-PINNED")
        assertHeadingHasPriority()
        composeRule.assertAccessible()
        capture("phone_small_devicetest_today_heading_dark")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST_TEXT")
    fun s1HeadingLargestTextSwedish() {
        show(sampleHomeState().copy(sections = listOf(downloads)), fontScale = 2f)
        scrollTo("section-PINNED")
        assertHeadingHasPriority()
        composeRule.assertAccessible()
        capture("phone_largest_devicetest_today_heading_font2_light_sv")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun f1BeforeTheFirstSyncTheRowsWaitForIt() {
        show(sampleHomeState().copy(sections = emptyList(), lastSynced = null))
        scrollTo("sections-empty")
        composeRule.onNodeWithText(string(R.string.home_empty_sections)).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.home_empty_sections_synced)).isEmpty())
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun f1AfterTheFirstSyncTheTextSaysWhatFillsTheRows() {
        show(sampleHomeState().copy(sections = emptyList()))
        scrollTo("sections-empty")
        composeRule.onNodeWithText(string(R.string.home_empty_sections_synced)).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.home_empty_sections)).isEmpty())
        composeRule.assertAccessible()
        capture("phone_small_devicetest_today_rows_empty_after_sync_light_sv")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST_TEXT")
    fun f1AfterTheFirstSyncLargestTextSwedish() {
        show(sampleHomeState().copy(sections = emptyList()), fontScale = 2f)
        scrollTo("sections-empty")
        composeRule.assertNoWordBreaks(string(R.string.home_empty_sections_synced))
        composeRule.assertAccessible()
        capture("phone_largest_devicetest_today_rows_empty_after_sync_font2_light_sv")
    }

    /** Either the heading and its subtitle are one line each beside the button, or the button is under them. */
    private fun assertHeadingHasPriority() {
        val title = string(R.string.home_section_pinned)
        val subtitle = string(R.string.home_section_pinned_subtitle)
        val all = string(R.string.home_all_recipes)
        composeRule.assertNoWordBreaks(title, subtitle, all)
        val subtitleBounds = composeRule.onNodeWithText(subtitle).getUnclippedBoundsInRoot()
        val allBounds = composeRule.onNodeWithText(all, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val beside = allBounds.top < subtitleBounds.bottom
        if (beside) {
            assertEquals("the heading on one line beside \"$all\"", 1, lines(title))
            assertEquals("the subtitle on one line beside \"$all\"", 1, lines(subtitle))
        } else {
            assertTrue("\"$all\" under the subtitle", allBounds.top >= subtitleBounds.bottom)
        }
    }

    private fun lines(text: String): Int {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().lineCount
    }

    private fun show(state: HomeUiState, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(LocalAppChrome provides AppChrome()) {
                        TodayScreen(state = state, onAction = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun scrollTo(key: String) {
        composeRule.onNodeWithTag(TODAY_LIST_TAG).performScrollToKey(key)
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/$name.png")
    }

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.isEmpty(): Boolean = fetchSemanticsNodes().isEmpty()
