package casa.moller.tandem.core.network.error

import casa.moller.tandem.core.model.error.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one API call and turns every failure into a typed [Outcome.Failure]: not only exceptions
 * but also errors such as a `NoClassDefFoundError` or `AbstractMethodError` from a broken
 * (minified) build, which are classified as [casa.moller.tandem.core.model.error.TandemError.Unknown].
 *
 * Coroutine cancellation is rethrown, never reported as an error, and so is a
 * [VirtualMachineError] (out of memory, stack overflow): see
 * [casa.moller.tandem.core.model.error.catchingUnexpected].
 */
suspend fun <T> apiCall(classifier: ErrorClassifier = ErrorClassifier.Default, block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: VirtualMachineError) {
    throw e
} catch (e: Throwable) {
    Outcome.Failure(classifier.classify(e))
}
