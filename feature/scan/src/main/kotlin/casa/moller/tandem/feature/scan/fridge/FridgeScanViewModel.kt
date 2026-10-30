package casa.moller.tandem.feature.scan.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.data.ai.FridgeVision
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.feature.scan.ScanMatcher
import casa.moller.tandem.feature.scan.navigation.FridgeScanKey
import casa.moller.tandem.feature.scan.session.ScanSessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the fridge photo is on its way to the review. */
enum class FridgePhase {
    IDLE,

    /** The photo is taken, scaled and stripped of metadata on the phone. */
    PREPARING,

    /** The photo is with the chosen AI service. */
    ASKING,

    /** The answer is matched to the space's foods. */
    MATCHING,
}

/** Why the fridge photo gave no review (each with its own explanation; no automatic retry, a call costs credits). */
enum class FridgeProblem {
    INVALID_KEY,
    MODEL_UNAVAILABLE,
    RATE_LIMITED,
    REFUSAL,
    NETWORK,
    UNPARSEABLE,
    NOTHING_FOUND,
    UNREADABLE,
}

/**
 * The fridge photo (#45, EXPERIMENTAL, B22).
 *
 * @property available a service and its key are set up (otherwise the page points to Settings → AI).
 * @property showWarning the experimental warning must be acknowledged before the first photo.
 * @property demo demo mode: the feature is off (no service is ever called).
 * @property readOnly a guest: the photo can be checked, but the review cannot save.
 * @property customHost the host of the user's own base URL, where the photo and key go instead of
 *   the provider's official API (the privacy text names it).
 */
data class FridgeScanUiState(
    val loading: Boolean = true,
    val provider: AiProvider? = null,
    val customHost: String? = null,
    val available: Boolean = false,
    val warningAcknowledged: Boolean = false,
    val demo: Boolean = false,
    val readOnly: Boolean = false,
    val phase: FridgePhase = FridgePhase.IDLE,
    val problem: FridgeProblem? = null,
) {
    val busy: Boolean
        get() = phase != FridgePhase.IDLE

    /**
     * The camera stays bound: also while a photo is taken and prepared (Fas S review, fix-ui:
     * unbinding it when [busy] aborted every capture with ERROR_CAMERA_CLOSED). Only the buttons
     * follow [canScan].
     */
    val cameraWanted: Boolean
        get() = !loading && available && !demo && warningAcknowledged

    /** A photo can be taken now. */
    val canScan: Boolean
        get() = cameraWanted && !busy

    val showWarning: Boolean
        get() = !loading && available && !demo && !warningAcknowledged
}

sealed interface FridgeScanAction {
    /** The photo is being taken or a picked one read. */
    data object Preparing : FridgeScanAction

    /**
     * Scale the taken or picked photo with [prepare] (off the main thread). It runs in the
     * ViewModel, so a rotation, fold or resize meanwhile neither loses it nor leaves PREPARING stuck.
     */
    class Prepare(val prepare: suspend () -> ByteArray) : FridgeScanAction {
        override fun toString(): String = "Prepare"
    }

    /** The page (re)appeared: a capture lost with the old camera is no longer awaited. */
    data object Shown : FridgeScanAction

    /** The scaled photo without metadata; it is sent once and not kept. */
    class PhotoReady(val jpeg: ByteArray) : FridgeScanAction {
        override fun toString(): String = "PhotoReady(${jpeg.size} bytes)"
    }

    data object PhotoFailed : FridgeScanAction

    /** "Jag förstår" in the experimental warning. */
    data object AcknowledgeWarning : FridgeScanAction

    data object DismissProblem : FridgeScanAction
}

sealed interface FridgeScanEffect {
    data class OpenReview(val sessionId: String) : FridgeScanEffect

    /** The review saved the photo's rows: the page closes. */
    data object Finished : FridgeScanEffect
}

/**
 * The fridge photo (#45, plan §8.8, B22, EXPERIMENTAL): the photo goes to the user's chosen AI
 * service with the user's own key (core:data [FridgeVision]); the items become rows in the shared
 * review list ([ScanSessionStore], source FRIDGE) where every row is a suggestion and nothing is
 * saved before the user confirms. The photo itself lives only in the action that carries it and
 * the one call; it is never stored, logged or kept in the state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = FridgeScanViewModel.Factory::class)
class FridgeScanViewModel @AssistedInject internal constructor(
    @Assisted private val key: FridgeScanKey,
    private val store: ScanSessionStore,
    private val matcher: ScanMatcher,
    private val vision: FridgeVision,
    private val settings: AiSettingsRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: FridgeScanKey): FridgeScanViewModel
    }

    private data class Local(val phase: FridgePhase = FridgePhase.IDLE, val problem: FridgeProblem? = null)

    private val effectChannel = Channel<FridgeScanEffect>(Channel.BUFFERED)
    val effects: Flow<FridgeScanEffect> = effectChannel.receiveAsFlow()

    private val local = MutableStateFlow(Local())
    private val sessionId = MutableStateFlow<String?>(null)
    private var prepareJob: Job? = null

    val uiState: StateFlow<FridgeScanUiState> = combine(settings.settings, permissions.permissions, local) { ai, rights, ui ->
        FridgeScanUiState(
            loading = false,
            provider = ai.provider,
            customHost = customHostOf(ai.customBaseUrl),
            available = ai.fridgePhotoAvailable,
            warningAcknowledged = ai.warningAcknowledged,
            demo = rights.isDemo,
            readOnly = rights.readOnly,
            phase = ui.phase,
            problem = ui.problem,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FridgeScanUiState())

    init {
        // The review saved the rows: close the page too.
        viewModelScope.launch {
            sessionId.flatMapLatest { id -> id?.let { store.observe(it) } ?: emptyFlow() }.collect { session ->
                if (session?.saved != null) {
                    store.remove(session.id)
                    sessionId.value = null
                    effectChannel.send(FridgeScanEffect.Finished)
                }
            }
        }
    }

    fun onAction(action: FridgeScanAction) {
        when (action) {
            FridgeScanAction.Preparing -> if (uiState.value.canScan) local.value = Local(phase = FridgePhase.PREPARING)
            is FridgeScanAction.Prepare -> prepare(action.prepare)
            FridgeScanAction.Shown -> if (prepareJob?.isActive != true && local.value.phase == FridgePhase.PREPARING) local.value = Local()
            is FridgeScanAction.PhotoReady -> recognize(action.jpeg)
            FridgeScanAction.PhotoFailed -> local.value = Local(problem = FridgeProblem.UNREADABLE)
            FridgeScanAction.AcknowledgeWarning -> viewModelScope.launch { settings.acknowledgeWarning() }
            FridgeScanAction.DismissProblem -> local.update { it.copy(problem = null) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A broken, huge or unsupported image fails with any Exception (or OOM).
    private fun prepare(block: suspend () -> ByteArray) {
        val state = uiState.value
        // After "Preparing" (the shutter was pressed) or straight from the photo picker.
        if (prepareJob?.isActive == true || !(state.canScan || state.phase == FridgePhase.PREPARING)) return
        local.value = Local(phase = FridgePhase.PREPARING)
        prepareJob = viewModelScope.launch {
            val jpeg = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (jpeg == null) onAction(FridgeScanAction.PhotoFailed) else recognize(jpeg)
        }
    }

    private fun recognize(jpeg: ByteArray) {
        val state = uiState.value
        // One call at a time, and never before the warning was acknowledged (or in demo mode).
        if (state.phase == FridgePhase.ASKING || state.phase == FridgePhase.MATCHING) return
        if (!state.available || state.demo || !state.warningAcknowledged) {
            local.value = Local()
            return
        }
        local.value = Local(phase = FridgePhase.ASKING)
        viewModelScope.launch {
            when (val result = vision.recognize(jpeg, Locale.getDefault().toLanguageTag())) {
                is FridgeVisionResult.Failed -> local.value = Local(problem = result.error.problem())

                is FridgeVisionResult.Items -> if (result.items.isEmpty()) {
                    local.value = Local(problem = FridgeProblem.NOTHING_FOUND)
                } else {
                    local.value = Local(phase = FridgePhase.MATCHING)
                    val rows = matcher.fridgeRows(result.items, matcher.foods())
                    sessionId.value?.let(store::remove)
                    val id = store.create(ScanSource.FRIDGE, key.locationId, rows)
                    if (result.truncated) store.update(id) { it.copy(truncated = true) }
                    sessionId.value = id
                    local.value = Local()
                    effectChannel.send(FridgeScanEffect.OpenReview(id))
                }
            }
        }
    }

    override fun onCleared() {
        sessionId.value?.let(store::remove)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The host of an own base URL ("proxy.exempel.se"), null for the provider's official API or an unreadable one. */
internal fun customHostOf(baseUrl: String?): String? = baseUrl?.takeIf { it.isNotBlank() }?.let { url ->
    runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** The page never sends in demo mode (core:ai refuses too), so DemoMode is only a fallback here. */
private fun FridgeVisionError.problem(): FridgeProblem = when (this) {
    FridgeVisionError.InvalidKey, FridgeVisionError.NoKey -> FridgeProblem.INVALID_KEY
    FridgeVisionError.ModelUnavailable -> FridgeProblem.MODEL_UNAVAILABLE
    is FridgeVisionError.QuotaOrRateLimit -> FridgeProblem.RATE_LIMITED
    FridgeVisionError.Refusal -> FridgeProblem.REFUSAL
    is FridgeVisionError.Network -> FridgeProblem.NETWORK
    is FridgeVisionError.Unparseable, FridgeVisionError.DemoMode -> FridgeProblem.UNPARSEABLE
}
