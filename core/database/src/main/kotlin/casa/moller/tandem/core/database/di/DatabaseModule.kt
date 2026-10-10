package casa.moller.tandem.core.database.di

import android.content.Context
import androidx.room3.Room
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.dao.SyncStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the single [TandemDatabase] and its DAOs. Opened lazily on first use. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TandemDatabase =
        Room.databaseBuilder(context, TandemDatabase::class.java, TandemDatabase.FILE_NAME).build()

    @Provides
    fun provideSyncStateDao(database: TandemDatabase): SyncStateDao = database.syncStateDao()
}
