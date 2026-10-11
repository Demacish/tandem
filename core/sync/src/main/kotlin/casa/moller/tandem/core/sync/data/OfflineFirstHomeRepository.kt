package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.mapper.toHomeSections
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.database.relation.RecipeListItem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.engine.SyncEngine
import casa.moller.tandem.core.sync.platform.SyncClock
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * [HomeRepository] on Room (plan 6.2 "Idag", 3.3 "Hemsektioner"):
 *
 * - **Pinned** comes straight from the recipes the user pinned on this device (by name).
 * - **Often cooked, new, not cooked lately** are the cached answers of the server queries
 *   (`home_section`, written by the sync and by [refresh]), so Today shows the last result offline.
 * - **Recently viewed** merges the recipes opened on this device (newest first, also offline and
 *   before the view log reached the server) with the server's list (all devices), without
 *   duplicates.
 * - **Upcoming meals** reads the stored meal plan window; "today" follows the device's calendar
 *   day and changes at midnight while collected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstHomeRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val engine: SyncEngine,
    private val clock: SyncClock,
) : HomeRepository {

    override fun observeSections(): Flow<List<HomeSection>> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> sections(source)
                ActiveSource.Loading -> emptyFlow<List<HomeSection>>()
                ActiveSource.None -> flowOf(emptyList<HomeSection>())
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private fun sections(source: ActiveSource.Stored): Flow<List<HomeSection>> {
        val database = databases.get(source.kind)
        val recipes = database.recipeDao()
        return combine(
            recipes.observePinned(source.accountId),
            database.homeSectionDao().observeSectionRows(source.accountId),
            recipes.observeRecentlyViewedLocal(source.accountId, SyncSchedule.HOME_SECTION_SIZE),
        ) { pinned, cached, viewedLocally ->
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
            val fromServer = cached.toHomeSections().associateBy { it.type }
            val recentlyViewed = (viewedLocally.map { it.toModel() } + fromServer[HomeSectionType.RECENTLY_VIEWED]?.recipes.orEmpty())
                .distinctBy { it.id }
                .take(SyncSchedule.HOME_SECTION_SIZE)
            val pinnedByName = pinned.sortedWith(compareBy<RecipeListItem, String>(collator) { it.overview.name }).map { it.toModel() }
            HomeSectionType.entries.mapNotNull { type ->
                val recipes = when (type) {
                    HomeSectionType.PINNED -> pinnedByName
                    HomeSectionType.RECENTLY_VIEWED -> recentlyViewed
                    else -> fromServer[type]?.recipes.orEmpty()
                }
                if (recipes.isEmpty()) null else HomeSection(type, recipes)
            }
        }
    }

    override fun observeUpcomingMeals(): Flow<UpcomingMeals> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> clock.days().flatMapLatest { today -> upcoming(source, today) }
                ActiveSource.Loading -> emptyFlow<UpcomingMeals>()
                ActiveSource.None -> clock.days().map { today -> UpcomingMeals(today = today) }
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private fun upcoming(source: ActiveSource.Stored, today: LocalDate): Flow<UpcomingMeals> {
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        return databases.get(source.kind).mealPlanDao()
            .observeRange(source.accountId, today.toString(), tomorrow.toString())
            .map { rows ->
                val meals = rows.map { it.toModel() }
                UpcomingMeals(
                    today = today,
                    todayMeals = meals.filter { it.isOn(today) },
                    tomorrowMeals = meals.filter { it.isOn(tomorrow) },
                )
            }
    }

    override suspend fun refresh(): Outcome<Unit> = when (val source = sources.current()) {
        is ActiveSource.Remote -> engine.refreshHome(source)
        ActiveSource.Demo -> Outcome.Success(Unit)
        ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
    }
}
