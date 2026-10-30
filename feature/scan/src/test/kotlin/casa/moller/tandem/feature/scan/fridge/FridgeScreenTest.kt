package casa.moller.tandem.feature.scan.fridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.feature.scan.Devices.PHONE_TALL
import casa.moller.tandem.feature.scan.ScanTestTheme
import casa.moller.tandem.feature.scan.aisettings.AI_CHECK_KEY_TAG
import casa.moller.tandem.feature.scan.aisettings.AI_KEY_FIELD_TAG
import casa.moller.tandem.feature.scan.aisettings.AI_REMOVE_KEY_TAG
import casa.moller.tandem.feature.scan.aisettings.AI_SAVE_KEY_TAG
import casa.moller.tandem.feature.scan.aisettings.AiSettingsAction
import casa.moller.tandem.feature.scan.aisettings.AiSettingsScreen
import casa.moller.tandem.feature.scan.aisettings.AiSettingsUiState
import casa.moller.tandem.feature.scan.barcode.ScanCameraAction
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The fridge photo's and Settings → AI's wiring: the warning, the way to the key, the buttons and the password field. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE_TALL")
class FridgeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<FridgeScanAction>()
    private val camera = mutableListOf<ScanCameraAction>()
    private var photos = 0
    private var settingsOpened = 0
    private var backs = 0

    private fun fridge(state: FridgeScanUiState, access: CameraAccess = CameraAccess.GRANTED) {
        composeRule.setContent {
            ScanTestTheme {
                FridgeScanScreen(
                    state = state,
                    camera = ScanCameraUi(access),
                    onAction = { actions += it },
                    onCamera = { camera += it },
                    onTakePhoto = { photos++ },
                    onOpenSettings = { settingsOpened++ },
                    onBack = { backs++ },
                )
            }
        }
    }

    @Test
    fun theWarningMustBeAcknowledged() {
        fridge(FridgeShots.ready.copy(warningAcknowledged = false))
        composeRule.onNodeWithText("Foto av kylen är experimentellt").assertExists()
        composeRule.onNodeWithTag(FRIDGE_SHUTTER_TAG, useUnmergedTree = true).assertIsNotEnabled()
        composeRule.onNodeWithTag(FRIDGE_WARNING_ACCEPT_TAG).performClick()
        assertEquals(listOf<FridgeScanAction>(FridgeScanAction.AcknowledgeWarning), actions)
    }

    @Test
    fun declineTheWarningLeaves() {
        fridge(FridgeShots.ready.copy(warningAcknowledged = false))
        composeRule.onNodeWithText("Inte nu").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun readyTakesAPhotoOrPicksOne() {
        fridge(FridgeShots.ready)
        composeRule.onNodeWithContentDescription("Experimentell").assertExists()
        composeRule.onNodeWithTag(FRIDGE_SHUTTER_TAG).assertIsEnabled().performClick()
        composeRule.onNodeWithText("Välj en bild från galleriet").performClick()
        assertEquals(1, photos)
        assertEquals(listOf(ScanCameraAction.PICK_IMAGE), camera)
    }

    @Test
    fun withoutAKeyThePageLeadsToSettings() {
        fridge(FridgeShots.ready.copy(available = false, provider = null))
        composeRule.onNodeWithTag(FRIDGE_SHUTTER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(FRIDGE_SETTINGS_TAG).performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun demoModeHasNoCameraAndNoWarning() {
        fridge(FridgeShots.ready.copy(demo = true, warningAcknowledged = false))
        composeRule.onNodeWithTag(FRIDGE_SHUTTER_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Foto av kylen är experimentellt").assertDoesNotExist()
        composeRule.onNodeWithText("Demoläge").assertExists()
    }

    @Test
    fun aProblemCanBeDismissed() {
        fridge(FridgeShots.ready.copy(problem = FridgeProblem.REFUSAL))
        composeRule.onNodeWithText("AI:n ville inte tolka bilden").assertExists()
    }

    @Test
    fun theKeyFieldIsAPasswordFieldAndSavesOnce() {
        val sent = mutableListOf<AiSettingsAction>()
        composeRule.setContent {
            var key by remember { mutableStateOf("") }
            ScanTestTheme {
                AiSettingsScreen(
                    state = AiSettingsUiState(loading = false, provider = AiProvider.CLAUDE),
                    key = key,
                    onKeyChange = { key = it },
                    onPaste = {},
                    onAction = { sent += it },
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag(AI_SAVE_KEY_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(AI_CHECK_KEY_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(AI_REMOVE_KEY_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(AI_KEY_FIELD_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag(AI_KEY_FIELD_TAG).performTextInput(KEY)
        composeRule.onNodeWithTag(AI_SAVE_KEY_TAG).assertIsEnabled().performClick()
        val save = sent.filterIsInstance<AiSettingsAction.SaveKey>().single()
        assertEquals(KEY, save.key)
    }

    @Test
    fun aStoredKeyCanBeTestedAndRemovedAndTheProviderChanged() {
        val sent = mutableListOf<AiSettingsAction>()
        composeRule.setContent {
            ScanTestTheme {
                AiSettingsScreen(
                    state = FridgeShots.ai,
                    key = "",
                    onKeyChange = {},
                    onPaste = {},
                    onAction = { sent += it },
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag(AI_CHECK_KEY_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(AI_REMOVE_KEY_TAG).performScrollTo().performClick()
        composeRule.onNodeWithText("Gemini").performScrollTo().performClick()
        assertTrue(AiSettingsAction.CheckKey in sent)
        assertTrue(AiSettingsAction.RemoveKey in sent)
        assertTrue(AiSettingsAction.SelectProvider(AiProvider.GEMINI) in sent)
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
    }
}
