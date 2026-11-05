package casa.moller.tandem.feature.shell.push

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.push.FastPushRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushResult
import casa.moller.tandem.core.model.push.FastPushState
import casa.moller.tandem.core.model.push.PushDistributor
import casa.moller.tandem.feature.shell.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Mer → Synk och lagring → Snabb push (Fas 4, push lane). Turning on checks the service, saves
 * the address and the distributor and registers (the repository does the rest when the endpoint
 * arrives); it also switches on "Notis när någon ändrar listan" (that is the notification a
 * wake-up leads to) and asks for the notification permission in context when it is missing.
 */
@HiltViewModel
class FastPushViewModel @Inject constructor(
    private val fastPush: FastPushRepository,
    private val settings: SettingsRepository,
    private val timers: TimerController,
) : ViewModel() {
    private val effects = Channel<FastPushEffect>(Channel.BUFFERED)

    /** One-off results: messages, the permission request. */
    val effect: Flow<FastPushEffect> = effects.receiveAsFlow()

    /** Null until the user types: the saved address is shown. */
    private val typedUrl = MutableStateFlow<String?>(null)
    private val chosen = MutableStateFlow<String?>(null)
    private val distributors = MutableStateFlow<List<PushDistributor>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val urlInvalid = MutableStateFlow(false)

    private data class Local(
        val typedUrl: String?,
        val chosen: String?,
        val distributors: List<PushDistributor>,
        val busy: Boolean,
        val urlInvalid: Boolean,
    )

    private val local = combine(typedUrl, chosen, distributors, busy, urlInvalid) { url, picked, apps, running, invalid ->
        Local(url, picked, apps, running, invalid)
    }

    val uiState: StateFlow<FastPushUiState> = combine(
        fastPush.state,
        settings.settings.map { it.shoppingChangeNotifications },
        timers.capabilities.map { it.notificationsAllowed },
        local,
    ) { push, listNotifications, allowed, ui -> stateOf(push, listNotifications, allowed, ui) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FastPushUiState())

    init {
        refreshDistributors()
    }

    fun onAction(action: FastPushAction) {
        when (action) {
            is FastPushAction.UrlChanged -> {
                typedUrl.value = action.url
                urlInvalid.value = false
            }

            is FastPushAction.SelectDistributor -> chosen.value = action.packageName

            FastPushAction.Enable -> launchAction { enable() }

            FastPushAction.Disable -> launchAction { disable() }

            FastPushAction.SendTest -> launchAction { sendTest() }

            FastPushAction.Refresh -> {
                refreshDistributors()
                timers.refreshCapabilities()
            }

            is FastPushAction.NotificationPermissionResult -> viewModelScope.launch {
                settings.setNotificationPermissionAsked(true)
                timers.refreshCapabilities()
            }
        }
    }

    private fun refreshDistributors() {
        distributors.value = fastPush.distributors()
    }

    /** Runs [block] unless another action runs; [busy] while it does. */
    private fun launchAction(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun enable() {
        val state = uiState.value
        val distributor = state.selectedDistributor ?: return
        when (val result = fastPush.enable(state.url, distributor)) {
            is FastPushResult.Failure -> {
                if (result.error == FastPushError.INVALID_URL) urlInvalid.value = true
                effects.send(FastPushEffect.Message(messageOf(result.error)))
            }

            is FastPushResult.Success -> {
                typedUrl.value = null
                // The wake-up's notification is "Notis när någon ändrar listan": turn it on with push.
                if (!settings.settings.first().shoppingChangeNotifications) settings.setShoppingChangeNotifications(true)
                effects.send(FastPushEffect.Message(R.string.fast_push_message_enabling))
                if (!timers.capabilities.value.notificationsAllowed) effects.send(FastPushEffect.RequestNotificationPermission)
            }
        }
    }

    private suspend fun disable() {
        when (val result = fastPush.disable()) {
            is FastPushResult.Failure -> effects.send(FastPushEffect.Message(messageOf(result.error)))
            is FastPushResult.Success -> effects.send(FastPushEffect.Message(R.string.fast_push_message_disabled))
        }
    }

    private suspend fun sendTest() {
        when (val result = fastPush.sendTest()) {
            is FastPushResult.Failure -> effects.send(FastPushEffect.Message(messageOf(result.error)))
            is FastPushResult.Success -> effects.send(FastPushEffect.Message(R.string.fast_push_message_test_sent))
        }
    }

    private fun stateOf(push: FastPushState, listNotifications: Boolean, allowed: Boolean, ui: Local): FastPushUiState {
        val installed = ui.distributors.map { it.packageName }
        val selected = ui.chosen?.takeIf { it in installed }
            ?: push.distributor?.takeIf { it in installed }
            ?: installed.singleOrNull()
        return FastPushUiState(
            loading = false,
            availability = push.availability,
            url = ui.typedUrl ?: push.serviceUrl,
            urlInvalid = ui.urlInvalid,
            distributors = ui.distributors,
            selectedDistributor = selected,
            status = push.status,
            error = push.error,
            testSupported = push.testSupported,
            pollSeconds = push.pollSeconds,
            lastWakeUp = push.lastWakeUp,
            busy = ui.busy,
            listNotificationsOn = listNotifications,
            notificationsAllowed = allowed,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The short message (snackbar) for [error]. */
internal fun messageOf(error: FastPushError): Int = when (error) {
    FastPushError.INVALID_URL -> R.string.fast_push_error_invalid_url
    FastPushError.NOT_A_PUSH_SERVICE -> R.string.fast_push_error_not_a_service
    FastPushError.UNREACHABLE -> R.string.fast_push_error_unreachable
    FastPushError.UNAUTHORIZED -> R.string.fast_push_error_unauthorized
    FastPushError.OTHER_HOUSEHOLD -> R.string.fast_push_error_other_household
    FastPushError.GUEST -> R.string.fast_push_error_guest
    FastPushError.TOO_MANY -> R.string.fast_push_error_too_many
    FastPushError.RATE_LIMITED -> R.string.fast_push_error_rate_limited
    FastPushError.SERVICE_ERROR -> R.string.fast_push_error_service
    FastPushError.NO_DISTRIBUTOR -> R.string.fast_push_error_no_distributor
    FastPushError.DISTRIBUTOR_FAILED -> R.string.fast_push_error_distributor
    FastPushError.TEST_UNSUPPORTED -> R.string.fast_push_error_test_unsupported
}
