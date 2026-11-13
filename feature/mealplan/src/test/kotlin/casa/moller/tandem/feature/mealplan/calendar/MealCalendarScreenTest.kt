package casa.moller.tandem.feature.mealplan.calendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.mealplan.MealCalendarState
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.mealplan.Devices.PHONE
import casa.moller.tandem.feature.mealplan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.mealplan.Devices.TABLET
import casa.moller.tandem.feature.mealplan.Devices.TABLET_PORTRAIT
import casa.moller.tandem.feature.mealplan.MealPlanTestTheme
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas 5 (kalender lane, plan §7 #36): "Matsedeln i kalendern": the switch is one TalkBack node with
 * the role "switch", the link's buttons send their actions, a guest, an old server and demo mode
 * get an explanation instead of the option. Screenshots with ATF on each: phone (portrait and
 * landscape), tablet in both orientations, dark, Swedish and font scale 2, of every state.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class MealCalendarScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: MealCalendarUiState, actions: MutableList<MealCalendarAction> = mutableListOf()) {
        composeRule.setContent {
            MealPlanTestTheme { MealCalendarScreen(state = state, onAction = { actions += it }, onBack = {}) }
        }
    }

    @Test
    fun theSwitchIsOneNodeAndTurnsTheCalendarOn() {
        val actions = mutableListOf<MealCalendarAction>()
        show(MealCalendarSamples.ui(MealCalendarSamples.off), actions)
        composeRule.onNodeWithTag(MEAL_CALENDAR_LOCAL_TAG).assertIsOff().performClick()
        assertEquals(MealCalendarAction.SetLocal(true), actions.single())
        composeRule.onNodeWithTag(MEAL_CALENDAR_CONTENT_TAG).performScrollToNode(hasTestTag(MEAL_CALENDAR_CREATE_LINK_TAG))
        composeRule.onNodeWithTag(MEAL_CALENDAR_CREATE_LINK_TAG).performClick()
        assertEquals(MealCalendarAction.CreateLink, actions.last())
        composeRule.onNodeWithText("Anyone with the link can read your meal plan").assertExists()
    }

    @Test
    fun theLinksButtonsSendTheirActions() {
        val actions = mutableListOf<MealCalendarAction>()
        show(MealCalendarSamples.ui(MealCalendarSamples.on), actions)
        composeRule.onNodeWithTag(MEAL_CALENDAR_LOCAL_TAG).assertIsOn()
        composeRule.onNodeWithTag(MEAL_CALENDAR_CONTENT_TAG).performScrollToNode(hasTestTag(MEAL_CALENDAR_ADDRESS_TAG))
        composeRule.onNodeWithText(MealCalendarSamples.URL).assertIsDisplayed()
        for (label in listOf("Copy", "Share", "Open in calendar app", "New link", "Turn off")) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
        }
        assertEquals(
            listOf(
                MealCalendarAction.CopyLink,
                MealCalendarAction.ShareLink,
                MealCalendarAction.OpenInCalendarApp,
                MealCalendarAction.AskReplace,
                MealCalendarAction.AskDisable,
            ),
            actions,
        )
    }

    @Test
    fun withoutACalendarAppTheLinkSaysHowToAddIt() {
        show(MealCalendarSamples.ui(MealCalendarSamples.on, canOpen = false))
        composeRule.onNodeWithTag(MEAL_CALENDAR_CONTENT_TAG).performScrollToNode(hasTestTag(MEAL_CALENDAR_ADDRESS_TAG))
        assertEquals(0, composeRule.onAllNodes(hasText("Open in calendar app")).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
    }

    @Test
    fun theConfirmationSaysWhatHappens() {
        val actions = mutableListOf<MealCalendarAction>()
        show(MealCalendarSamples.ui(MealCalendarSamples.on, confirm = LinkConfirm.REPLACE), actions)
        composeRule.onNodeWithText("Make a new link?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(MealCalendarAction.Dismiss, actions.single())
    }

    @Test
    fun aGuestIsToldWhyThereIsNoLink() {
        show(MealCalendarSamples.ui(MealCalendarSamples.guest))
        composeRule.onNodeWithText("Guests cannot create a link").assertExists()
        composeRule.onNodeWithTag(MEAL_CALENDAR_LOCAL_TAG).assertIsOn()
    }

    @Test
    fun anOldServerIsToldWhyThereIsNoLink() {
        show(MealCalendarSamples.ui(MealCalendarSamples.unsupported))
        composeRule.onNodeWithText("This server cannot be subscribed to").assertExists()
    }

    @Test
    fun demoModeHasNoOptions() {
        show(MealCalendarSamples.ui(MealCalendarSamples.demo))
        composeRule.onNodeWithText("Not in demo mode").assertExists()
        assertEquals(0, composeRule.onAllNodes(hasTestTag(MEAL_CALENDAR_LOCAL_TAG)).fetchSemanticsNodes().size)
    }

    // --- screenshots and ATF ---------------------------------------------------------------------

    @Test
    fun phoneOff() = capture("phone_meal_calendar_off_light", MealCalendarSamples.off)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneOnSwedish() = capture("phone_meal_calendar_on_light_sv", MealCalendarSamples.on, scrollTo = MEAL_CALENDAR_ADDRESS_TAG)

    @Test
    fun phoneOnDark() = capture("phone_meal_calendar_on_dark", MealCalendarSamples.on, darkTheme = true)

    @Test
    fun phoneFontScale2() = capture("phone_meal_calendar_on_font2_light", MealCalendarSamples.on, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phonePermissionLost() = capture("phone_meal_calendar_permission_lost_light_sv", MealCalendarSamples.permissionLost)

    @Test
    fun phonePermissionDeniedDark() =
        capture("phone_meal_calendar_permission_denied_dark", MealCalendarSamples.off, darkTheme = true, denied = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneGuest() = capture("phone_meal_calendar_guest_light_sv", MealCalendarSamples.guest)

    @Test
    fun phoneDemo() = capture("phone_meal_calendar_demo_light", MealCalendarSamples.demo)

    @Test
    fun phoneConfirm() = capture("phone_meal_calendar_confirm_light", MealCalendarSamples.on, confirm = LinkConfirm.DISABLE)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_meal_calendar_on_light", MealCalendarSamples.on)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tablet() = capture("tablet_meal_calendar_on_light_sv", MealCalendarSamples.on)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitDark() = capture("tablet_portrait_meal_calendar_on_dark", MealCalendarSamples.on, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitOff() = capture("tablet_portrait_meal_calendar_off_light", MealCalendarSamples.off)

    private fun capture(
        name: String,
        state: MealCalendarState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        confirm: LinkConfirm? = null,
        denied: Boolean = false,
        scrollTo: String? = null,
    ) {
        val ui = MealCalendarSamples.ui(state, confirm = confirm, denied = denied)
        composeRule.setContent {
            MealPlanTestTheme(darkTheme = darkTheme, fontScale = fontScale) { MealCalendarScreen(state = ui, onAction = {}, onBack = {}) }
        }
        if (scrollTo != null) composeRule.onNodeWithTag(MEAL_CALENDAR_CONTENT_TAG).performScrollToNode(hasTestTag(scrollTo))
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/mealplan/$name.png")
    }
}
