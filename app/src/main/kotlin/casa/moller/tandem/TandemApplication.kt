package casa.moller.tandem

import android.app.Application
import android.content.Context
import android.os.Build
import casa.moller.tandem.core.common.crash.CrashLogEnvironment
import casa.moller.tandem.core.common.crash.CrashLogHandler
import casa.moller.tandem.core.common.crash.CrashLogStore
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class TandemApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First thing in the process (before content providers, Hilt and every activity): the
        // local crash log (plan 4.2 p7). It only installs a handler here; the folder is resolved
        // and written only when a crash happens. Nothing is ever sent anywhere.
        CrashLogHandler.install(crashLogStore(this)) { crashLogEnvironment() }
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
