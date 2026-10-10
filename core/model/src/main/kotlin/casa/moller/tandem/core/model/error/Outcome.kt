package casa.moller.tandem.core.model.error

import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of an operation that can fail with a [TandemError]. Used instead of `kotlin.Result`
 * so that failures are typed and never carry arbitrary exceptions across layers.
 */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>

    data class Failure(val error: TandemError) : Outcome<Nothing>
}

val Outcome<*>.isSuccess: Boolean
    get() = this is Outcome.Success

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun Outcome<*>.errorOrNull(): TandemError? = (this as? Outcome.Failure)?.error

/** Returns the value or throws [TandemException]. */
fun <T> Outcome<T>.getOrThrow(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw TandemException(error)
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.fold(onSuccess: (T) -> R, onFailure: (TandemError) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (TandemError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

/**
 * Runs [block] and turns an unexpected [Throwable] into [Outcome.Failure] with
 * [TandemError.Unknown] (the generic error state), so that a bug or a broken release build shows
 * an error instead of killing the app from a coroutine. Use it where a user action starts a
 * coroutine (login, probe) and at layer boundaries that call code which should never throw.
 *
 * The error's detail is the throwable's fully qualified class name: R8 renames app and library
 * classes in a release build, and only the full name can be retraced with the published mapping.
 *
 * Never caught:
 * - [CancellationException]: cancellation must propagate, or structured concurrency breaks.
 * - [VirtualMachineError] ([OutOfMemoryError], [StackOverflowError], ...): the VM may be in no
 *   state to continue; crashing is the honest result.
 */
inline fun <T> catchingUnexpected(block: () -> Outcome<T>): Outcome<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: VirtualMachineError) {
    throw e
} catch (e: Throwable) {
    Outcome.Failure(TandemError.Unknown(e.javaClass.name))
}
