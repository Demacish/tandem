package casa.moller.tandem.core.network.error

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiCallTest {
    @Test
    fun successAndClassifiedExceptions() = runBlocking {
        assertEquals(Outcome.Success(1), apiCall { 1 })
        assertEquals(Outcome.Failure(TandemError.Timeout), apiCall<Int> { throw SocketTimeoutException() })
    }

    @Test
    fun errorsOfABrokenBuildBecomeTheGenericError() = runBlocking {
        // What R8 mistakes look like at runtime: these are Errors, not Exceptions.
        assertEquals(
            Outcome.Failure(TandemError.Unknown("java.lang.AbstractMethodError")),
            apiCall<Int> { throw AbstractMethodError() },
        )
        assertEquals(
            Outcome.Failure(TandemError.Unknown("java.lang.NoSuchMethodError")),
            apiCall<Int> { throw NoSuchMethodError() },
        )
    }

    @Test
    fun cancellationAndVirtualMachineErrorsAreRethrown() {
        assertThrows(CancellationException::class.java) { runBlocking { apiCall<Int> { throw CancellationException("x") } } }
        assertThrows(OutOfMemoryError::class.java) { runBlocking { apiCall<Int> { throw OutOfMemoryError() } } }
    }
}
