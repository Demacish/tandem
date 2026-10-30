package casa.moller.tandem.core.sync.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.files.CacheDirectories
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.sync.drink.AndroidDrinkImageStore
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas S komplettering (fix-ui #3/#22): when no account is left (the last logout, "Rensa appdata"),
 * [AccountMirror] empties the drinks' photo copies AND the camera originals of "Ny dryck" in
 * `cache/drink-camera/` (often with EXIF location), through the real [AndroidDrinkImageStore].
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DrinkCameraCleanupTest {
    private val graph = SyncTestGraph()

    @After
    fun close() = graph.close()

    @Test
    fun noAccountLeftEmptiesTheCameraOriginalsAndTheCopies() = runBlocking {
        val context = graph.context
        val original = File(context.cacheDir, "${CacheDirectories.DRINK_CAMERA}/drink-1.jpg").apply {
            parentFile?.mkdirs()
            writeText("jpeg")
        }
        val copy = File(context.filesDir, "drink-images/1/drink-2.jpg").apply {
            parentFile?.mkdirs()
            writeText("jpeg")
        }
        val mirror = AccountMirror(
            graph.sessions,
            graph.accountStore,
            graph.settings,
            graph.health,
            graph.databases,
            graph.demo,
            graph.imageCache,
            graph.notifier,
            graph.clock,
            graph.scope,
            AndroidDrinkImageStore(context),
        )

        // An account is still stored: nothing of the drink photos goes.
        mirror.onSession(Session.NoAccount)
        assertTrue(original.exists())

        graph.accountStore.state.value = emptyList()
        mirror.onSession(Session.NoAccount)

        assertFalse(original.exists())
        assertFalse(copy.exists())
    }
}
