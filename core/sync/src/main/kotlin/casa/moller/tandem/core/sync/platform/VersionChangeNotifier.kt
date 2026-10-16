package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.network.capabilities.TandoorVersionTable

/** Tells the user that the server's Tandoor version changed (plan B18). */
internal interface VersionChangeNotifier {
    /** Posts the system notification if allowed; true when it was posted. */
    fun onVersionChanged(change: ServerVersionChange): Boolean
}

/** What the notice about a version change says (plan 3.6: safe mode for untested versions). */
internal enum class VersionNotice {
    /** A tested version: nothing changes for the user. */
    UPDATED,

    /** Untested but inside the supported range: safe mode, changes wait. */
    SAFE_MODE,

    /** Outside the supported range: changes are not sent until Tandem is updated. */
    WRITES_OFF,
}

/** The pure decisions behind the version notification (unit tested). */
internal object VersionNotificationPolicy {
    /**
     * Plan B18: a system notification only when the user allowed notifications (Android 13+
     * permission granted, the app's notifications and its "Server" channel switched on).
     * Otherwise the app tells inside itself ([needsInAppNotice]).
     */
    fun shouldPost(permissionGranted: Boolean, notificationsEnabled: Boolean, channelEnabled: Boolean = true): Boolean =
        permissionGranted && notificationsEnabled && channelEnabled

    /**
     * The in-app fallback (plan B18): a change that got no system notification is shown once in
     * the app ("Tandoor was updated to 2.6.15"), but only for a tested version. An untested one
     * puts the app in safe mode, and the safe mode banner already tells about it (never two
     * messages for the same change); a version that is too old has its blocking notice.
     */
    fun needsInAppNotice(change: ServerVersionChange, notificationPosted: Boolean): Boolean =
        !notificationPosted && change.compatibility == ServerCompatibility.SUPPORTED

    fun noticeFor(change: ServerVersionChange): VersionNotice = when (TandoorVersionTable.assess(change.newVersion).writePolicy) {
        WritePolicy.SEND -> VersionNotice.UPDATED
        WritePolicy.QUEUE_PAUSED -> VersionNotice.SAFE_MODE
        WritePolicy.DISABLED -> VersionNotice.WRITES_OFF
    }
}
