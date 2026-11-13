package casa.moller.tandem.feature.mealplan.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.mealplan.MealCalendarRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.mealplan.MealCalendarAvailability
import casa.moller.tandem.feature.mealplan.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Matsedeln i kalendern" (Fas 5, kalender lane, plan §7 #36), from Veckan's menu and Mer →
 * Inställningar. Turning the phone's calendar on asks for the calendar permission first (in
 * context, never at start); "Ny länk" and "Stäng av" ask for a yes, since calendars that subscribe
 * lose the old address. Copying, sharing and opening the link are the route's (platform) business.
 */
@HiltViewModel
class MealCalendarViewModel @Inject constructor(private val calendar: MealCalendarRepository) : ViewModel() {
    private val effects = Channel<MealCalendarEffect>(Channel.BUFFERED)

    /** Snackbars and the permission request. */
    val effect: Flow<MealCalendarEffect> = effects.receiveAsFlow()

    private val busy = MutableStateFlow<MealCalendarBusy?>(null)
    private val confirm = MutableStateFlow<LinkConfirm?>(null)
    private val permissionDenied = MutableStateFlow(false)

    val uiState: StateFlow<MealCalendarUiState> = combine(calendar.state, busy, confirm, permissionDenied) { state, running, open, denied ->
        MealCalendarUiState(
            loading = state.availability == MealCalendarAvailability.LOADING,
            availability = state.availability,
            link = state.link,
            local = state.local,
            busy = running,
            confirm = open?.takeIf { state.link.isOn },
            permissionDenied = denied && !state.local.enabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MealCalendarUiState())

    fun onAction(action: MealCalendarAction) {
        when (action) {
            is MealCalendarAction.SetLocal -> if (action.enabled) turnOnLocal() else run(MealCalendarBusy.LOCAL) { turnOffLocal() }

            MealCalendarAction.AskPermission -> effects.trySend(MealCalendarEffect.RequestPermission)

            is MealCalendarAction.PermissionResult -> if (action.granted) {
                permissionDenied.value = false
                run(MealCalendarBusy.LOCAL) { enableLocal() }
            } else {
                permissionDenied.value = true
                effects.trySend(MealCalendarEffect.Message(R.string.calendar_msg_permission_denied))
            }

            MealCalendarAction.RefreshLocal -> run(MealCalendarBusy.LOCAL) {
                when (val written = calendar.refreshLocalCalendar()) {
                    is Outcome.Success -> message(R.string.calendar_msg_refreshed)
                    is Outcome.Failure -> localFailed()
                }
            }

            MealCalendarAction.CreateLink -> run(MealCalendarBusy.LINK) {
                linkDone(calendar.enableLink(), R.string.calendar_msg_link_created)
            }

            MealCalendarAction.AskReplace -> confirm.value = LinkConfirm.REPLACE

            MealCalendarAction.AskDisable -> confirm.value = LinkConfirm.DISABLE

            MealCalendarAction.Dismiss -> confirm.value = null

            MealCalendarAction.Confirm -> {
                val open = confirm.value ?: return
                confirm.value = null
                run(MealCalendarBusy.LINK) {
                    when (open) {
                        LinkConfirm.REPLACE -> linkDone(calendar.replaceLink(), R.string.calendar_msg_link_replaced)
                        LinkConfirm.DISABLE -> linkDone(calendar.disableLink(), R.string.calendar_msg_link_disabled)
                    }
                }
            }

            // The route's: clipboard, share sheet, calendar app, system settings.
            MealCalendarAction.CopyLink, MealCalendarAction.ShareLink, MealCalendarAction.OpenInCalendarApp,
            MealCalendarAction.OpenAppSettings,
            -> Unit
        }
    }

    private fun turnOnLocal() {
        if (!calendar.hasCalendarPermission()) {
            effects.trySend(MealCalendarEffect.RequestPermission)
            return
        }
        run(MealCalendarBusy.LOCAL) { enableLocal() }
    }

    private suspend fun enableLocal() {
        when (val result = calendar.setLocalCalendar(true)) {
            is Outcome.Success -> effects.send(MealCalendarEffect.Message(R.string.calendar_msg_local_on, R.string.calendar_local_name))
            is Outcome.Failure -> localFailed()
        }
    }

    private suspend fun turnOffLocal() {
        calendar.setLocalCalendar(false)
        message(R.string.calendar_msg_local_off)
    }

    /** A failed write; a missing permission shows the card that leads to the system settings. */
    private suspend fun localFailed() {
        if (!calendar.hasCalendarPermission()) permissionDenied.value = true
        message(R.string.calendar_msg_local_failed)
    }

    private suspend fun linkDone(result: Outcome<Unit>, done: Int) {
        when (result) {
            is Outcome.Success -> message(done)

            is Outcome.Failure -> message(
                when {
                    result.error.isConnectivityProblem -> R.string.calendar_msg_offline
                    result.error.dataProblem() == DataProblem.READ_ONLY -> R.string.calendar_msg_guest
                    else -> R.string.calendar_msg_failed
                },
            )
        }
    }

    private suspend fun message(text: Int) = effects.send(MealCalendarEffect.Message(text))

    /** Runs [block] unless something else runs; [busy] while it does. */
    private fun run(what: MealCalendarBusy, block: suspend () -> Unit) {
        if (busy.value != null) return
        busy.value = what
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = null
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
