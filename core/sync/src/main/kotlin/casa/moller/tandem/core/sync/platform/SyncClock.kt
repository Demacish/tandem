package casa.moller.tandem.core.sync.platform

import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate

/**
 * Time for the sync. "Today" is the device's calendar day: Tandoor's API does not tell its time
 * zone, and in practice phone and server share it (plan 3.4). Tests use a fixed clock.
 */
internal interface SyncClock {
    /** Wall-clock time in epoch milliseconds. */
    fun nowMillis(): Long

    /** The current calendar day. */
    fun today(): LocalDate

    /** Emits [today] now and again after every midnight. */
    fun days(): Flow<LocalDate>

    /** Emits now and then every [periodMillis], so that time-based pauses can run out on screen. */
    fun ticks(periodMillis: Long): Flow<Unit>
}

internal class SystemSyncClock @Inject constructor() : SyncClock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun today(): LocalDate = java.time.LocalDate.now().toKotlinLocalDate()

    override fun days(): Flow<LocalDate> = flow {
        while (true) {
            emit(today())
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(MIN_WAIT_MILLIS) + MIDNIGHT_MARGIN_MILLIS)
        }
    }.distinctUntilChanged()

    override fun ticks(periodMillis: Long): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(periodMillis)
        }
    }

    private companion object {
        const val MIN_WAIT_MILLIS = 1_000L

        /** Wake up just after midnight, never just before it. */
        const val MIDNIGHT_MARGIN_MILLIS = 500L
    }
}
