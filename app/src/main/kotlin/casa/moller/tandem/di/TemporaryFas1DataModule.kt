package casa.moller.tandem.di

import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.map
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.ServingsChoice
import casa.moller.tandem.core.model.server.Capabilities
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.toKotlinLocalDate

/**
 * TEMPORARY (Phase 1 foundation, grund lane): stand-in bindings for the Phase 1 repository
 * interfaces of core:data, so the app compiles and runs before the real implementations exist.
 * Recipes, Today and preferences are empty, the sync does nothing, and the server status is
 * derived from the session.
 *
 * The sync lane REPLACES this module: delete this file in the same change that binds the real
 * implementations (core:data / core:sync). Keeping both is a Hilt duplicate-binding error.
 */
@Module
@InstallIn(SingletonComponent::class)
object TemporaryFas1DataModule {
    @Provides
    @Singleton
    fun provideRecipeRepository(): RecipeRepository = EmptyRecipeRepository

    @Provides
    @Singleton
    fun provideHomeRepository(): HomeRepository = EmptyHomeRepository

    @Provides
    @Singleton
    fun provideSyncRepository(): SyncRepository = NoOpSyncRepository

    @Provides
    @Singleton
    fun providePreferencesRepository(): PreferencesRepository = DefaultPreferencesRepository

    @Provides
    @Singleton
    fun provideServerStatusRepository(sessions: SessionRepository): ServerStatusRepository = SessionServerStatusRepository(sessions)
}

private object EmptyRecipeRepository : RecipeRepository {
    override fun observeRecipes(query: RecipeQuery): Flow<List<RecipeOverview>> = flowOf(emptyList())

    override suspend fun searchServer(query: RecipeQuery): Outcome<List<RecipeOverview>> = Outcome.Success(emptyList())

    override fun observeRecipe(id: Long): Flow<RecipeDetailState> = flowOf(RecipeDetailState(recipeId = id))

    override suspend fun refreshRecipe(id: Long): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setAvailableOffline(id: Long, available: Boolean): Outcome<Unit> = Outcome.Success(Unit)

    override fun observeServingsChoice(id: Long): Flow<ServingsChoice?> = flowOf(null)

    override suspend fun setServingsChoice(id: Long, servings: Double?): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun markViewed(id: Long) = Unit

    override fun observeKeywords(): Flow<List<Keyword>> = flowOf(emptyList())
}

private object EmptyHomeRepository : HomeRepository {
    override fun observeSections(): Flow<List<HomeSection>> = flowOf(emptyList())

    override fun observeUpcomingMeals(): Flow<UpcomingMeals> = flow {
        emit(UpcomingMeals(today = java.time.LocalDate.now().toKotlinLocalDate()))
    }

    override suspend fun refresh(): Outcome<Unit> = Outcome.Success(Unit)
}

private object NoOpSyncRepository : SyncRepository {
    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle).asStateFlow()

    override fun requestSync(reason: SyncReason) = Unit

    override fun firstSync(): Flow<SyncProgress> = flowOf(SyncProgress(SyncPhase.DONE))
}

private object DefaultPreferencesRepository : PreferencesRepository {
    override fun observeUserPreferences(): Flow<UserPreferences> = flowOf(UserPreferences.Default)
}

private class SessionServerStatusRepository(private val sessions: SessionRepository) : ServerStatusRepository {
    override fun observeStatus(): Flow<ServerStatus> = sessions.session.map { session ->
        when (session) {
            is Session.LoggedIn -> ServerStatus(
                version = session.serverVersion,
                compatibility = session.compatibility,
                capabilities = Capabilities.None,
            )

            Session.Demo -> ServerStatus.Demo

            else -> ServerStatus.Unknown
        }
    }

    override fun observeVersionChanges(): Flow<ServerVersionChange> = emptyFlow()

    override suspend fun refresh(force: Boolean): Outcome<ServerStatus> = sessions.refreshServerInfo().map { info ->
        ServerStatus(version = info.version, compatibility = info.compatibility, capabilities = Capabilities.None)
    }
}
