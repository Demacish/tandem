package casa.moller.tandem.feature.recipes.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.SampleInbox
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
private const val TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"

/**
 * Fas 5 (rss lane, plan §7 #35): "Inkorg för recept" and its settings. A post is one TalkBack node that
 * opens its preview, the preview's "Importera" sends the import (not for guests), the settings connect
 * only with an address and a key, a category is one checkbox node, the switches and "Koppla från" send
 * their actions. Screenshots with ATF of every state on the phone (portrait and landscape), the tablet
 * in both orientations, dark, Swedish and font scale 2 (`src/test/screenshots/inbox/`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class InboxScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val list = InboxUiState(
        loading = false,
        availability = InboxAvailability.AVAILABLE,
        inUse = true,
        entries = SampleInbox.posts,
        lastRefresh = SampleInbox.refreshed,
    )

    private val connected = InboxSettingsUiState(
        loading = false,
        availability = InboxAvailability.AVAILABLE,
        settings = SampleInbox.settings,
        serverCategories = listOf(SampleInbox.recipes, SampleInbox.news, SampleInbox.desserts),
        selected = setOf(2L, 4L),
    )

    private val notConnected = InboxSettingsUiState(loading = false, availability = InboxAvailability.AVAILABLE)

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { TestFrame { content() } }
    }

    // --- behaviour ---------------------------------------------------------------------------------

    @Test
    fun aPostIsOneNodeThatOpensItsPreview() {
        val actions = mutableListOf<InboxAction>()
        show { InboxScreen(state = list, onAction = { actions += it }, onBack = {}, onOpenSettings = {}) }
        composeRule.onNodeWithTag(inboxEntryTag(11)).assertIsDisplayed().performClick()
        assertEquals(InboxAction.Open(11), actions.single())
        composeRule.onNodeWithText("Imported").assertIsDisplayed()
    }

    @Test
    fun thePreviewImportsMarksReadAndOpensTheBrowser() {
        val actions = mutableListOf<InboxAction>()
        show { InboxPreviewContent(entry = SampleInbox.posts.first(), readOnly = false, onAction = { actions += it }) }
        composeRule.onNodeWithTag(INBOX_IMPORT_TAG).performClick()
        composeRule.onNodeWithText("Mark as read").performClick()
        composeRule.onNodeWithText("Open in browser").performClick()
        assertEquals(listOf(InboxAction.Import(11), InboxAction.Dismiss(11), InboxAction.OpenInBrowser(11)), actions)
    }

    @Test
    fun aGuestsPreviewHasNoImport() {
        show { InboxPreviewContent(entry = SampleInbox.posts.first(), readOnly = true, onAction = {}) }
        assertEquals(0, composeRule.onAllNodes(hasTestTag(INBOX_IMPORT_TAG)).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Guests cannot import recipes.").assertIsDisplayed()
    }

    @Test
    fun aNotSetUpInboxLeadsToItsSettings() {
        var opened = false
        show {
            InboxScreen(
                state = InboxUiState(loading = false, availability = InboxAvailability.AVAILABLE),
                onAction = {},
                onBack = {},
                onOpenSettings = { opened = true },
            )
        }
        composeRule.onNodeWithText("Set up").performClick()
        assertTrue(opened)
    }

    @Test
    fun connectNeedsAnAddressAndAKey() {
        val actions = mutableListOf<InboxSettingsAction>()
        show { InboxSettingsScreen(state = notConnected, onAction = { actions += it }, onBack = {}) }
        composeRule.onNodeWithTag(INBOX_SETTINGS_CONNECT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText("Miniflux address").performTextInput("rss.example.com")
        assertEquals(InboxSettingsAction.SetAddress("rss.example.com"), actions.single())
    }

    @Test
    fun aCategoryIsOneCheckboxNodeAndTheSwitchesSendTheirActions() {
        val actions = mutableListOf<InboxSettingsAction>()
        show { InboxSettingsScreen(state = connected, onAction = { actions += it }, onBack = {}) }
        composeRule.onNodeWithTag(INBOX_SETTINGS_CONTENT_TAG).performScrollToNode(hasTestTag(inboxCategoryTag(3)))
        composeRule.onNodeWithTag(inboxCategoryTag(2)).assertIsOn()
        composeRule.onNodeWithTag(inboxCategoryTag(3)).assertIsOff().performClick()
        composeRule.onNodeWithText("Mark as read after import").performScrollTo().performClick()
        composeRule.onNodeWithText("Show on Today").performScrollTo().performClick()
        composeRule.onNodeWithText("Disconnect").performScrollTo().performClick()
        assertEquals(
            listOf(
                InboxSettingsAction.ToggleCategory(3, chosen = true),
                InboxSettingsAction.SetMarkRead(false),
                InboxSettingsAction.SetShowOnToday(false),
                InboxSettingsAction.AskDisconnect,
            ),
            actions,
        )
    }

    @Test
    fun demoModeHasNoSettings() {
        val demo = InboxSettingsUiState(loading = false, availability = InboxAvailability.DEMO)
        show { InboxSettingsScreen(state = demo, onAction = {}, onBack = {}) }
        composeRule.onNodeWithText("Not in demo mode").assertExists()
        assertEquals(0, composeRule.onAllNodes(hasText("Connect")).fetchSemanticsNodes().size)
    }

    // --- screenshots and ATF: the inbox --------------------------------------------------------

    @Test
    fun phoneList() = capture("phone_inbox_list_light") { InboxScreen(list, {}, {}, {}) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneListSwedish() = capture("phone_inbox_list_light_sv") { InboxScreen(list, {}, {}, {}) }

    @Test
    fun phoneListDark() = capture("phone_inbox_list_dark", darkTheme = true) { InboxScreen(list, {}, {}, {}) }

    @Test
    fun phoneListFontScale2() = capture("phone_inbox_list_font2_light", fontScale = 2f) { InboxScreen(list, {}, {}, {}) }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_inbox_list_light") { InboxScreen(list, {}, {}, {}) }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tablet() = capture("tablet_inbox_list_light_sv") { InboxScreen(list, {}, {}, {}) }

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitDark() = capture("tablet_portrait_inbox_list_dark", darkTheme = true) { InboxScreen(list, {}, {}, {}) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneOffline() = capture("phone_inbox_offline_light_sv") {
        InboxScreen(list.copy(problem = InboxProblem.UNREACHABLE), {}, {}, {})
    }

    @Test
    fun phoneRefusedKeyDark() = capture("phone_inbox_auth_dark", darkTheme = true) {
        InboxScreen(list.copy(problem = InboxProblem.AUTH), {}, {}, {})
    }

    @Test
    fun phoneGuest() = capture("phone_inbox_guest_light") { InboxScreen(list.copy(readOnly = true), {}, {}, {}) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneDemo() = capture("phone_inbox_demo_light_sv") {
        InboxScreen(list.copy(availability = InboxAvailability.DEMO, lastRefresh = null), {}, {}, {})
    }

    @Test
    fun phoneEmpty() = capture("phone_inbox_empty_light") { InboxScreen(list.copy(entries = emptyList()), {}, {}, {}) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneNotSetUp() = capture("phone_inbox_not_set_up_light_sv") {
        InboxScreen(InboxUiState(loading = false, availability = InboxAvailability.AVAILABLE), {}, {}, {})
    }

    @Test
    fun phonePreview() = capture("phone_inbox_preview_light") {
        InboxPreviewContent(SampleInbox.posts.first(), readOnly = false, onAction = {})
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phonePreviewFontScale2() = capture("phone_inbox_preview_font2_light_sv", fontScale = 2f) {
        InboxPreviewContent(SampleInbox.posts.first(), readOnly = false, onAction = {})
    }

    @Test
    fun phonePreviewImportedGuestDark() = capture("phone_inbox_preview_guest_dark", darkTheme = true) {
        InboxPreviewContent(SampleInbox.posts.last(), readOnly = true, onAction = {})
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletPreview() = capture("tablet_inbox_preview_light") {
        InboxPreviewContent(SampleInbox.posts.first(), readOnly = false, onAction = {})
    }

    // --- screenshots and ATF: the settings ----------------------------------------------------

    @Test
    fun phoneSettingsNotConnected() = capture("phone_inbox_settings_connect_light") { InboxSettingsScreen(notConnected, {}, {}) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSettingsFailedSwedish() = capture("phone_inbox_settings_connect_failed_light_sv") {
        InboxSettingsScreen(
            notConnected.copy(address = "https://rss.example.com", apiKey = "x", connectProblem = InboxProblem.AUTH),
            {},
            {},
        )
    }

    @Test
    fun phoneSettingsNotConnectedFontScale2() = capture("phone_inbox_settings_connect_font2_dark", darkTheme = true, fontScale = 2f) {
        InboxSettingsScreen(notConnected, {}, {})
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSettingsConnected() = capture("phone_inbox_settings_connected_light_sv") { InboxSettingsScreen(connected, {}, {}) }

    @Test
    fun phoneSettingsConnectedDark() = capture("phone_inbox_settings_connected_dark", darkTheme = true) {
        InboxSettingsScreen(connected, {}, {})
    }

    @Test
    fun phoneSettingsConnectedFontScale2() = capture("phone_inbox_settings_connected_font2_light", fontScale = 2f) {
        InboxSettingsScreen(connected, {}, {})
    }

    @Test
    fun phoneSettingsCategoriesFailed() = capture("phone_inbox_settings_categories_failed_light", scrollTo = INBOX_SETTINGS_SAVE_TAG) {
        InboxSettingsScreen(
            connected.copy(
                serverCategories = null,
                categoriesProblem = InboxProblem.UNREACHABLE,
                settings = SampleInbox.settings.copy(categories = emptyList()),
                selected = emptySet(),
            ),
            {},
            {},
        )
    }

    @Test
    fun phoneSettingsDisconnect() = capture("phone_inbox_settings_disconnect_light") {
        InboxSettingsScreen(connected.copy(confirmDisconnect = true), {}, {})
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSettingsDemo() = capture("phone_inbox_settings_demo_light_sv") {
        InboxSettingsScreen(InboxSettingsUiState(loading = false, availability = InboxAvailability.DEMO), {}, {})
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscapeSettings() = capture("phone_landscape_inbox_settings_connected_light") { InboxSettingsScreen(connected, {}, {}) }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletSettings() = capture("tablet_inbox_settings_connected_light_sv") { InboxSettingsScreen(connected, {}, {}) }

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitSettings() = capture("tablet_portrait_inbox_settings_connect_dark", darkTheme = true) {
        InboxSettingsScreen(notConnected, {}, {})
    }

    private fun capture(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        scrollTo: String? = null,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            TestFrame(darkTheme = darkTheme, fontScale = fontScale) {
                // The preview is drawn on the sheet's surface in the app.
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        if (scrollTo != null) composeRule.onNodeWithTag(INBOX_SETTINGS_CONTENT_TAG).performScrollToNode(hasTestTag(scrollTo))
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/inbox/$name.png")
    }
}
