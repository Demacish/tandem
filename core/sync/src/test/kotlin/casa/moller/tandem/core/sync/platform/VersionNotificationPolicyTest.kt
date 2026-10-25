package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerVersionChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan B18: a notification only with the user's permission; the text follows the write policy. */
class VersionNotificationPolicyTest {
    @Test
    fun postsOnlyWhenPermittedAndEnabled() {
        assertTrue(VersionNotificationPolicy.shouldPost(permissionGranted = true, notificationsEnabled = true))
        assertFalse(VersionNotificationPolicy.shouldPost(permissionGranted = false, notificationsEnabled = true))
        assertFalse(VersionNotificationPolicy.shouldPost(permissionGranted = true, notificationsEnabled = false))
        // The "Server" channel switched off in the system settings.
        assertFalse(VersionNotificationPolicy.shouldPost(permissionGranted = true, notificationsEnabled = true, channelEnabled = false))
    }

    @Test
    fun theInAppNoticeIsTheFallbackForATestedVersionOnly() {
        val tested = ServerVersionChange("2.6.13", "2.6.15", ServerCompatibility.SUPPORTED)
        assertTrue(VersionNotificationPolicy.needsInAppNotice(tested, notificationPosted = false))
        // Never twice: the notification already told the user.
        assertFalse(VersionNotificationPolicy.needsInAppNotice(tested, notificationPosted = true))
        // An untested version has the safe mode banner; one that is too old its blocking notice.
        listOf(
            change("2.7.3", ServerCompatibility.UNTESTED_NEWER),
            change("2.5.0", ServerCompatibility.UNTESTED_OLDER),
            change("dev", ServerCompatibility.UNRECOGNIZED),
            change("1.5.0", ServerCompatibility.TOO_OLD),
        ).forEach { assertFalse(it.newVersion, VersionNotificationPolicy.needsInAppNotice(it, notificationPosted = false)) }
    }

    @Test
    fun noticeFollowsTheNewVersion() {
        assertEquals(VersionNotice.UPDATED, VersionNotificationPolicy.noticeFor(change("2.6.15")))
        assertEquals(VersionNotice.SAFE_MODE, VersionNotificationPolicy.noticeFor(change("2.7.3")))
        assertEquals(VersionNotice.WRITES_OFF, VersionNotificationPolicy.noticeFor(change("2.8.0")))
    }

    private fun change(version: String, compatibility: ServerCompatibility = ServerCompatibility.SUPPORTED) =
        ServerVersionChange("2.6.13", version, compatibility)
}
