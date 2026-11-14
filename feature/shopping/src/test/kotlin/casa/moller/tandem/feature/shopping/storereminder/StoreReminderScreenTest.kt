package casa.moller.tandem.feature.shopping.storereminder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.StoreReminderState
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.shopping.Devices.PHONE
import casa.moller.tandem.feature.shopping.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shopping.Devices.TABLET
import casa.moller.tandem.feature.shopping.Devices.TABLET_PORTRAIT
import casa.moller.tandem.feature.shopping.ShoppingTestTheme
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas 5 (butik lane, plan §7 #34): "Påminnelse nära butiken". The switch is one TalkBack item with
 * the role "switch", a store is one button, the cards lead to the system settings, demo mode and a
 * phone without Play services get an explanation; the store sheet with and without a place.
 * Screenshots with ATF on each: phone (portrait and landscape), tablet in both orientations, dark,
 * Swedish and font scale 2.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class StoreReminderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: StoreReminderUiState, actions: MutableList<StoreReminderAction> = mutableListOf()) {
        composeRule.setContent {
            ShoppingTestTheme { StoreReminderScreen(state = state, onAction = { actions += it }, onBack = {}) }
        }
    }

    @Test
    fun theSwitchIsOneNodeAndAStoreOpensItsSheet() {
        val actions = mutableListOf<StoreReminderAction>()
        show(StoreReminderSamples.ui(StoreReminderSamples.off), actions)
        composeRule.onNodeWithTag(STORE_REMINDER_SWITCH_TAG).assertIsOff().performClick()
        assertEquals(StoreReminderAction.SetEnabled(true), actions.single())
        composeRule.onNodeWithTag(STORE_REMINDER_CONTENT_TAG).performScrollToNode(hasTestTag(STORE_REMINDER_STORE_TAG + 3))
        composeRule.onNodeWithTag(STORE_REMINDER_STORE_TAG + 3).performClick()
        assertEquals(StoreReminderAction.OpenStore(3), actions.last())
        composeRule.onNodeWithText("No place set").assertExists()
    }

    @Test
    fun onItSaysHowManyStoresAreWatched() {
        show(StoreReminderSamples.ui(StoreReminderSamples.on))
        composeRule.onNodeWithTag(STORE_REMINDER_SWITCH_TAG).assertIsOn()
        composeRule.onNodeWithText("Watching 1 store").assertExists()
        composeRule.onNodeWithText("Reminds within 200 m").assertExists()
        composeRule.onNodeWithText("Off · place saved").assertExists()
    }

    @Test
    fun aRefusedBackgroundLocationLeadsToTheSettings() {
        val actions = mutableListOf<StoreReminderAction>()
        show(StoreReminderSamples.ui(StoreReminderSamples.off, denied = PermissionStep.BACKGROUND), actions)
        composeRule.onNodeWithText("Choose “Allow all the time”").assertExists()
        val allow = composeRule.onAllNodes(hasText("Allow")).fetchSemanticsNodes()
        assertEquals("No dialog to ask again: Android only offers the settings", 0, allow.size)
        composeRule.onNodeWithText("Open settings").performClick()
        assertEquals(StoreReminderAction.OpenAppSettings, actions.single())
    }

    @Test
    fun locationOffLeadsToTheLocationSettings() {
        val actions = mutableListOf<StoreReminderAction>()
        show(StoreReminderSamples.ui(StoreReminderSamples.locationOff), actions)
        composeRule.onNodeWithText("Location settings").performClick()
        assertEquals(StoreReminderAction.OpenLocationSettings, actions.single())
    }

    @Test
    fun demoModeAndNoPlayServicesHaveNoOptions() {
        show(StoreReminderSamples.ui(StoreReminderSamples.noPlayServices))
        composeRule.onNodeWithText("Needs Google Play services").assertExists()
        assertEquals(0, composeRule.onAllNodes(hasTestTag(STORE_REMINDER_SWITCH_TAG)).fetchSemanticsNodes().size)
    }

    @Test
    fun theSheetsButtonsSendTheirActions() {
        val actions = mutableListOf<StoreReminderAction>()
        composeRule.setContent {
            ShoppingTestTheme {
                SheetShot {
                    StoreReminderSheetLayout(sheet = StoreReminderSamples.sheetWithPlace, canOpenMaps = true, onAction = { actions += it })
                }
            }
        }
        composeRule.onNodeWithTag(STORE_REMINDER_SHEET_SWITCH_TAG).assertIsOn().performClick()
        composeRule.onNodeWithTag(STORE_REMINDER_USE_LOCATION_TAG).performScrollTo().performClick()
        composeRule.onNodeWithText("Show on map").performScrollTo().performClick()
        composeRule.onNodeWithText("Remove place").performScrollTo().performClick()
        assertEquals(
            listOf(
                StoreReminderAction.SetStoreEnabled(false),
                StoreReminderAction.UseCurrentLocation,
                StoreReminderAction.ShowOnMap,
                StoreReminderAction.ForgetPlace,
            ),
            actions,
        )
        composeRule.onNodeWithText("Distance: 200 m").assertExists()
    }

    @Test
    fun theMapLinkCarriesThePlaceAndTheName() {
        val uri = geoUri(GeoPoint(12.3456, -45.6789), "Testbutiken")
        assertEquals("geo:12.345600,-45.678900?q=12.345600,-45.678900(Testbutiken)", uri)
    }

    // --- screenshots and ATF ---------------------------------------------------------------------

    @Test
    fun phoneOff() = capture("phone_store_reminder_off_light", StoreReminderSamples.off)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneOnSwedish() = capture("phone_store_reminder_on_light_sv", StoreReminderSamples.on)

    @Test
    fun phoneOnDark() = capture("phone_store_reminder_on_dark", StoreReminderSamples.on, darkTheme = true)

    @Test
    fun phoneFontScale2() = capture("phone_store_reminder_on_font2_light", StoreReminderSamples.on, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneDeniedBackground() =
        capture("phone_store_reminder_denied_light_sv", StoreReminderSamples.off, denied = PermissionStep.BACKGROUND)

    @Test
    fun phoneLocationOffDark() = capture("phone_store_reminder_location_off_dark", StoreReminderSamples.locationOff, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneLost() = capture("phone_store_reminder_lost_light_sv", StoreReminderSamples.lost)

    @Test
    fun phoneNoStores() = capture("phone_store_reminder_no_stores_light", StoreReminderSamples.off, stores = emptyList())

    @Test
    fun phoneDemo() = capture("phone_store_reminder_demo_light", StoreReminderSamples.demo)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneNoPlayServices() = capture("phone_store_reminder_no_play_light_sv", StoreReminderSamples.noPlayServices)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_store_reminder_on_light", StoreReminderSamples.on)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tablet() = capture("tablet_store_reminder_on_light_sv", StoreReminderSamples.on)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitDark() = capture("tablet_portrait_store_reminder_on_dark", StoreReminderSamples.on, darkTheme = true)

    @Test
    fun phoneSheetWithPlace() = captureSheet("phone_store_reminder_sheet_light", StoreReminderSamples.sheetWithPlace)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSheetEmptySwedish() = captureSheet("phone_store_reminder_sheet_empty_light_sv", StoreReminderSamples.sheetEmpty)

    @Test
    fun phoneSheetDark() = captureSheet("phone_store_reminder_sheet_dark", StoreReminderSamples.sheetWithPlace, darkTheme = true)

    @Test
    fun phoneSheetFontScale2() =
        captureSheet("phone_store_reminder_sheet_font2_light", StoreReminderSamples.sheetWithPlace, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscapeSheet() = captureSheet("phone_landscape_store_reminder_sheet_light", StoreReminderSamples.sheetEmpty)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitSheet() = captureSheet("tablet_portrait_store_reminder_sheet_light", StoreReminderSamples.sheetWithPlace)

    private fun capture(
        name: String,
        state: StoreReminderState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        denied: PermissionStep? = null,
        stores: List<StoreReminderStoreRow> = StoreReminderSamples.stores,
    ) {
        val ui = StoreReminderSamples.ui(state, denied = denied, stores = stores)
        composeRule.setContent {
            ShoppingTestTheme(darkTheme = darkTheme, fontScale = fontScale) { StoreReminderScreen(state = ui, onAction = {}, onBack = {}) }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shopping/$name.png")
    }

    private fun captureSheet(name: String, sheet: StoreReminderSheetState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            ShoppingTestTheme(darkTheme = darkTheme, fontScale = fontScale) {
                SheetShot { StoreReminderSheetLayout(sheet = sheet, canOpenMaps = true, onAction = {}) }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shopping/$name.png")
    }
}

/** The sheet's content as it is laid out in the sheet (the sheet window itself is the platform's), filling the window. */
@Composable
private fun SheetShot(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { content() }
    }
}
