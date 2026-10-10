package casa.moller.tandem.di

import android.os.Build
import casa.moller.tandem.BuildConfig
import casa.moller.tandem.core.common.AppBuildInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppBuildInfoModule {
    @Provides
    @Singleton
    fun provideAppBuildInfo(): AppBuildInfo = AppBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        distribution = BuildConfig.FLAVOR,
        isDebug = BuildConfig.DEBUG,
        sdkInt = Build.VERSION.SDK_INT,
    )
}
