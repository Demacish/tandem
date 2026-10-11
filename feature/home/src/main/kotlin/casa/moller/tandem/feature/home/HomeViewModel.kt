package casa.moller.tandem.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncReason
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the Today tab (plan 6.2 "Idag"): tonight and tomorrow from the stored meal plan, the
 * recipe carousels, and pull to refresh. Offline first: everything comes from
 * [HomeRepository]'s Room-backed flows, so the last fetched content stays on screen offline.
 *
 * Requests (plan 4.1): one [HomeRepository.refresh] when the tab is first shown, then only when
 * the user pulls to refresh (which also asks the sync for a [SyncReason.USER_REFRESH]).
 */
@HiltViewModel
class HomeViewModel internal constructor(
    private val homeRepository: HomeRepository,
    private val syncRepository: SyncRepository,
    /** The current hour of the day (0-23) on the device, for the greeting. */
    private val currentHour: () -> Int,
) : ViewModel() {

    @Inject
    constructor(homeRepository: HomeRepository, syncRepository: SyncRepository) : this(
        homeRepository = homeRepository,
        syncRepository = syncRepository,
        currentHour = { java.time.LocalTime.now().hour },
    )

    private val refreshes = MutableStateFlow(RefreshState())
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once (e.g. in a LaunchedEffect). */
    val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        homeRepository.observeUpcomingMeals(),
        homeRepository.observeSections(),
        syncRepository.status,
        refreshes,
    ) { meals, sections, sync, refreshState ->
        val (tonight, alsoToday) = meals.splitToday()
        HomeUiState(
            loading = false,
            dayPart = DayPart.of(currentHour()),
            today = meals.today,
            tonight = tonight,
            alsoToday = alsoToday,
            tomorrow = meals.tomorrowMeals.sortedBy { it.mealType.order },
            sections = sections.filter { it.recipes.isNotEmpty() },
            refreshing = refreshState.running,
            offline = refreshState.offline || sync.pause == SyncPause.OFFLINE,
            lastSynced = sync.lastSuccess,
            syncRunning = sync.running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState(dayPart = DayPart.of(currentHour())))

    init {
        refresh(userAsked = false)
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Refresh -> {
                syncRepository.requestSync(SyncReason.USER_REFRESH)
                refresh(userAsked = true)
            }

            // Navigation is handled by the route.
            is HomeAction.OpenRecipe, HomeAction.OpenRecipes, HomeAction.OpenWeek -> Unit
        }
    }

    private fun refresh(userAsked: Boolean) {
        if (refreshes.value.running) return
        refreshes.update { it.copy(running = true) }
        viewModelScope.launch {
            val result = homeRepository.refresh()
            val error = (result as? Outcome.Failure)?.error
            refreshes.update { RefreshState(running = false, offline = error?.isConnectivityProblem == true) }
            // A failed automatic refresh is quiet: the stored content and the offline notice say
            // enough. A refresh the user asked for gets an answer.
            if (error != null && userAsked) effectChannel.send(HomeEffect.RefreshFailed(error))
        }
    }

    private data class RefreshState(val running: Boolean = false, val offline: Boolean = false)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
