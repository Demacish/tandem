package casa.moller.tandem.feature.shell

import androidx.annotation.StringRes
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.shell.navigation.ShellTab
import casa.moller.tandem.feature.shell.ui.label
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tested phone (Galaxy S21 FE class) in landscape: 780 × 360 dp. */
internal const val PHONE_SMALL_LANDSCAPE = "w780dp-h360dp-land-xhdpi"

/** The same phone at its largest display size, in landscape (693 × 320 dp). */
internal const val PHONE_ZOOMED_LANDSCAPE = "w693dp-h320dp-land-xhdpi"

/** The same phone at its largest display size, in portrait (320 × 693 dp). */
internal const val PHONE_ZOOMED = "w320dp-h693dp-port-xhdpi"

/**
 * Device test 2026-11-14, round 3 (Galaxy S21 FE):
 *
 * - **B21:** in landscape all five tabs are in view and can be tapped (Mer was below the screen
 *   edge), at 780 × 360 dp and at 693 × 320 dp with font scale 2: the short rail.
 * - **B22 (flikar):** at font scale 2 on 320 dp no tab label is broken inside a word; without room
 *   the bar shows icons only and TalkBack still says every tab's name.
 *
 * Screenshots `shell/devicetest_tabs_*` and ATF for both.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_SMALL_LANDSCAPE)
class ShellNavigationDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(@StringRes id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun setShell(tab: ShellTab = ShellTab.TODAY, fontScale: Float = 1f, darkTheme: Boolean = false) {
        val navigation = navigationAt(tab)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    FrozenTimers {
                        ShellScaffold(
                            state = CONNECTED_STATE,
                            navigation = navigation,
                            actions = RecordingActions(),
                            language = AppLanguage.SYSTEM,
                            onLanguageSelected = {},
                            onConnectTandoor = {},
                            features = PlaceholderShellFeatures(CONNECTED_STATE),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // --- B21 ---

    @Test
    fun theShortRailIsForShortWideWindowsOnly() {
        assertTrue(shellUsesShortRail(NavigationSuiteType.WideNavigationRailCollapsed, DpSize(780.dp, 360.dp)))
        assertTrue(shellUsesShortRail(NavigationSuiteType.WideNavigationRailCollapsed, DpSize(693.dp, 320.dp)))
        assertTrue(shellUsesShortRail(NavigationSuiteType.WideNavigationRailCollapsed, DpSize(915.dp, 412.dp)))
        assertFalse(shellUsesShortRail(NavigationSuiteType.WideNavigationRailCollapsed, DpSize(700.dp, 1000.dp)))
        assertFalse(shellUsesShortRail(NavigationSuiteType.WideNavigationRailExpanded, DpSize(1280.dp, 800.dp)))
        assertFalse(shellUsesShortRail(NavigationSuiteType.ShortNavigationBarCompact, DpSize(360.dp, 780.dp)))
    }

    @Test
    fun inLandscapeEveryTabIsInViewAndOpens() {
        setShell()
        composeRule.onNodeWithTag(SHELL_SHORT_RAIL_TAG).assertIsDisplayed()
        assertEveryTabReachable()
    }

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun atTheLargestSizeInLandscapeEveryTabIsInViewAndOpens() {
        setShell(fontScale = 2f)
        assertEveryTabReachable()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED_LANDSCAPE")
    fun atTheLargestSizeInSwedishEveryTabIsInViewAndOpens() {
        setShell(fontScale = 2f)
        assertEveryTabReachable()
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun aTallerLandscapePhoneShowsEveryLabel() {
        setShell()
        ShellTab.entries.forEach { composeRule.onNode(hasText(string(it.label))).assertIsDisplayed() }
        assertEveryTabReachable()
    }

    // --- B22 ---

    @Test
    @Config(qualifiers = PHONE_ZOOMED)
    fun atFontScale2On320dpNoLabelIsBrokenAndTalkBackHasEveryName() {
        setShell(fontScale = 2f)
        composeRule.onNodeWithTag(SHELL_TEST_TAG_PREFIX + NavigationSuiteType.ShortNavigationBarCompact).assertExists()
        assertLabelsWholeOrIcons()
        assertEveryTabReachable()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED")
    fun atFontScale2On320dpInSwedishNoLabelIsBroken() {
        setShell(fontScale = 2f)
        assertLabelsWholeOrIcons()
        assertEveryTabReachable()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun aPhoneAtFontScale1KeepsItsLabels() {
        setShell()
        ShellTab.entries.forEach { composeRule.onNode(hasText(string(it.label))).assertIsDisplayed() }
    }

    // --- Screenshots and ATF ---

    @Test
    fun railScreenshot() = capture("devicetest_tabs_rail_landscape_light", ShellTab.MORE)

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun railSwedishDarkScreenshot() = capture("devicetest_tabs_rail_landscape_dark_sv", ShellTab.SHOPPING, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun railFontScale2Screenshot() = capture("devicetest_tabs_rail_zoomed_font2_light", ShellTab.MORE, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED_LANDSCAPE")
    fun railFontScale2SwedishScreenshot() = capture("devicetest_tabs_rail_zoomed_font2_light_sv", ShellTab.RECIPES, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_ZOOMED)
    fun barFontScale2Screenshot() = capture("devicetest_tabs_bar_zoomed_font2_light", ShellTab.TODAY, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED")
    fun barFontScale2SwedishDarkScreenshot() =
        capture("devicetest_tabs_bar_zoomed_font2_dark_sv", ShellTab.WEEK, fontScale = 2f, darkTheme = true)

    @Test
    @Config(qualifiers = "w600dp-h400dp-land-hdpi")
    fun splitScreenTabletScreenshot() = capture("devicetest_tabs_rail_split_light", ShellTab.TODAY)

    @Test
    fun railAccessible() {
        setShell(ShellTab.MORE)
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun railFontScale2Accessible() {
        setShell(ShellTab.MORE, fontScale = 2f)
        composeRule.assertAccessible()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED")
    fun barFontScale2Accessible() {
        setShell(fontScale = 2f)
        composeRule.assertAccessible()
    }

    // --- helpers ---

    private fun capture(name: String, tab: ShellTab, fontScale: Float = 1f, darkTheme: Boolean = false) {
        setShell(tab, fontScale, darkTheme)
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/$name.png")
    }

    /** Every tab: whole inside the window, a tab with its name for TalkBack, and a tap opens it. */
    private fun assertEveryTabReachable() {
        ShellTab.entries.forEach { tab ->
            val item = composeRule.onNodeWithTag(shellTabTag(tab))
            item.assertWhollyVisible().assertHasClickAction()
            val name = string(tab.label)
            item.assert(hasText(name) or hasContentDescription(name))
        }
        // From the last one back to the first, so every tab is opened once.
        ShellTab.entries.reversed().forEach { tab ->
            composeRule.onNodeWithTag(shellTabTag(tab)).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(shellTabTag(tab)).assertIsSelected()
            if (tab == ShellTab.MORE) {
                // Mer is the shell's own page: its settings rows (Inställningar, Diagnostik, logout) are there.
                composeRule.onNodeWithText(string(R.string.shell_language)).assertExists()
            } else {
                composeRule.onNodeWithText(tabStubText(tab)).assertIsDisplayed()
            }
        }
    }

    /** No tab label on two lines or cut; where none shows, the icons carry the names. */
    private fun assertLabelsWholeOrIcons() {
        ShellTab.entries.forEach { tab ->
            val name = string(tab.label)
            val shown = composeRule.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes()
                .filter { it.layoutInfo.isPlaced && it.textLayoutOrNull() != null }
            shown.forEach { node ->
                val layout = node.textLayoutOrNull()!!
                assertEquals("“$name” on one line", 1, layout.lineCount)
                assertFalse("“$name” not cut", layout.isLineEllipsized(0) || layout.hasVisualOverflow)
            }
            composeRule.onNodeWithTag(shellTabTag(tab)).assert(hasText(name) or hasContentDescription(name))
        }
    }

    private fun SemanticsNode.textLayoutOrNull(): TextLayoutResult? {
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val results = mutableListOf<TextLayoutResult>()
        action(results)
        return results.firstOrNull()
    }

    private fun SemanticsNodeInteraction.assertWhollyVisible(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val node = fetchSemanticsNode()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val shown = node.boundsInRoot
        assertTrue("whole height shown (${shown.height} of ${node.size.height})", shown.height >= node.size.height - 1f)
        assertTrue("inside the window (${shown.bottom} of ${root.bottom})", shown.bottom <= root.bottom + 1f)
        return this
    }
}
