package casa.moller.tandem.feature.scan.fridge

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.feature.scan.ScanSamples
import casa.moller.tandem.feature.scan.ScanShots
import casa.moller.tandem.feature.scan.aisettings.AiKeyError
import casa.moller.tandem.feature.scan.aisettings.AiSettingsScreen
import casa.moller.tandem.feature.scan.aisettings.AiSettingsUiState
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.review.ScanReviewScreen

/**
 * The fridge photo and Settings → AI in every state (ai-foto lane), with SYNTHETIC data and a fake
 * key that is never shown (the key field shows dots), for the screenshot and accessibility tests.
 */
internal enum class FridgeShot(val dialog: Boolean = false, val content: @Composable () -> Unit) {
    FRIDGE_READY(content = { Fridge(FridgeShots.ready) }),
    FRIDGE_WARNING(dialog = true, content = { Fridge(FridgeShots.ready.copy(warningAcknowledged = false)) }),
    FRIDGE_ASKING(content = { Fridge(FridgeShots.ready.copy(phase = FridgePhase.ASKING)) }),
    FRIDGE_REFUSAL(content = { Fridge(FridgeShots.ready.copy(problem = FridgeProblem.REFUSAL)) }),
    FRIDGE_RATE_LIMITED(content = { Fridge(FridgeShots.ready.copy(problem = FridgeProblem.RATE_LIMITED)) }),
    FRIDGE_NO_KEY(content = { Fridge(FridgeShots.ready.copy(available = false, provider = null)) }),
    FRIDGE_DEMO(content = { Fridge(FridgeShots.ready.copy(demo = true)) }),
    FRIDGE_GUEST(content = { Fridge(FridgeShots.ready.copy(readOnly = true)) }),
    FRIDGE_CAMERA_DENIED(content = { Fridge(FridgeShots.ready, CameraAccess.DENIED) }),
    REVIEW_FRIDGE_TRUNCATED(content = {
        val state = ScanShots.review(ScanSource.FRIDGE, ScanSamples.fridgeRows()).copy(truncated = true)
        ScanReviewScreen(state = state, onAction = {}, onBack = {})
    }),
    AI_OFF(content = { Ai(AiSettingsUiState(loading = false)) }),
    AI_NEW_KEY(content = { Ai(FridgeShots.ai.copy(hasKey = false), key = "sk-test-not-a-real-key") }),
    AI_KEY_WORKS(content = { Ai(FridgeShots.ai.copy(check = AiKeyCheck.WORKS)) }),
    AI_ERRORS(content = {
        Ai(
            FridgeShots.ai.copy(
                provider = AiProvider.OPENAI,
                keyError = AiKeyError.INVALID,
                check = AiKeyCheck.NO_MODEL_ACCESS,
                model = "gpt-custom",
                baseUrl = "http://proxy.example.com",
                baseUrlError = true,
                advancedChanged = true,
            ),
            key = "sk test",
        )
    }),
    AI_CHECKING(content = { Ai(FridgeShots.ai.copy(provider = AiProvider.GEMINI, checking = true)) }),
    AI_DEMO(content = { Ai(FridgeShots.ai.copy(demo = true)) }),
    AI_REMOVE_DIALOG(dialog = true, content = { Ai(FridgeShots.ai.copy(confirmRemove = true)) }),
}

@Composable
private fun Fridge(state: FridgeScanUiState, access: CameraAccess = CameraAccess.GRANTED) = FridgeScanScreen(
    state = state,
    camera = ScanCameraUi(access),
    onAction = {},
    onCamera = {},
    onTakePhoto = {},
    onOpenSettings = {},
    onBack = {},
)

@Composable
private fun Ai(state: AiSettingsUiState, key: String = "") =
    AiSettingsScreen(state = state, key = key, onKeyChange = {}, onPaste = {}, onAction = {}, onBack = {})

internal object FridgeShots {
    val ready = FridgeScanUiState(loading = false, provider = AiProvider.CLAUDE, available = true, warningAcknowledged = true)

    val ai = AiSettingsUiState(loading = false, provider = AiProvider.CLAUDE, hasKey = true, warningAcknowledged = true)
}
