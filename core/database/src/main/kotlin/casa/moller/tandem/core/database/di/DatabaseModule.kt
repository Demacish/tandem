package casa.moller.tandem.core.database.di

import android.content.Context
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.dao.AccountDao
import casa.moller.tandem.core.database.dao.HomeSectionDao
import casa.moller.tandem.core.database.dao.MealPlanDao
import casa.moller.tandem.core.database.dao.OutboxDao
import casa.moller.tandem.core.database.dao.RecipeDao
import casa.moller.tandem.core.database.dao.ReferenceDao
import casa.moller.tandem.core.database.dao.SyncStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the demo database (plan 6.2 "Demoläge"); the unqualified binding is the real one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DemoDatabase

/**
 * Provides [TandemDatabaseFactory], the real [TandemDatabase] with its DAOs, and the demo
 * database as `@DemoDatabase TandemDatabase` (take its DAOs from the instance). Files are opened
 * lazily on first use.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideFactory(@ApplicationContext context: Context): TandemDatabaseFactory = TandemDatabaseFactory.forFiles(context)

    @Provides
    @Singleton
    fun provideDatabase(factory: TandemDatabaseFactory): TandemDatabase = factory.get(DatabaseKind.REAL)

    @Provides
    @Singleton
    @DemoDatabase
    fun provideDemoDatabase(factory: TandemDatabaseFactory): TandemDatabase = factory.get(DatabaseKind.DEMO)

    @Provides
    fun provideAccountDao(database: TandemDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideRecipeDao(database: TandemDatabase): RecipeDao = database.recipeDao()

    @Provides
    fun provideReferenceDao(database: TandemDatabase): ReferenceDao = database.referenceDao()

    @Provides
    fun provideMealPlanDao(database: TandemDatabase): MealPlanDao = database.mealPlanDao()

    @Provides
    fun provideHomeSectionDao(database: TandemDatabase): HomeSectionDao = database.homeSectionDao()

    @Provides
    fun provideOutboxDao(database: TandemDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideSyncStateDao(database: TandemDatabase): SyncStateDao = database.syncStateDao()
}
