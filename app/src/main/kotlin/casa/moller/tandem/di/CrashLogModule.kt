package casa.moller.tandem.di

import android.content.Context
import casa.moller.tandem.TandemApplication
import casa.moller.tandem.core.common.crash.CrashLogStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CrashLogModule {
    /** The same folder the crash handler writes to (TandemApplication). */
    @Provides
    @Singleton
    fun provideCrashLogStore(@ApplicationContext context: Context): CrashLogStore = TandemApplication.crashLogStore(context)
}
