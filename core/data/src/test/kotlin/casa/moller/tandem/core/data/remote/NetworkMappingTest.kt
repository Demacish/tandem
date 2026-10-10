package casa.moller.tandem.core.data.remote

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.network.auth.DeviceCredentials
import casa.moller.tandem.core.network.auth.RevokeResult
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMappingTest {

    @Test
    fun deviceCredentialsKeepEveryFieldAndConvertTheExpiry() {
        val device = DeviceCredentials(
            baseUrl = "https://example.com/tandoor/",
            token = "tda_secret",
            tokenId = 12,
            expires = java.time.Instant.parse("2027-10-09T08:00:00.123456Z"),
            userId = 3,
            username = "anna",
            displayName = " ",
            spaceId = 5,
            spaceName = "Home",
            serverVersion = "2.6.13",
        )

        val remote = device.toRemoteCredentials()

        assertEquals("https://example.com/tandoor/", remote.baseUrl)
        assertEquals("tda_secret", remote.token)
        assertEquals(12L, remote.tokenId)
        assertEquals(Instant.parse("2027-10-09T08:00:00.123456Z"), remote.tokenExpires)
        assertEquals(3L, remote.userId)
        assertNull("blank display names become null", remote.displayName)
        assertEquals(5L, remote.spaceId)
        assertFalse("toString must not leak the token", remote.toString().contains("tda_secret"))
    }

    @Test
    fun compatibilityRoundTrips() {
        ServerCompatibility.entries.forEach {
            assertEquals(it, it.toNetworkCompatibility().toServerCompatibility())
        }
    }

    @Test
    fun storedVersionsAreClassified() {
        assertEquals(ServerCompatibility.SUPPORTED, compatibilityOf("2.6.13"))
        assertEquals(ServerCompatibility.UNTESTED_NEWER, compatibilityOf("2.7.0"))
        assertEquals(ServerCompatibility.UNTESTED_OLDER, compatibilityOf("2.5.3"))
        assertEquals(ServerCompatibility.TOO_OLD, compatibilityOf("1.5.19"))
        assertEquals(ServerCompatibility.UNRECOGNIZED, compatibilityOf(null))
    }

    @Test
    fun serverInfoBecomesATandoorServerWithTheSameBaseAndVersion() {
        val info = ServerInfo("https://example.com/tandoor/", "2.6.13", ServerCompatibility.SUPPORTED)
        val server = info.toTandoorServer()
        assertEquals(info.baseUrl, server.baseUrl)
        assertEquals(info.version, server.version)
        assertEquals(info, server.toServerInfo())
    }

    @Test
    fun revokeResultsMap() {
        assertEquals(TokenRevocation.REVOKED, RevokeResult.REVOKED.toTokenRevocation())
        assertEquals(TokenRevocation.ALREADY_INVALID, RevokeResult.ALREADY_INVALID.toTokenRevocation())
    }

    @Test
    fun displayAddressDropsSchemeAndTrailingSlash() {
        assertEquals(
            "example.com/tandoor",
            ServerInfo("https://example.com/tandoor/", "2.6.13", ServerCompatibility.SUPPORTED).displayAddress,
        )
        assertEquals("tandoor.example.com:8443", ServerInfo.displayAddress("https://tandoor.example.com:8443/"))
    }

    @Test
    fun compatibilityFlags() {
        assertFalse(ServerCompatibility.TOO_OLD.canLogIn)
        assertTrue(ServerCompatibility.UNTESTED_NEWER.canLogIn)
        assertTrue(ServerCompatibility.UNTESTED_NEWER.isSafeMode)
        assertTrue(ServerCompatibility.UNRECOGNIZED.isSafeMode)
        assertTrue(ServerCompatibility.UNTESTED_OLDER.canLogIn)
        assertTrue(ServerCompatibility.UNTESTED_OLDER.isSafeMode)
        ServerCompatibility.entries.forEach { assertEquals(it, it.toNetworkCompatibility().toServerCompatibility()) }
        assertFalse(ServerCompatibility.SUPPORTED.isSafeMode)
    }

    @Test
    fun dataProblemsAreRecognised() {
        DataProblem.entries.forEach { assertEquals(it, it.toError().dataProblem()) }
        assertNull(TandemError.Unknown("something else").dataProblem())
        assertNull(TandemError.Timeout.dataProblem())
    }
}
