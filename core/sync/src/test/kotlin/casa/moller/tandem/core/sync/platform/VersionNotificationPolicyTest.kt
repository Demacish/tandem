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
    }

    @Test
    fun noticeFollowsTheNewVersion() {
        assertEquals(VersionNotice.UPDATED, VersionNotificationPolicy.noticeFor(change("2.6.15")))
        assertEquals(VersionNotice.SAFE_MODE, VersionNotificationPolicy.noticeFor(change("2.7.1")))
        assertEquals(VersionNotice.WRITES_OFF, VersionNotificationPolicy.noticeFor(change("2.8.0")))
    }

    private fun change(version: String) = ServerVersionChange("2.6.13", version, ServerCompatibility.SUPPORTED)
}
