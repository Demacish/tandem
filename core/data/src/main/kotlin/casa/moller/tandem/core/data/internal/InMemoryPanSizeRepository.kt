package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.data.recipe.PanSizeRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * [PanSizeRepository] in memory, keyed by the source (the active account, or the demo) and the
 * recipe, so another server's recipe with the same id never gets this tin. Nothing is stored on
 * disk (no Room table: plan "Fas 5" keeps the schema to the shopping lane); the screens keep
 * their own choice in their SavedStateHandle across process death.
 */
@Singleton
internal class InMemoryPanSizeRepository @Inject constructor(settings: SettingsRepository) : PanSizeRepository {
    private val chosen = MutableStateFlow<Map<Pair<String, Long>, Int>>(emptyMap())

    private val source: Flow<String> = settings.settings
        .map { if (it.demoMode) DEMO else it.activeAccountId.orEmpty() }
        .distinctUntilChanged()

    override fun observePanSize(recipeId: Long): Flow<Int?> =
        combine(source, chosen) { source, chosen -> chosen[source to recipeId] }.distinctUntilChanged()

    override suspend fun setPanSize(recipeId: Long, diameter: Int?) {
        val key = source.first() to recipeId
        chosen.update { if (diameter == null || diameter <= 0) it - key else it + (key to diameter) }
    }

    private companion object {
        const val DEMO = "demo"
    }
}
