package casa.moller.tandem.feature.recipes.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.detail.isConnectionProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The recipe list (plan 6.2 "Receptlista", feature #3).
 *
 * - **Search:** the stored recipes are searched at once on every keystroke (offline FTS in the
 *   repository). When online and the query narrows the list, the server is searched too, after
 *   a short pause in typing (plan 4.1: no request per keystroke); its results that are not
 *   stored yet are added after the stored ones, without duplicates.
 * - **State:** the query survives rotation and process death ([SavedStateHandle]); the grid/list
 *   choice is remembered on the device ([RecipeListPreferences]).
 * - **Pull to refresh** asks for a sync (USER_REFRESH, never throttled) and shows the indicator
 *   until that sync has finished; a sync that fails says why (snackbar, plan 5.4).
 * - **Sync errors** other than "offline" and "log in again" (which have their own notices) are
 *   in [RecipeListUiState.syncError], so an empty list explains itself instead of "no recipes".
 * - **Coming back** to the screen keeps the shown list (and its scroll position): the inputs
 *   have no placeholder values, so a new subscription shows the cached state until Room answers.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipes: RecipeRepository,
    private val sync: SyncRepository,
    private val listPreferences: RecipeListPreferences,
    sessionRepository: SessionRepository,
) : ViewModel() {

    private val effectChannel = Channel<RecipeListEffect>(Channel.BUFFERED)

    /** One-off messages for a snackbar. */
    val effects: Flow<RecipeListEffect> = effectChannel.receiveAsFlow()

    private val query = MutableStateFlow(savedStateHandle.restoreQuery())
    private val serverResult = MutableStateFlow<ServerResult?>(null)
    private val serverSearch = MutableStateFlow(ServerSearch.IDLE)
    private val refreshing = MutableStateFlow(false)

    /** Bumped by pull to refresh, so the server is searched again for the same query. */
    private val searchGeneration = MutableStateFlow(0)

    private val isDemo: Flow<Boolean> = sessionRepository.session
        .map { it is Session.Demo }
        .onStart { emit(false) }
        .distinctUntilChanged()

    private val offline: Flow<Boolean> = sync.status.map { it.pause == SyncPause.OFFLINE }.distinctUntilChanged()

    private val syncInfo: Flow<SyncInfo> = sync.status
        .map { SyncInfo(offline = it.pause == SyncPause.OFFLINE, running = it.running, error = it.shownError()) }
        .distinctUntilChanged()

    // No placeholder (`onStart`) values on these inputs: when the screen subscribes again after
    // the stop timeout, `combine` waits for the real values and the cached state stays on screen.
    // A placeholder would replace the list with "loading" for a frame and lose the scroll position.
    private val localRecipes: Flow<List<RecipeOverview>> = query.flatMapLatest { recipes.observeRecipes(it) }

    private val listing: Flow<Listing> = combine(localRecipes, serverResult, query) { local, server, current ->
        when {
            server == null || server.query != current -> Listing(local, loaded = true, serverOnlyCount = 0)

            else -> {
                val stored = local.mapTo(HashSet()) { it.id }
                val extra = server.recipes.filter { it.id !in stored }.distinctBy { it.id }
                Listing(local + extra, loaded = true, serverOnlyCount = extra.size)
            }
        }
    }

    private val status: Flow<Status> = combine(
        syncInfo,
        refreshing,
        serverSearch,
        listPreferences.layout.distinctUntilChanged(),
    ) { info, isRefreshing, search, layout -> Status(info, isRefreshing, search, layout) }

    val uiState: StateFlow<RecipeListUiState> = combine(
        query,
        listing,
        recipes.observeKeywords(),
        status,
    ) { current, list, keywords, flags ->
        RecipeListUiState(
            query = current,
            recipes = list.recipes,
            loaded = list.loaded,
            keywords = keywords,
            layout = flags.layout,
            serverSearch = flags.serverSearch,
            serverOnlyCount = list.serverOnlyCount,
            offline = flags.sync.offline,
            refreshing = flags.refreshing,
            syncing = flags.sync.running,
            syncError = flags.sync.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeListUiState())

    init {
        viewModelScope.launch { searchServerWhileTyping() }
    }

    fun onAction(action: RecipeListAction) {
        when (action) {
            is RecipeListAction.Search -> updateQuery { it.copy(text = action.text) }

            is RecipeListAction.ToggleKeyword -> updateQuery {
                it.copy(keywordIds = if (action.id in it.keywordIds) it.keywordIds - action.id else it.keywordIds + action.id)
            }

            RecipeListAction.ClearKeywords -> updateQuery { it.copy(keywordIds = emptySet()) }

            RecipeListAction.ToggleQuick -> updateQuery {
                it.copy(maxTotalMinutes = if (it.maxTotalMinutes == null) QUICK_MAX_MINUTES else null)
            }

            RecipeListAction.ToggleGoodRating -> updateQuery { it.copy(minRating = if (it.minRating == null) GOOD_MIN_RATING else null) }

            RecipeListAction.ToggleOnlyOffline -> updateQuery { it.copy(onlyOffline = !it.onlyOffline) }

            is RecipeListAction.Sort -> updateQuery { it.copy(sort = action.sort) }

            RecipeListAction.ClearFilters -> updateQuery { RecipeQuery(sort = it.sort) }

            RecipeListAction.ToggleLayout -> viewModelScope.launch {
                listPreferences.setLayout(uiState.value.layout.toggled())
            }

            RecipeListAction.Refresh -> refresh()
        }
    }

    private fun updateQuery(change: (RecipeQuery) -> RecipeQuery) {
        query.update(change)
        savedStateHandle.saveQuery(query.value)
    }

    private suspend fun searchServerWhileTyping() {
        val requests = combine(query, isDemo, offline, searchGeneration) { current, demo, isOffline, generation ->
            SearchRequest(current, demo, isOffline, generation)
        }
        requests.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS).collectLatest { request -> searchServer(request) }
    }

    private suspend fun searchServer(request: SearchRequest) {
        val current = request.query
        if (request.demo || current.onlyOffline || current.isUnfiltered) {
            serverSearch.value = ServerSearch.IDLE
            return
        }
        if (request.offline) {
            serverSearch.value = ServerSearch.OFFLINE
            return
        }
        // The network came back (or flickered, e.g. Wi-Fi handing over to mobile data) while this
        // query's answer is already shown: asking again changes nothing (plan 4.1). Typing or
        // pull to refresh (a new generation) still asks.
        val answered = serverResult.value
        if (answered != null && answered.query == current && answered.generation == request.generation) {
            serverSearch.value = ServerSearch.DONE
            return
        }
        serverSearch.value = ServerSearch.SEARCHING
        when (val result = recipes.searchServer(current)) {
            is Outcome.Success -> {
                serverResult.value = ServerResult(current, request.generation, result.value)
                serverSearch.value = ServerSearch.DONE
            }

            is Outcome.Failure -> {
                serverSearch.value = if (result.error.isConnectionProblem()) ServerSearch.OFFLINE else ServerSearch.FAILED
            }
        }
    }

    private fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        sync.requestSync(SyncReason.USER_REFRESH)
        searchGeneration.update { it + 1 }
        if (sync.status.value.pause == SyncPause.OFFLINE) {
            effectChannel.trySend(RecipeListEffect.Message(R.string.recipes_refresh_offline))
        }
        viewModelScope.launch {
            // The sync may be ignored (offline, demo mode, already running): stop after a moment.
            val started = withTimeoutOrNull(SYNC_START_TIMEOUT_MILLIS) { sync.status.first { it.running } } != null
            val error = if (started) sync.status.first { !it.running }.shownError() else null
            refreshing.value = false
            // The user asked: a failed sync gets an answer (plan 5.4), not just a stopped spinner.
            if (error != null) effectChannel.trySend(RecipeListEffect.SyncFailed(error))
        }
    }

    private data class ServerResult(val query: RecipeQuery, val generation: Int, val recipes: List<RecipeOverview>)

    private data class Listing(val recipes: List<RecipeOverview>, val loaded: Boolean, val serverOnlyCount: Int)

    private data class SyncInfo(val offline: Boolean, val running: Boolean, val error: TandemError?)

    private data class Status(val sync: SyncInfo, val refreshing: Boolean, val serverSearch: ServerSearch, val layout: RecipeListLayout)

    private data class SearchRequest(val query: RecipeQuery, val demo: Boolean, val offline: Boolean, val generation: Int)

    internal companion object {
        /**
         * Why the last sync failed, when the list should say so: not while a sync runs, and not
         * for a missing network or a rejected login, which the offline notice and the shell's
         * "Log in again" banner already cover.
         */
        fun SyncStatus.shownError(): TandemError? {
            val error = lastError ?: return null
            return when {
                running -> null
                pause == SyncPause.OFFLINE || pause == SyncPause.UNAUTHORIZED -> null
                error == TandemError.NetworkUnavailable || error is TandemError.Unauthorized -> null
                else -> error
            }
        }

        /** Pause in typing before the server is asked (plan 4.1). */
        const val SEARCH_DEBOUNCE_MILLIS = 400L

        /** How long pull to refresh waits for the requested sync to start. */
        const val SYNC_START_TIMEOUT_MILLIS = 2_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private const val KEY_TEXT = "query_text"
        private const val KEY_KEYWORDS = "query_keywords"
        private const val KEY_QUICK = "query_quick"
        private const val KEY_RATING = "query_rating"
        private const val KEY_OFFLINE = "query_offline"
        private const val KEY_SORT = "query_sort"

        fun SavedStateHandle.restoreQuery(): RecipeQuery = RecipeQuery(
            text = get<String>(KEY_TEXT).orEmpty(),
            keywordIds = get<LongArray>(KEY_KEYWORDS)?.toSet().orEmpty(),
            maxTotalMinutes = QUICK_MAX_MINUTES.takeIf { get<Boolean>(KEY_QUICK) == true },
            minRating = GOOD_MIN_RATING.takeIf { get<Boolean>(KEY_RATING) == true },
            sort = RecipeSort.entries.firstOrNull { it.name == get<String>(KEY_SORT) } ?: RecipeSort.NAME,
            onlyOffline = get<Boolean>(KEY_OFFLINE) == true,
        )

        fun SavedStateHandle.saveQuery(query: RecipeQuery) {
            set(KEY_TEXT, query.text)
            set(KEY_KEYWORDS, query.keywordIds.toLongArray())
            set(KEY_QUICK, query.maxTotalMinutes != null)
            set(KEY_RATING, query.minRating != null)
            set(KEY_OFFLINE, query.onlyOffline)
            set(KEY_SORT, query.sort.name)
        }
    }
}
