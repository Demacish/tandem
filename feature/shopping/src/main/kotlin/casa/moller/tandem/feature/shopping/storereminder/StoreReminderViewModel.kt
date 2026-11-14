package casa.moller.tandem.feature.shopping.storereminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.shopping.StoreReminderRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.domain.storereminder.StoreReminderRadius
import casa.moller.tandem.core.model.shopping.CurrentLocationResult
import casa.moller.tandem.core.model.shopping.StoreReminderAvailability
import casa.moller.tandem.core.model.shopping.StoreReminderState
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.feature.shopping.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Påminnelse nära butiken" (Fas 5, butik lane, plan §7 #34), from Mer → Inställningar and from
 * Handla's store chooser. Every permission is asked in context and in order (plan §8.7):
 *
 * - setting a store's place from the position asks for the precise location only;
 * - turning the reminder on asks for the precise location, then explains "Tillåt alltid" before
 *   Android opens its settings page, then asks for notifications; only with all three is it turned
 *   on. A "no" leaves it off and shows a card that leads to the system settings.
 *
 * Opening a map app and the system settings are the route's (platform) business.
 */
@HiltViewModel
class StoreReminderViewModel @Inject constructor(
    private val reminders: StoreReminderRepository,
    supermarkets: SupermarketRepository,
) : ViewModel() {
    private val effects = Channel<StoreReminderEffect>(Channel.BUFFERED)

    /** Snackbars and permission requests. */
    val effect: Flow<StoreReminderEffect> = effects.receiveAsFlow()

    /** What the page is on its way to while the system asks. */
    private sealed interface Goal {
        data object Enable : Goal

        data class Locate(val storeId: Long) : Goal
    }

    private data class Local(
        val openStoreId: Long? = null,
        val pasteText: String = "",
        val pasteError: Boolean = false,
        val locating: Boolean = false,
        val radiusDraft: Int? = null,
        val rationale: Boolean = false,
        val denied: PermissionStep? = null,
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private var goal: Goal? = null

    /** The steps already asked for the current goal (a step that stays missing after a "yes" is not asked in a loop). */
    private val asked = mutableSetOf<PermissionStep>()

    val uiState: StateFlow<StoreReminderUiState> = combine(
        reminders.state,
        supermarkets.observeSupermarkets().catch { emit(emptyList()) },
        local,
    ) { state, stores, ui -> toUiState(state, stores, ui) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StoreReminderUiState())

    fun onAction(action: StoreReminderAction) {
        when (action) {
            is StoreReminderAction.SetEnabled -> if (action.enabled) start(Goal.Enable) else turnOff()

            is StoreReminderAction.OpenStore -> local.update { Local(openStoreId = action.storeId, denied = it.denied, busy = it.busy) }

            StoreReminderAction.CloseStore -> local.update {
                it.copy(openStoreId = null, pasteText = "", pasteError = false, radiusDraft = null)
            }

            StoreReminderAction.UseCurrentLocation -> local.value.openStoreId?.let { start(Goal.Locate(it)) }

            is StoreReminderAction.PasteChanged -> local.update { it.copy(pasteText = action.text, pasteError = false) }

            StoreReminderAction.SavePasted -> savePasted()

            is StoreReminderAction.SetStoreEnabled -> withOpenStore { reminders.setStoreEnabled(it, action.enabled) }

            is StoreReminderAction.RadiusChanged -> local.update { it.copy(radiusDraft = StoreReminderRadius.snap(action.meters)) }

            StoreReminderAction.RadiusDone -> saveRadius()

            StoreReminderAction.ForgetPlace -> withOpenStore {
                reminders.forgetPlace(it)
                message(R.string.store_reminder_msg_forgotten)
            }

            StoreReminderAction.ContinueToBackground -> {
                local.update { it.copy(rationale = false) }
                request(PermissionStep.BACKGROUND)
            }

            StoreReminderAction.DismissRationale -> {
                local.update { it.copy(rationale = false) }
                goal = null
            }

            is StoreReminderAction.PermissionResult -> onPermissionResult(action.step, action.granted)

            // "Tillåt" on a card: the location for the open store's position, otherwise the way to turning it on.
            is StoreReminderAction.AskAgain -> {
                val openStore = local.value.openStoreId
                start(if (action.step == PermissionStep.LOCATION && openStore != null) Goal.Locate(openStore) else Goal.Enable)
            }

            StoreReminderAction.Resumed -> reminders.refreshPermissions()

            // The route's: a map app and the system settings.
            StoreReminderAction.ShowOnMap, StoreReminderAction.OpenAppSettings, StoreReminderAction.OpenLocationSettings -> Unit
        }
    }

    private fun saveRadius() {
        val radius = local.value.radiusDraft ?: return
        withOpenStore {
            reminders.setRadius(it, radius)
            local.update { current -> if (current.radiusDraft == radius) current.copy(radiusDraft = null) else current }
        }
    }

    private fun start(next: Goal) {
        goal = next
        asked.clear()
        local.update { it.copy(denied = null) }
        proceed()
    }

    /** The next missing permission for the goal, or the goal itself when nothing is missing. */
    private fun proceed() {
        val current = goal ?: return
        val granted = reminders.currentPermissions()
        when {
            !granted.precise -> request(PermissionStep.LOCATION)

            current is Goal.Locate -> {
                goal = null
                locate(current.storeId)
            }

            !granted.background -> if (PermissionStep.BACKGROUND in asked) {
                refused(PermissionStep.BACKGROUND)
            } else {
                local.update { it.copy(rationale = true) }
            }

            !granted.notifications -> request(PermissionStep.NOTIFICATIONS)

            else -> {
                goal = null
                turnOn()
            }
        }
    }

    private fun request(step: PermissionStep) {
        if (step in asked && step != PermissionStep.BACKGROUND) {
            refused(step)
            return
        }
        asked += step
        effects.trySend(StoreReminderEffect.RequestPermission(step))
    }

    private fun onPermissionResult(step: PermissionStep, granted: Boolean) {
        reminders.refreshPermissions()
        if (granted) {
            local.update { it.copy(denied = null) }
            proceed()
        } else {
            refused(step)
        }
    }

    private fun refused(step: PermissionStep) {
        goal = null
        local.update { it.copy(denied = step, rationale = false) }
    }

    private fun turnOn() = busy {
        reminders.setEnabled(true)
        message(R.string.store_reminder_msg_on)
    }

    private fun turnOff() {
        goal = null
        busy {
            reminders.setEnabled(false)
            message(R.string.store_reminder_msg_off)
        }
    }

    private fun locate(storeId: Long) {
        if (local.value.locating) return
        local.update { it.copy(locating = true) }
        viewModelScope.launch {
            try {
                when (val result = reminders.useCurrentLocation(storeId)) {
                    is CurrentLocationResult.Saved -> saved(result.accuracyMeters)
                    CurrentLocationResult.NoPermission -> refused(PermissionStep.LOCATION)
                    CurrentLocationResult.LocationOff -> message(R.string.store_reminder_msg_location_off)
                    CurrentLocationResult.Unavailable -> message(R.string.store_reminder_msg_no_position)
                    CurrentLocationResult.NotAvailable -> message(R.string.store_reminder_msg_failed)
                }
            } finally {
                local.update { it.copy(locating = false) }
            }
        }
    }

    private fun savePasted() {
        val storeId = local.value.openStoreId ?: return
        val text = local.value.pasteText
        if (text.isBlank()) return
        viewModelScope.launch {
            if (reminders.setPlaceFromText(storeId, text)) {
                local.update { it.copy(pasteText = "", pasteError = false) }
                saved(accuracy = null)
            } else {
                local.update { it.copy(pasteError = true) }
            }
        }
    }

    private suspend fun saved(accuracy: Int?) {
        when {
            !uiState.value.enabled -> message(R.string.store_reminder_msg_saved_turn_on)
            accuracy != null -> effects.send(StoreReminderEffect.Message(R.string.store_reminder_msg_saved_accuracy, accuracy))
            else -> message(R.string.store_reminder_msg_saved)
        }
    }

    private fun withOpenStore(block: suspend (Long) -> Unit) {
        val storeId = local.value.openStoreId ?: return
        viewModelScope.launch { block(storeId) }
    }

    private fun busy(block: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun message(text: Int) = effects.send(StoreReminderEffect.Message(text))

    private fun toUiState(state: StoreReminderState, stores: List<Supermarket>, ui: Local): StoreReminderUiState {
        val rows = stores
            .sortedBy { it.name.lowercase() }
            .map { StoreReminderStoreRow(id = it.id, name = it.name, spot = state.spots[it.id]) }
        val open = ui.openStoreId?.let { id -> rows.firstOrNull { it.id == id } }
        return StoreReminderUiState(
            loading = state.availability == StoreReminderAvailability.LOADING,
            availability = state.availability,
            enabled = state.enabled,
            stores = rows,
            permissions = state.permissions,
            problem = state.problem,
            watched = state.watched,
            sheet = open?.takeIf { state.availability == StoreReminderAvailability.AVAILABLE }?.let { row ->
                StoreReminderSheetState(
                    storeId = row.id,
                    name = row.name,
                    spot = row.spot,
                    radius = ui.radiusDraft ?: row.spot?.radiusMeters ?: StoreReminderRadius.DEFAULT_METERS,
                    pasteText = ui.pasteText,
                    pasteError = ui.pasteError,
                    locating = ui.locating,
                )
            },
            rationale = ui.rationale,
            denied = ui.denied,
            busy = ui.busy,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
