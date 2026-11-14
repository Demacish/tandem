package casa.moller.tandem.feature.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.shopping.StoreReminderRepository
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.shopping.CurrentLocationResult
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import casa.moller.tandem.core.model.shopping.StoreReminderState
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.shell.PHONE
import casa.moller.tandem.feature.shell.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shell.TABLET
import casa.moller.tandem.feature.shell.accounts.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Device test 2026-11-14 (B12): everything allowed, the position "all the time". */
internal val PERMISSIONS_ALL_ALLOWED = SAMPLE_PERMISSIONS.copy(
    cameraAllowed = true,
    microphoneAllowed = true,
    calendarAllowed = true,
    location = LocationAccess.ALWAYS,
)

/** Device test 2026-11-14 (B12): only the approximate position, no microphone yet. */
internal val PERMISSIONS_APPROXIMATE = SAMPLE_PERMISSIONS.copy(microphoneAllowed = false, location = LocationAccess.APPROXIMATE)

/** Device test 2026-11-14 (B12): a phone without Google Play services (no reminder near the store). */
internal val PERMISSIONS_NO_PLAY_SERVICES = SAMPLE_PERMISSIONS.copy(location = LocationAccess.NONE, storeReminderUnavailable = true)

/**
 * Device test 2026-11-14 (B12): Mer → Behörigheter lists the microphone (handsfree), the calendar
 * (the meal plan in the phone's calendar) and the position (the reminder near the store), each
 * with its state. Screenshots scrolled to the new rows on a phone (portrait, landscape) and a
 * tablet (both orientations), dark, Swedish and font scale 2; ATF on each.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class FeaturePermissionsScreenshotTest(private val device: String) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun whileInUse() = page("light") { FixedSettingsPages().Permissions {} }

    @Test
    fun allAllowedDark() = page("all_allowed_dark", darkTheme = true) {
        FixedSettingsPages(permissions = PERMISSIONS_ALL_ALLOWED).Permissions {}
    }

    @Test
    @Config(qualifiers = "+sv")
    fun whileInUseSwedish() = page("light_sv") { FixedSettingsPages().Permissions {} }

    @Test
    fun approximateFontScale2() = page("approximate_font_scale_2_light", fontScale = 2f) {
        FixedSettingsPages(permissions = PERMISSIONS_APPROXIMATE).Permissions {}
    }

    @Test
    @Config(qualifiers = "+sv")
    fun noPlayServicesDarkSwedish() = page("no_play_services_dark_sv", darkTheme = true) {
        FixedSettingsPages(permissions = PERMISSIONS_NO_PLAY_SERVICES).Permissions {}
    }

    private fun page(name: String, darkTheme: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme, content = content)
            }
        }
        // The new rows are below the timers' rows: scroll so the screenshot shows them.
        composeRule.onNodeWithTag(PERMISSIONS_LOCATION_TAG).performScrollTo()
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/devicetest_feature_permissions_${device}_$name.png")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class FeaturePermissionsPhoneScreenshotTest : FeaturePermissionsScreenshotTest("phone")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LANDSCAPE)
class FeaturePermissionsPhoneLandscapeScreenshotTest : FeaturePermissionsScreenshotTest("phone_landscape")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class FeaturePermissionsTabletScreenshotTest : FeaturePermissionsScreenshotTest("tablet")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET_PORTRAIT)
class FeaturePermissionsTabletPortraitScreenshotTest : FeaturePermissionsScreenshotTest("tablet_portrait")

/** Device test 2026-11-14 (B12): what the new rows say and open. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class FeaturePermissionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: PermissionsUiState): FixedSettingsPages {
        val pages = FixedSettingsPages(permissions = state)
        composeRule.setContent { TandemTheme { pages.Permissions {} } }
        return pages
    }

    // The state chip is a content description (core:designsystem StatusChip), not a text.
    @Test
    fun eachNewRowSaysItsStateAndOpensTheAppsSettings() {
        val pages = show(SAMPLE_PERMISSIONS)
        composeRule.onNodeWithText("Microphone").performScrollTo()
        composeRule.onNodeWithText("Calendar").performScrollTo()
        composeRule.onNodeWithContentDescription("Only while in use").performScrollTo()
        for (title in listOf("Microphone", "Calendar", "Location")) {
            composeRule.onNodeWithContentDescription("Open Android’s settings for $title").performScrollTo().performClick()
        }
        assertEquals(List(3) { SystemSetting.APP_DETAILS }, pages.openedSystemSettings)
    }

    @Test
    fun theLevelOfThePositionIsNamed() {
        show(PERMISSIONS_APPROXIMATE)
        composeRule.onNodeWithContentDescription("Only approximate").performScrollTo()
    }

    @Test
    fun allTheTimeIsWhatTheReminderNeeds() {
        show(PERMISSIONS_ALL_ALLOWED)
        composeRule.onNodeWithContentDescription("Allowed all the time").performScrollTo()
    }

    @Test
    fun withoutPlayServicesThePositionRowExplainsAndOpensNothing() {
        val pages = show(PERMISSIONS_NO_PLAY_SERVICES)
        composeRule.onNodeWithText(
            "This phone has no Google Play services, so the reminder near the store is not available and your location is never used.",
        ).performScrollTo()
        composeRule.onNodeWithContentDescription("Open Android’s settings for Location").assertDoesNotExist()
        assertEquals(emptyList<SystemSetting>(), pages.openedSystemSettings)
    }

    @Test
    fun rowsThatWereNotReadAreNotShown() {
        show(SAMPLE_PERMISSIONS.copy(microphoneAllowed = null, calendarAllowed = null, location = null))
        composeRule.onNodeWithText("Microphone").assertDoesNotExist()
        composeRule.onNodeWithText("Calendar").assertDoesNotExist()
        composeRule.onNodeWithTag(PERMISSIONS_LOCATION_TAG).assertDoesNotExist()
    }

    @Test
    fun thePrivacyCardNoLongerSaysNoLocation() {
        show(SAMPLE_PERMISSIONS)
        composeRule.onNodeWithText("No location", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Tandem never sends it anywhere", substring = true).performScrollTo()
    }
}

/** Device test 2026-11-14 (B12): how the three location permissions map to what the reminder can do. */
class LocationAccessTest {
    @Test
    fun preciseAndBackgroundIsAllTheTime() {
        assertEquals(LocationAccess.ALWAYS, LocationAccess.of(precise = true, approximate = true, background = true))
    }

    @Test
    fun preciseWithoutBackgroundIsWhileInUse() {
        assertEquals(LocationAccess.WHILE_IN_USE, LocationAccess.of(precise = true, approximate = true, background = false))
    }

    @Test
    fun approximateIsNotEnoughEvenAllTheTime() {
        assertEquals(LocationAccess.APPROXIMATE, LocationAccess.of(precise = false, approximate = true, background = true))
    }

    @Test
    fun nothingIsNone() {
        assertEquals(LocationAccess.NONE, LocationAccess.of(precise = false, approximate = false, background = false))
    }
}

/** A [StoreReminderRepository] that only has a state (the settings read its availability). */
internal class FakeStoreReminderRepository(initial: StoreReminderState = StoreReminderState()) : StoreReminderRepository {
    override val state = MutableStateFlow(initial)

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override suspend fun useCurrentLocation(storeId: Long): CurrentLocationResult = CurrentLocationResult.NoPermission

    override suspend fun setPlaceFromText(storeId: Long, text: String): Boolean = false

    override suspend fun setStoreEnabled(storeId: Long, enabled: Boolean) = Unit

    override suspend fun setRadius(storeId: Long, meters: Int) = Unit

    override suspend fun forgetPlace(storeId: Long) = Unit

    override fun refreshPermissions() = Unit

    override fun currentPermissions(): StoreReminderPermissions = StoreReminderPermissions()
}
