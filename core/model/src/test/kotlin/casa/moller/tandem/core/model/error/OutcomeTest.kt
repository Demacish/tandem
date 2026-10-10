package casa.moller.tandem.core.model.error

import kotlin.coroutines.cancellation.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OutcomeTest {
    private val success: Outcome<Int> = Outcome.Success(2)
    private val failure: Outcome<Int> = Outcome.Failure(TandemError.Timeout)

    @Test
    fun mapAndFlatMapOnlyTouchSuccess() {
        assertEquals(Outcome.Success(4), success.map { it * 2 })
        assertSame(failure, failure.map { it * 2 })
        assertEquals(Outcome.Success("2"), success.flatMap { Outcome.Success(it.toString()) })
        assertEquals(
            Outcome.Failure(TandemError.NotFound),
            success.flatMap { Outcome.Failure(TandemError.NotFound) },
        )
    }

    @Test
    fun accessors() {
        assertTrue(success.isSuccess)
        assertFalse(failure.isSuccess)
        assertEquals(2, success.getOrNull())
        assertNull(failure.getOrNull())
        assertNull(success.errorOrNull())
        assertEquals(TandemError.Timeout, failure.errorOrNull())
        assertEquals("ok 2", success.fold({ "ok $it" }, { "err" }))
        assertEquals("err", failure.fold({ "ok $it" }, { "err" }))
    }

    @Test
    fun getOrThrowWrapsTheError() {
        assertEquals(2, success.getOrThrow())
        val thrown = assertThrows<TandemException> { failure.getOrThrow() }
        assertEquals(TandemError.Timeout, thrown.error)
    }

    @Test
    fun callbacksRunOnTheMatchingBranch() {
        var seen: Any? = null
        success.onSuccess { seen = it }.onFailure { seen = it }
        assertEquals(2, seen)
        failure.onSuccess { seen = it }.onFailure { seen = it }
        assertEquals(TandemError.Timeout, seen)
    }

    @Test
    fun badRequestExposesNonFieldErrors() {
        val error = TandemError.BadRequest(
            mapOf(TandemError.BadRequest.NON_FIELD_ERRORS to listOf("Unable to log in.")),
        )
        assertEquals(listOf("Unable to log in."), error.nonFieldErrors)
        assertEquals(emptyList<String>(), TandemError.BadRequest().nonFieldErrors)
    }

    @Test
    fun catchingUnexpectedTurnsUnexpectedThrowablesIntoTheGenericError() {
        assertSame(success, catchingUnexpected { success })
        assertEquals(
            Outcome.Failure(TandemError.Unknown("java.lang.ClassCastException")),
            catchingUnexpected<Int> { throw ClassCastException() },
        )
        // Errors too: a broken (minified) build fails with NoClassDefFoundError and the like.
        assertEquals(
            Outcome.Failure(TandemError.Unknown("java.lang.NoClassDefFoundError")),
            catchingUnexpected<Int> { throw NoClassDefFoundError("x") },
        )
    }

    @Test
    fun catchingUnexpectedNeverSwallowsCancellationOrVirtualMachineErrors() {
        assertThrows<CancellationException> { catchingUnexpected<Int> { throw CancellationException("cancelled") } }
        assertThrows<OutOfMemoryError> { catchingUnexpected<Int> { throw OutOfMemoryError() } }
        assertThrows<StackOverflowError> { catchingUnexpected<Int> { throw StackOverflowError() } }
    }
}
