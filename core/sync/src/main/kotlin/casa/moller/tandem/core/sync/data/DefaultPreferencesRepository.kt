package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The active account's Tandoor preferences from Room (stored by the sync with the small tables,
 * plan 3.2), or [UserPreferences.Default] until they were fetched. Demo mode reads the demo data's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultPreferencesRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
) : PreferencesRepository {

    override fun observeUserPreferences(): Flow<UserPreferences> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> databases.get(source.kind).referenceDao()
                    .observeUserPreference(source.accountId)
                    .map { it?.toModel() ?: UserPreferences.Default }

                ActiveSource.Loading, ActiveSource.None -> flowOf(UserPreferences.Default)
            }
        }
        .distinctUntilChanged()
}
