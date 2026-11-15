package casa.moller.tandem.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.home.HomeLayout
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.SampleInbox
import casa.moller.tandem.feature.home.ui.TODAY_INBOX_TAG
import casa.moller.tandem.feature.home.ui.TodayCustomizeContent
import casa.moller.tandem.feature.home.ui.TodayInboxCard
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val INBOX_PHONE = "w412dp-h915dp-port-xhdpi"
private const val INBOX_PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
private const val INBOX_TABLET = "w1280dp-h800dp-land-mdpi"
private const val INBOX_TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"

/**
 * Fas 5 (rss lane, plan §7 #35): "Nytt i inkorgen" on Idag. The row shows the newest posts not imported
 * yet (at most three) when the inbox is set up and "Visa på Idag" is on; a post opens its preview, the
 * heading's button the inbox; "Anpassa Idag" offers the row only for a set-up inbox. Reference images
 * with ATF under `src/test/screenshots/home/` (phone, landscape, tablets, dark, Swedish, font scale 2).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = INBOX_PHONE)
class TodayInboxTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val inUse = TodayInboxState(
        available = true,
        shown = true,
        entries = SampleInbox.posts.filter { !it.imported },
        newCount = 2,
    )

    @Test
    fun aPostOpensItsPreviewAndTheButtonTheInbox() {
        val opened = mutableListOf<Long?>()
        composeRule.setContent { TandemTheme { TodayInboxCard(inbox = inUse, horizontal = 20.dp, onOpenInbox = { opened += it }) } }
        composeRule.onNodeWithText(SampleInbox.posts.first().title).performClick()
        composeRule.onNodeWithText("Open inbox").performClick()
        assertEquals(listOf<Long?>(11, null), opened)
    }

    @Test
    fun nothingNewOrTurnedOffShowsNothing() {
        composeRule.setContent {
            TandemTheme {
                Column {
                    TodayInboxCard(inbox = inUse.copy(entries = emptyList(), newCount = 0), horizontal = 20.dp, onOpenInbox = {})
                    TodayInboxCard(inbox = inUse.copy(shown = false), horizontal = 20.dp, onOpenInbox = {})
                }
            }
        }
        assertEquals(0, composeRule.onAllNodes(hasTestTag(TODAY_INBOX_TAG)).fetchSemanticsNodes().size)
    }

    @Test
    fun anpassaIdagHasTheRowOnlyForASetUpInbox() {
        val shown = mutableListOf<Boolean>()
        composeRule.setContent {
            TandemTheme {
                Column {
                    TodayCustomizeContent(layout = HomeLayout(), onAction = {}, inbox = inUse, onShowInbox = { shown += it })
                }
            }
        }
        composeRule.onNodeWithText("New in the inbox").assertIsOn().performClick()
        assertEquals(listOf(false), shown)
    }

    // --- screenshots and ATF ------------------------------------------------------------------

    @Test
    fun phone() = capture("phone_today_inbox_light")

    @Test
    @Config(qualifiers = "sv-$INBOX_PHONE")
    fun phoneSwedish() = capture("phone_today_inbox_light_sv")

    @Test
    fun phoneDark() = capture("phone_today_inbox_dark", darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$INBOX_PHONE")
    fun phoneFontScale2() = capture("phone_today_inbox_font2_light_sv", fontScale = 2f)

    @Test
    @Config(qualifiers = INBOX_PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_today_inbox_light")

    @Test
    @Config(qualifiers = "sv-$INBOX_TABLET")
    fun tablet() = capture("tablet_today_inbox_light_sv")

    @Test
    @Config(qualifiers = INBOX_TABLET_PORTRAIT)
    fun tabletPortrait() = capture("tablet_portrait_today_inbox_dark", darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$INBOX_PHONE")
    fun customizeWithTheInbox() {
        content(darkTheme = false, fontScale = null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                TodayCustomizeContent(layout = HomeLayout(), onAction = {}, inbox = inUse)
            }
        }
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/phone_today_customize_inbox_light_sv.png")
    }

    private fun capture(name: String, darkTheme: Boolean = false, fontScale: Float? = null) {
        content(darkTheme, fontScale) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    TodayInboxCard(inbox = inUse, horizontal = 20.dp, onOpenInbox = {})
                }
            }
        }
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/$name.png")
    }

    private fun content(darkTheme: Boolean, fontScale: Float?, body: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            val scaled = if (fontScale == null) density else Density(density.density, fontScale)
            CompositionLocalProvider(LocalDensity provides scaled) {
                TandemTheme(darkTheme = darkTheme) { body() }
            }
        }
        composeRule.waitForIdle()
    }
}
