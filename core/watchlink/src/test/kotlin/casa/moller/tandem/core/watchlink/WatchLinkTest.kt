package casa.moller.tandem.core.watchlink

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Fas 5 (wear lane): the watch protocol and the watch token's lifetime.
class WatchLinkTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")

    @Test
    fun credentialsSurviveTheTripAndNeverPrintTheToken() {
        val credentials = WatchCredentials(
            baseUrl = "https://tandoor.example.com/",
            token = "tda_00000000_0000_0000_0000_000000000001",
            tokenId = 42,
            expiresEpochMillis = WatchTokenPolicy.expiry(now).toEpochMilliseconds(),
            accountKey = "account-1",
            accountLabel = "Anna",
            serverVersion = "2.6.13",
        )

        val bytes = WatchCodec.encode(WatchCredentials.serializer(), credentials)

        assertEquals(credentials, WatchCodec.decode(WatchCredentials.serializer(), bytes))
        assertFalse(credentials.toString().contains("tda_"))
    }

    @Test
    fun brokenOrEmptyPayloadsAreNull() {
        assertNull(WatchCodec.decode(WatchCredentials.serializer(), null))
        assertNull(WatchCodec.decode(WatchCredentials.serializer(), ByteArray(0)))
        assertNull(WatchCodec.decode(WatchCredentials.serializer(), "{\"baseUrl\":1".encodeToByteArray()))
        assertNull(WatchCodec.decode(WatchCredentials.serializer(), "{}".encodeToByteArray()))
    }

    @Test
    fun aNewerPhoneMayAddFields() {
        val newer = """{"protocol":2,"sentAtEpochMillis":5,"timers":[],"somethingNew":true}"""

        val mirror = WatchCodec.decode(TimerMirror.serializer(), newer.encodeToByteArray())

        assertEquals(TimerMirror(protocol = 2, sentAtEpochMillis = 5), mirror)
    }

    @Test
    fun timersRoundTrip() {
        val mirror = TimerMirror(
            sentAtEpochMillis = 1,
            timers = listOf(
                MirroredTimer(1, "Pasta", "Krämig pasta", MirroredTimerState.RUNNING, endsAtEpochMillis = 600_000, durationMillis = 1),
                MirroredTimer(2, "Ugn", null, MirroredTimerState.PAUSED, remainingMillis = 30_000, durationMillis = 60_000),
            ),
        )

        assertEquals(mirror, WatchCodec.decode(TimerMirror.serializer(), WatchCodec.encode(TimerMirror.serializer(), mirror)))
        val action = TimerAction(1, TimerActionKind.STOP_RINGING)
        assertEquals(action, WatchCodec.decode(TimerAction.serializer(), WatchCodec.encode(TimerAction.serializer(), action)))
    }

    @Test
    fun theWatchTokenLivesNinetyDaysAndIsRenewedThirtyDaysBefore() {
        val expires = WatchTokenPolicy.expiry(now)

        assertEquals(now + 90.days, expires)
        assertFalse(WatchTokenPolicy.needsRenewal(expires, now))
        assertFalse(WatchTokenPolicy.needsRenewal(expires, now + 59.days))
        assertTrue(WatchTokenPolicy.needsRenewal(expires, now + 60.days))
        assertTrue(WatchTokenPolicy.needsRenewal(null, now))
        assertFalse(WatchTokenPolicy.isExpired(expires, now + 89.days))
        assertTrue(WatchTokenPolicy.isExpired(expires, now + 90.days))
        assertFalse(WatchTokenPolicy.isExpired(null, now))
        assertEquals("read write", WatchTokenPolicy.SCOPE)
    }

    @Test
    fun everyPathIsUnderThePrefix() {
        val paths = listOf(
            WatchLink.PATH_PAIR,
            WatchLink.PATH_UNPAIR,
            WatchLink.PATH_STATUS,
            WatchLink.PATH_TIMERS,
            WatchLink.PATH_TIMERS_REQUEST,
            WatchLink.PATH_TIMER_ACTION,
        )

        assertTrue(paths.all { it.startsWith(WatchLink.PATH_PREFIX + "/") })
        assertEquals(paths.size, paths.toSet().size)
    }
}
