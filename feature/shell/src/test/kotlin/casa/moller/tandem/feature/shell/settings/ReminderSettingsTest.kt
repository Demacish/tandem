package casa.moller.tandem.feature.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.shell.PHONE
import casa.moller.tandem.feature.shell.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.accounts.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fas 5 (planera lane): both reminders on, at chosen times (Mer → Synk och lagring). */
internal val SAMPLE_SYNC_REMINDERS = SAMPLE_SYNC.copy(
    pantryExpiryNotifications = true,
    pantryExpiryTime = LocalTime(7, 30),
    mealReminders = true,
    mealReminderTime = LocalTime(19, 0),
)

/**
 * Fas 5 (planera lane), plan §8.3 and §8.8: the evening reminder's switch and the reminders' times
 * on Mer → Synk och lagring: screenshots on a phone (portrait, landscape) and a tablet (both
 * orientations), dark, Swedish, font scale 2 and with notifications blocked; ATF on each.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ReminderSettingsScreenshotTest(private val device: String) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reminders() = page("light") { FixedSettingsPages(sync = SAMPLE_SYNC_REMINDERS).Sync({}, {}) }

    @Test
    fun remindersDark() = page("dark", darkTheme = true) { FixedSettingsPages(sync = SAMPLE_SYNC_REMINDERS).Sync({}, {}) }

    @Test
    @Config(qualifiers = "+sv")
    fun remindersSwedish() = page("light_sv") { FixedSettingsPages(sync = SAMPLE_SYNC_REMINDERS).Sync({}, {}) }

    @Test
    fun remindersFontScale2() = page("font_scale_2_light", fontScale = 2f) { FixedSettingsPages(sync = SAMPLE_SYNC_REMINDERS).Sync({}, {}) }

    @Test
    @Config(qualifiers = "+sv")
    fun remindersBlockedDarkSwedish() = page("blocked_dark_sv", darkTheme = true) {
        FixedSettingsPages(sync = SAMPLE_SYNC_REMINDERS.copy(notificationsAllowed = false)).Sync({}, {})
    }

    private fun page(name: String, darkTheme: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme, content = content)
            }
        }
        // The new rows are below the sync's own cards: scroll so the screenshot shows them.
        composeRule.onNodeWithTag(reminderTimeTag("evening")).performScrollTo()
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/reminders_${device}_$name.png")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class ReminderSettingsPhoneScreenshotTest : ReminderSettingsScreenshotTest("phone")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LANDSCAPE)
class ReminderSettingsPhoneLandscapeScreenshotTest : ReminderSettingsScreenshotTest("phone_landscape")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class ReminderSettingsTabletScreenshotTest : ReminderSettingsScreenshotTest("tablet")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET_PORTRAIT)
class ReminderSettingsTabletPortraitScreenshotTest : ReminderSettingsScreenshotTest("tablet_portrait")

/** Fas 5 (planera lane): what the reminders' rows do. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class ReminderSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: SyncSettingsUiState): FixedSettingsPages {
        val pages = FixedSettingsPages(sync = state)
        composeRule.setContent { TandemTheme { pages.Sync({}, {}) } }
        return pages
    }

    @Test
    fun theEveningSwitchAsksAndItsTimeShowsOnlyWhileOn() {
        val pages = show(SAMPLE_SYNC)
        composeRule.onNodeWithTag(reminderTimeTag("evening")).assertDoesNotExist()
        composeRule.onNodeWithText("Reminder the evening before").performScrollTo().performClick()
        assertEquals(listOf<SettingsAction>(SettingsAction.SetMealReminders(true)), pages.actions)
    }

    @Test
    fun aTimeRowIsOneButtonThatOpensThePickerAndSavesTheTime() {
        val pages = show(SAMPLE_SYNC_REMINDERS)
        val row = composeRule.onNodeWithTag(reminderTimeTag("evening")).performScrollTo()
        row.assert(hasClickAction())
        val oneNode = SemanticsMatcher("one node with the time and what it is for") { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()
            "Time" in text && "7:00" in text && "When the evening reminder comes" in text
        }
        row.assert(oneNode)
        row.performClick()
        composeRule.onNode(isDialog()).assertIsDisplayed()
        composeRule.onNodeWithText("Time of the evening reminder").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(listOf<SettingsAction>(SettingsAction.SetMealReminderTime(LocalTime(19, 0))), pages.actions)

        composeRule.onNodeWithTag(reminderTimeTag("morning")).performScrollTo().performClick()
        composeRule.onNodeWithText("Time of the morning notification").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, pages.actions.size)
    }

    @Test
    fun aGuestSeesNeitherReminder() {
        show(SAMPLE_SYNC_REMINDERS.copy(readOnly = true))
        composeRule.onNodeWithText("Reminder the evening before").assertDoesNotExist()
        composeRule.onNodeWithTag(reminderTimeTag("morning")).assertDoesNotExist()
    }

    @Test
    fun theTimeDialogFitsAtFontScale2() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TandemTheme {
                    ReminderTimeDialog(initial = LocalTime(19, 0), title = "Time of the evening reminder", onDismiss = {}, onConfirm = {})
                }
            }
        }
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
