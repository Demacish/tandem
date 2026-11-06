package casa.moller.tandem.feature.pantry.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.pantry.PantryGroup
import casa.moller.tandem.core.model.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hemma's in-context offer of "Notis när något går ut" (Fas 4, skafferinotis lane; plan §8.8
 * "Använd upp", §8.7 "Måltidspåminnelser"). Null = no card.
 *
 * @property blocked the notification is switched on but the system does not let Tandem post: the
 *   card asks again ("Fråga igen") instead of offering it.
 * @property openSettings blocked, and Tandem has asked before: after a second no the system answers
 *   "denied" without a dialog, so the card opens the system's page instead ("Öppna inställningar").
 */
data class ExpiryReminderOffer(val blocked: Boolean = false, val openSettings: Boolean = false)

/** What the card asks for. */
sealed interface ExpiryReminderAction {
    /** "Slå på" (or "Fråga igen"): the setting on, and the permission asked in context when it is missing. */
    data object Enable : ExpiryReminderAction

    /** "Nej tack": never offered again (Settings keeps the switch). */
    data object Dismiss : ExpiryReminderAction

    /** "Öppna inställningar": the app's notification page of the system. */
    data object OpenSettings : ExpiryReminderAction

    /** Back on Hemma: notifications may have been allowed in the system's settings. */
    data object Refresh : ExpiryReminderAction

    /** The answer to the system's notification dialog. */
    data class PermissionResult(val granted: Boolean) : ExpiryReminderAction
}

/** One-off effects of the offer. */
sealed interface ExpiryReminderEffect {
    /** Show the system's notification permission dialog (Android 13+). */
    data object RequestPermission : ExpiryReminderEffect

    /** Open the system's notification page for the app. */
    data object OpenNotificationSettings : ExpiryReminderEffect

    /** "Du får en notis på morgonen när något går ut." */
    data object Enabled : ExpiryReminderEffect

    /** The setting could not be saved. */
    data object NotSaved : ExpiryReminderEffect
}

/**
 * The offer on Hemma: shown when the household's pantry has entries WITH an expiry date, the
 * notification is off and the offer was never dismissed; never for a guest (read-only, no pantry
 * of their own) or in demo mode (demo never posts). Off by default: nothing happens until "Slå på".
 */
@HiltViewModel
class ExpiryReminderViewModel @Inject internal constructor(
    private val settings: SettingsRepository,
    private val timers: TimerController,
    permissions: SpacePermissionsRepository,
    pantry: PantryRepository,
) : ViewModel() {
    private val effectChannel = Channel<ExpiryReminderEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<ExpiryReminderEffect> = effectChannel.receiveAsFlow()

    val offer: StateFlow<ExpiryReminderOffer?> = combine(
        settings.settings,
        timers.capabilities,
        permissions.permissions,
        pantry.observePantry().map { groups -> groups.hasExpiryDates() },
    ) { current, capabilities, perms, dated ->
        offerOf(current, capabilities.notificationsAllowed, perms, dated, capabilities.notificationPermissionAsked)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun onAction(action: ExpiryReminderAction) {
        when (action) {
            ExpiryReminderAction.Enable -> viewModelScope.launch {
                if (settings.setPantryExpiryNotifications(true) is Outcome.Failure) {
                    effectChannel.send(ExpiryReminderEffect.NotSaved)
                } else if (!timers.capabilities.value.notificationsAllowed) {
                    effectChannel.send(ExpiryReminderEffect.RequestPermission)
                } else {
                    effectChannel.send(ExpiryReminderEffect.Enabled)
                }
            }

            ExpiryReminderAction.Dismiss -> viewModelScope.launch {
                if (settings.setPantryExpiryOfferDismissed(true) is Outcome.Failure) effectChannel.send(ExpiryReminderEffect.NotSaved)
            }

            ExpiryReminderAction.OpenSettings -> effectChannel.trySend(ExpiryReminderEffect.OpenNotificationSettings)

            ExpiryReminderAction.Refresh -> timers.refreshCapabilities()

            is ExpiryReminderAction.PermissionResult -> viewModelScope.launch {
                settings.setNotificationPermissionAsked(true)
                timers.refreshCapabilities()
                val on = settings.settings.first().pantryExpiryNotifications
                if (action.granted && on) effectChannel.send(ExpiryReminderEffect.Enabled)
            }
        }
    }

    internal companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private fun List<PantryGroup>.hasExpiryDates(): Boolean = any { group -> group.entries.any { it.expires != null } }

        /** The offer for these inputs (pure, tested). */
        fun offerOf(
            settings: AppSettings,
            notificationsAllowed: Boolean,
            permissions: SpacePermissions,
            hasExpiryDates: Boolean,
            permissionAsked: Boolean = false,
        ): ExpiryReminderOffer? = when {
            permissions.readOnly || permissions.isDemo || settings.pantryExpiryOfferDismissed -> null
            settings.pantryExpiryNotifications ->
                if (notificationsAllowed) null else ExpiryReminderOffer(blocked = true, openSettings = permissionAsked)
            hasExpiryDates -> ExpiryReminderOffer(blocked = false)
            else -> null
        }
    }
}
