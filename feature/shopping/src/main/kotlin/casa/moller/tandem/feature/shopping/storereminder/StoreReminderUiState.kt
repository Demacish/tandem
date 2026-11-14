package casa.moller.tandem.feature.shopping.storereminder

import androidx.annotation.StringRes
import casa.moller.tandem.core.domain.storereminder.StoreReminderRadius
import casa.moller.tandem.core.model.shopping.StoreReminderAvailability
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import casa.moller.tandem.core.model.shopping.StoreReminderProblem
import casa.moller.tandem.core.model.shopping.StoreSpot

/** A permission the reminder asks for, in this order, each in context. */
enum class PermissionStep {
    /** ACCESS_FINE_LOCATION (with COARSE, as Android 12+ requires): a store's place from the position, and geofences. */
    LOCATION,

    /** ACCESS_BACKGROUND_LOCATION ("Tillåt alltid", the system settings page, after the explanation). */
    BACKGROUND,

    /** POST_NOTIFICATIONS: the reminder is a notification. */
    NOTIFICATIONS,
}

/**
 * One Tandoor store on the page.
 *
 * @property spot its place, null when none is set.
 */
data class StoreReminderStoreRow(val id: Long, val name: String, val spot: StoreSpot? = null)

/**
 * The open store's sheet.
 *
 * @property radius the distance shown (the slider's value while it is dragged).
 * @property pasteText the place being pasted.
 * @property pasteError the pasted text held no place.
 * @property locating "Jag är i butiken nu" waits for a position.
 */
data class StoreReminderSheetState(
    val storeId: Long,
    val name: String,
    val spot: StoreSpot? = null,
    val radius: Int = spot?.radiusMeters ?: StoreReminderRadius.DEFAULT_METERS,
    val pasteText: String = "",
    val pasteError: Boolean = false,
    val locating: Boolean = false,
)

/**
 * "Påminnelse nära butiken" (Fas 5, butik lane, plan §7 #34).
 *
 * @property availability who may use it (demo mode, no account and no Play services show why not).
 * @property enabled the reminder is on for the account.
 * @property stores Tandoor's stores by name, with their places.
 * @property permissions what the phone allows now.
 * @property problem why the geofences are not in place, null when they are (or the reminder is off).
 * @property watched how many stores are watched right now.
 * @property sheet the open store's sheet, null when none is open.
 * @property rationale the explanation before "Tillåt alltid" is shown.
 * @property denied the permission the user said no to (its card offers the system settings).
 * @property busy turning the reminder on or off.
 * @property canOpenMaps an app on the phone shows `geo:` places (set by the route).
 */
data class StoreReminderUiState(
    val loading: Boolean = true,
    val availability: StoreReminderAvailability = StoreReminderAvailability.LOADING,
    val enabled: Boolean = false,
    val stores: List<StoreReminderStoreRow> = emptyList(),
    val permissions: StoreReminderPermissions = StoreReminderPermissions(),
    val problem: StoreReminderProblem? = null,
    val watched: Int = 0,
    val sheet: StoreReminderSheetState? = null,
    val rationale: Boolean = false,
    val denied: PermissionStep? = null,
    val busy: Boolean = false,
    val canOpenMaps: Boolean = false,
) {
    val available: Boolean
        get() = availability == StoreReminderAvailability.AVAILABLE

    /** At least one store reminds when the reminder is on. */
    val hasEnabledPlace: Boolean
        get() = stores.any { it.spot?.enabled == true }
}

/** What the page and its sheet can ask for. */
sealed interface StoreReminderAction {
    /** The switch "Påminn mig nära butiken" (the permissions are asked first when turning it on). */
    data class SetEnabled(val enabled: Boolean) : StoreReminderAction

    data class OpenStore(val storeId: Long) : StoreReminderAction

    data object CloseStore : StoreReminderAction

    /** "Jag är i butiken nu – använd min position" (asks for the location first). */
    data object UseCurrentLocation : StoreReminderAction

    data class PasteChanged(val text: String) : StoreReminderAction

    data object SavePasted : StoreReminderAction

    /** The open store's own switch. */
    data class SetStoreEnabled(val enabled: Boolean) : StoreReminderAction

    /** The slider moves (shown at once, stored when it is let go). */
    data class RadiusChanged(val meters: Float) : StoreReminderAction

    data object RadiusDone : StoreReminderAction

    data object ForgetPlace : StoreReminderAction

    /** Handled by the route: the open store's place in a map app (`geo:`). */
    data object ShowOnMap : StoreReminderAction

    /** "Fortsätt" in the explanation before "Tillåt alltid". */
    data object ContinueToBackground : StoreReminderAction

    data object DismissRationale : StoreReminderAction

    /** The answer of a system permission dialog. */
    data class PermissionResult(val step: PermissionStep, val granted: Boolean) : StoreReminderAction

    /** "Tillåt" on a card: the system dialog again. */
    data class AskAgain(val step: PermissionStep) : StoreReminderAction

    /** Handled by the route: the app's page in the system settings. */
    data object OpenAppSettings : StoreReminderAction

    /** Handled by the route: the phone's location settings. */
    data object OpenLocationSettings : StoreReminderAction

    /** The page came back to the screen (perhaps from the system settings). */
    data object Resumed : StoreReminderAction
}

/** One-off results. */
sealed interface StoreReminderEffect {
    /** A snackbar; [arg] fills `%1$d` when the text has one. */
    data class Message(@StringRes val text: Int, val arg: Int? = null) : StoreReminderEffect

    /** Ask for [step] in context. */
    data class RequestPermission(val step: PermissionStep) : StoreReminderEffect
}
