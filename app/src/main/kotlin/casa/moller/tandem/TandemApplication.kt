package casa.moller.tandem

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import casa.moller.tandem.assistant.AssistantFunctionStates
import casa.moller.tandem.assistant.AssistantHost
import casa.moller.tandem.core.common.crash.CrashLogEnvironment
import casa.moller.tandem.core.common.crash.CrashLogHandler
import casa.moller.tandem.core.common.crash.CrashLogStore
import casa.moller.tandem.core.data.assistant.AssistantActions
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.sync.SyncStarter
import casa.moller.tandem.core.sync.security.DefaultAppLock
import casa.moller.tandem.core.watch.WatchLinkStarter
import casa.moller.tandem.diagnostics.ColdStart
import casa.moller.tandem.feature.widgets.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class TandemApplication :
    Application(),
    Configuration.Provider,
    AssistantHost {

    /** Creates the sync's @HiltWorker workers (core:sync); WorkManager starts on demand with it. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncStarter: SyncStarter

    /** Every module's notification channels (core:timer, core:sync), created once per start. */
    @Inject
    lateinit var channelInstallers: Set<@JvmSuppressWildcards NotificationChannelInstaller>

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /** Fas 3 (widgets lane): redraws the home-screen widgets when their data changes. */
    @Inject
    lateinit var widgetUpdater: WidgetUpdater

    /** Fas 5 (wear lane): the timers mirrored to a paired watch and the watch token's upkeep. */
    @Inject
    lateinit var watchLink: WatchLinkStarter

    /** Fas 5 (las lane, plan §5.5): Mer → Lås appen follows the process lifecycle from the start. */
    @Inject
    lateinit var appLock: DefaultAppLock

    // --- Fas 5 (funktioner lane, plan §8.7): the App Functions (assistant/), off until chosen. ---
    /** What the App Functions' service calls; a Provider, so nothing is built at startup for it. */
    @Inject
    lateinit var assistantActionsProvider: Provider<AssistantActions>

    /** The functions' enabled state in the system follows "Låt assistenten använda Tandem". */
    @Inject
    lateinit var assistantFunctionStates: AssistantFunctionStates

    override val assistantActions: AssistantActions
        get() = assistantActionsProvider.get()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First thing in the process (before content providers, Hilt and every activity): the
        // local crash log (plan 4.2 p7). It only installs a handler here; the folder is resolved
        // and written only when a crash happens. Nothing is ever sent anywhere.
        CrashLogHandler.install(crashLogStore(this)) { crashLogEnvironment() }
    }

    override fun onCreate() {
        super.onCreate()
        // Phase 1b: tells a launch from a process started for background work (cold start timing).
        ColdStart.onApplicationCreated()
        // Fas 5 (las lane): first, so a lock that is on is in place before any screen is shown.
        appLock.start()
        // Phase 1 data layer (core:sync): image loader, account mirror, periodic sync, and a sync
        // request whenever the app comes to the foreground.
        syncStarter.start()
        widgetUpdater.start()
        watchLink.start()
        assistantFunctionStates.start()
        // Phase 1b (plan 8.7): the channels exist before the first notification, so the system
        // settings and Mer → Behörigheter show them from the first start. Off the main thread
        // (binder calls, not needed for the first frame); a failure only delays them to the first
        // notification, which creates its channel again.
        applicationScope.launch {
            channelInstallers.forEach { installer -> runCatching { installer.createChannels() } }
        }
    }

    companion object {
        /** The crash logs in `noBackupFilesDir`, so they are never part of a backup. */
        fun crashLogStore(context: Context): CrashLogStore = CrashLogStore({ File(context.noBackupFilesDir, CrashLogStore.DIRECTORY_NAME) })

        /** App version, build type, Android version and device model; nothing about the user. */
        fun crashLogEnvironment(): CrashLogEnvironment = CrashLogEnvironment(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = BuildConfig.BUILD_TYPE,
            distribution = BuildConfig.FLAVOR,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
        )
    }
}
