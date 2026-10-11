package casa.moller.tandem.core.model.error

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectivityTest {
    @Test
    fun onlyUnreachableServersCountAsOffline() {
        assertTrue(TandemError.NetworkUnavailable.isConnectivityProblem)
        assertTrue(TandemError.UnknownHost.isConnectivityProblem)
        assertTrue(TandemError.Timeout.isConnectivityProblem)
        assertFalse(TandemError.Blocked.isConnectivityProblem)
        assertFalse(TandemError.ServerError(502).isConnectivityProblem)
        assertFalse(TandemError.Unauthorized(UnauthorizedSource.TANDOOR).isConnectivityProblem)
        assertFalse(TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE).isConnectivityProblem)
    }
}
