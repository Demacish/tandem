package casa.moller.tandem

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import casa.moller.tandem.core.common.crash.CrashLogEnvironment
import casa.moller.tandem.core.common.crash.CrashLogHandler
import casa.moller.tandem.core.common.crash.CrashLogStore
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.sync.SyncStarter
import casa.moller.tandem.diagnostics.ColdStart
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class TandemApplication :
    Application(),
    Configuration.Provider {

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
        // Phase 1 data layer (core:sync): image loader, account mirror, periodic sync, and a sync
        // request whenever the app comes to the foreground.
        syncStarter.start()
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
