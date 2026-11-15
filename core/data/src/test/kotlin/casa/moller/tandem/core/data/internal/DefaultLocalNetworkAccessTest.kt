package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.network.http.LocalNetworkGuard
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fas 5 (sdk37 lane): when the intro asks for the local network permission (docs/BYGGE.md "Target SDK 37"). */
class DefaultLocalNetworkAccessTest {

    private class FakeGuard(
        val enforced: Boolean = true,
        val missing: Boolean = true,
        val localHosts: Set<String> = emptySet(),
        val block: CountDownLatch? = null,
    ) : LocalNetworkGuard {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun enforced(): Boolean = enforced

        override fun permissionMissing(): Boolean = missing

        override fun isLocal(host: String): Boolean {
            asked += host
            block?.await(5, TimeUnit.SECONDS)
            return host in localHosts
        }
    }

    private fun access(guard: FakeGuard, timeout: Duration = 2.seconds) = DefaultLocalNetworkAccess(guard, Dispatchers.IO, timeout)

    @Test
    fun aHomeAddressWithoutThePermissionNeedsIt() = runBlocking {
        val guard = FakeGuard(localHosts = setOf("192.168.1.5"))
        assertTrue(access(guard).needsPermission("192.168.1.5:8080/tandoor"))
        assertEquals("the host as the probe reads it", listOf("192.168.1.5"), guard.asked)
    }

    @Test
    fun aServerOnTheInternetNeedsNothing() = runBlocking {
        val guard = FakeGuard(localHosts = setOf("192.168.1.5"))
        assertFalse(access(guard).needsPermission("https://recipes.example.com/"))
        assertEquals(listOf("recipes.example.com"), guard.asked)
    }

    @Test
    fun withThePermissionOrBeforeAndroid17NothingIsLookedUp() = runBlocking {
        val granted = FakeGuard(missing = false, localHosts = setOf("192.168.1.5"))
        assertFalse(access(granted).needsPermission("192.168.1.5"))
        assertTrue(granted.asked.isEmpty())
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", access(granted).permission)

        val android16 = FakeGuard(enforced = false, missing = false)
        assertFalse(access(android16).needsPermission("tandoor.local"))
        assertNull("nothing to ask on Android 16", access(android16).permission)
        assertTrue(android16.asked.isEmpty())
    }

    @Test
    fun anAddressThatCannotBeReadAsksNothing() = runBlocking {
        val guard = FakeGuard()
        assertFalse(access(guard).needsPermission(""))
        assertFalse(access(guard).needsPermission("http://192.168.1.5"))
        assertTrue(guard.asked.isEmpty())
    }

    @Test
    fun aHangingResolverGivesUpQuickly() = runBlocking {
        val latch = CountDownLatch(1)
        val guard = FakeGuard(localHosts = setOf("tandoor.home.example"), block = latch)
        val started = System.nanoTime()
        assertFalse(access(guard, timeout = 100.milliseconds).needsPermission("tandoor.home.example"))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        latch.countDown()
        assertTrue("took $tookMs ms", tookMs < 2_000)
    }
}
