package casa.moller.tandem.core.common.crash

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default uncaught-exception handler that saves a redacted crash log ([CrashLogFormatter]) in
 * [store], then hands the throwable to the [previous] handler (on Android the platform's, which
 * logs the crash and ends the process), exactly as if this handler was not there.
 *
 * It can never crash or loop itself:
 * - writing is wrapped in a catch-all; whatever goes wrong there, [previous] still runs;
 * - only the first crash of the process is written: a second throwable (another thread, or the
 *   writing itself failing in a way that ends up here again) goes straight to [previous];
 * - [install] never puts a second [CrashLogHandler] in front of an existing one, and [previous]
 *   is never this handler itself.
 */
class CrashLogHandler internal constructor(
    private val store: CrashLogStore,
    private val environment: () -> CrashLogEnvironment,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {
    private val handled = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (handled.compareAndSet(false, true)) {
            try {
                val now = clock()
                store.write(CrashLogFormatter.format(environment(), thread.name, throwable, now), now)
            } catch (e: Throwable) {
                // Nothing to do: the crash itself matters more than its log.
            }
        }
        val next = previous
        if (next != null && next !== this) {
            next.uncaughtException(thread, throwable)
        } else {
            // What the JVM does without a default handler (ThreadGroup.uncaughtException).
            try {
                System.err.print("Exception in thread \"" + thread.name + "\" ")
                throwable.printStackTrace()
            } catch (e: Throwable) {
                // stderr is gone; nothing left to tell.
            }
        }
    }

    companion object {
        /**
         * Installs a [CrashLogHandler] as the default uncaught-exception handler in front of the
         * current one. Does nothing if one is already installed (e.g. a second Application in a
         * Robolectric test run), so handlers never stack up. Returns the active handler.
         */
        @Synchronized
        fun install(store: CrashLogStore, environment: () -> CrashLogEnvironment): CrashLogHandler {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashLogHandler) return current
            return CrashLogHandler(store, environment, current).also { Thread.setDefaultUncaughtExceptionHandler(it) }
        }
    }
}
