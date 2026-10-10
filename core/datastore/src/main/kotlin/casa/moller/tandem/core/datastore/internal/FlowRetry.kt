package casa.moller.tandem.core.datastore.internal

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/** Wait before the first new attempt after a read failure; doubled per attempt. */
internal const val FIRST_RETRY_DELAY_MILLIS: Long = 1_000L

/** Longest wait between two read attempts. */
internal const val MAX_RETRY_DELAY_MILLIS: Long = 30_000L

private const val MAX_BACKOFF_SHIFT = 5

/**
 * Keeps a long-lived collector alive when reading fails: runs [onFailure], emits [fallback],
 * waits (1 s, 2 s, 4 s, ... up to [MAX_RETRY_DELAY_MILLIS]) and subscribes to the upstream again.
 * DataStore does not cache a failed read, so the next subscription reads the file again and the
 * collector gets the real data as soon as it is readable. Cancellation is never caught.
 */
internal fun <T> Flow<T>.resubscribeOnFailure(fallback: T, onFailure: () -> Unit = {}): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) return@retryWhen false
    onFailure()
    emit(fallback)
    delay(retryDelayMillis(attempt))
    true
}

internal fun retryDelayMillis(attempt: Long): Long = (FIRST_RETRY_DELAY_MILLIS shl attempt.coerceIn(0L, MAX_BACKOFF_SHIFT.toLong()).toInt())
    .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
