package casa.moller.tandem.feature.scan.barcode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.scan.BarcodeLookup
import casa.moller.tandem.core.domain.scan.FoodMatcher
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.scan.BarcodeLookupResult
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.MatchedFood
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.feature.scan.ScanMatcher
import casa.moller.tandem.feature.scan.ScanSaver
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.scan.session.ScanSession
import casa.moller.tandem.feature.scan.session.ScanSessionStore
import casa.moller.tandem.feature.scan.ui.FoodPickerUi
import casa.moller.tandem.feature.scan.ui.ScanRowStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Where the lookup of a scanned code stands (#27). */
enum class BarcodeLookupState {
    /** Being looked up (learned codes first, then Open Food Facts). */
    LOOKING_UP,

    /** A learned code (`FOOD_ALIAS`): the food is known. */
    KNOWN,

    /** Open Food Facts knows the product; its name suggested a food (or none fitted). */
    PRODUCT,

    /** Open Food Facts does not know the code: the user picks or creates the food. */
    NOT_FOUND,

    /** No connection: the user picks the food (the code is learned when saved). */
    OFFLINE,

    /** Any other failure: the user picks the food. */
    FAILED,
}

/**
 * One scanned package.
 *
 * @property productName the Open Food Facts name (with brand), shown above the food.
 * @property suggestions the foods the product name suggests, for one-tap picks.
 */
data class BarcodeRow(
    val item: ScanReviewItem,
    val lookup: BarcodeLookupState,
    val productName: String? = null,
    val suggestions: List<Food> = emptyList(),
) {
    val status: ScanRowStatus
        get() = when {
            lookup == BarcodeLookupState.LOOKING_UP -> ScanRowStatus.LOOKING_UP
            item.food != null -> ScanRowStatus.of(item)
            lookup == BarcodeLookupState.NOT_FOUND -> ScanRowStatus.NOT_FOUND
            lookup == BarcodeLookupState.OFFLINE -> ScanRowStatus.OFFLINE
            lookup == BarcodeLookupState.FAILED -> ScanRowStatus.FAILED
            else -> ScanRowStatus.UNKNOWN
        }
}

/**
 * The barcode scanner (#27, plan §8.8 "Streckkod").
 *
 * @property rows the packages scanned in this session, newest first.
 * @property selectedKey the row the card shows (the newest unless the user chose another).
 * @property defaultLocationId the scanner's place (from Hemma) or the household's first.
 * @property picker the food picker for the selected row, when open.
 */
data class BarcodeScanUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val demo: Boolean = false,
    val locations: List<InventoryLocation> = emptyList(),
    val defaultLocationId: Long? = null,
    val rows: List<BarcodeRow> = emptyList(),
    val selectedKey: String? = null,
    val picker: FoodPickerUi? = null,
    val saving: Boolean = false,
    val today: LocalDate = LocalDate(2000, 1, 1),
) {
    val selected: BarcodeRow?
        get() = rows.firstOrNull { it.item.key == selectedKey } ?: rows.firstOrNull()

    val readyCount: Int
        get() = rows.count { it.item.ready }

    /** The place of [row] (its own, or the default). */
    fun locationOf(row: BarcodeRow): InventoryLocation? = locations.firstOrNull { it.id == (row.item.locationId ?: defaultLocationId) }
}

sealed interface BarcodeScanAction {
    /** The camera saw [code] (every frame; repeats within 3 s are ignored, a later one counts one more package). */
    data class CodeScanned(val code: String) : BarcodeScanAction

    /** The codes ML Kit read in a picked photo (empty = none found). */
    data class ImageRead(val codes: List<String>) : BarcodeScanAction

    /** The picked photo could not be read. */
    data object ImageFailed : BarcodeScanAction

    data class Select(val key: String) : BarcodeScanAction

    data class SetLocation(val key: String, val locationId: Long) : BarcodeScanAction

    data class SetExpiry(val key: String, val choice: ExpiryChoice) : BarcodeScanAction

    /** One of the suggestions (or a search result) for the row. */
    data class ChooseFood(val key: String, val food: Food) : BarcodeScanAction

    data class OpenPicker(val key: String) : BarcodeScanAction

    data class PickerQuery(val text: String) : BarcodeScanAction

    /** A new food Tandoor creates by [name] when the entry is saved. */
    data class CreateFood(val key: String, val name: String) : BarcodeScanAction

    data object ClosePicker : BarcodeScanAction

    data class Remove(val key: String) : BarcodeScanAction

    /** "Lägg i Kyl": the only row straight into the pantry (several rows go through the review). */
    data object SaveToPantry : BarcodeScanAction

    /** "Lägg på inköpslistan": the selected row. */
    data object AddToShoppingList : BarcodeScanAction

    /** "Granska n varor": the shared review list. */
    data object Review : BarcodeScanAction
}

sealed interface BarcodeScanEffect {
    /** A new code was taken: vibrate and show it. */
    data object Scanned : BarcodeScanEffect

    data class Saved(val count: Int, val place: String?) : BarcodeScanEffect

    data class AddedToList(val count: Int) : BarcodeScanEffect

    data class OpenReview(val sessionId: String) : BarcodeScanEffect

    data object NoCodeInImage : BarcodeScanEffect

    data object ImageUnreadable : BarcodeScanEffect

    data class Failed(val error: TandemError) : BarcodeScanEffect
}

/**
 * The barcode scanner (#27): continuous scanning with de-duplication; a learned code (`FOOD_ALIAS`)
 * goes straight to its food, an unknown one to Open Food Facts (only the code is sent, by
 * core:data `BarcodeLookup`), whose product name suggests the space's foods; the user picks or
 * creates one and the code is learned on save, so the other phone recognises it. One row goes
 * straight into the pantry (place and expiry quick picks: at most 3 taps, plan §10 "Klart när") or
 * on the shopping list; several go through the shared review list. The rows live in
 * [ScanSessionStore] so the review edits the same ones.
 */
@HiltViewModel(assistedFactory = BarcodeScanViewModel.Factory::class)
class BarcodeScanViewModel @AssistedInject internal constructor(
    @Assisted private val key: BarcodeScanKey,
    private val savedStateHandle: SavedStateHandle,
    private val store: ScanSessionStore,
    private val matcher: ScanMatcher,
    private val lookup: BarcodeLookup,
    private val saver: ScanSaver,
    private val pantry: PantryRepository,
    permissions: SpacePermissionsRepository,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: BarcodeScanKey): BarcodeScanViewModel
    }

    private data class Lookup(val state: BarcodeLookupState, val productName: String? = null, val suggestions: List<Food> = emptyList())

    private data class Local(
        val selectedKey: String? = null,
        val pickerKey: String? = null,
        val pickerQuery: String = "",
        val saving: Boolean = false,
    )

    private val effectChannel = Channel<BarcodeScanEffect>(Channel.BUFFERED)
    val effects: Flow<BarcodeScanEffect> = effectChannel.receiveAsFlow()

    private val sessionId = MutableStateFlow(openSession())
    private val lookups = MutableStateFlow<Map<String, Lookup>>(emptyMap())
    private val local = MutableStateFlow(Local())
    private val foods = MutableStateFlow<List<Food>>(emptyList())
    private val recentCodes = mutableMapOf<String, Long>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val session: Flow<ScanSession?> = sessionId.flatMapLatest { store.observe(it) }

    val uiState: StateFlow<BarcodeScanUiState> = combine(
        session,
        lookups,
        local,
        combine(pantry.observeLocations(), permissions.permissions, foods) { locations, rights, all -> Triple(locations, rights, all) },
    ) { current, lookupMap, ui, (locations, rights, all) ->
        buildState(current, lookupMap, ui, locations, rights, all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BarcodeScanUiState())

    init {
        viewModelScope.launch { foods.value = matcher.foods() }
        viewModelScope.launch { if (pantry.observeLocations().first().isEmpty()) pantry.refresh() }
        // The review saved this session's rows: say so and start a new session (the scanner stays open).
        viewModelScope.launch {
            session.collect { current ->
                val saved = current?.saved ?: return@collect
                store.remove(current.id)
                sessionId.value = newSession()
                lookups.value = emptyMap()
                local.value = Local()
                effectChannel.send(BarcodeScanEffect.Saved(saved, null))
            }
        }
    }

    fun onAction(action: BarcodeScanAction) {
        when (action) {
            is BarcodeScanAction.CodeScanned -> scanned(action.code)

            is BarcodeScanAction.ImageRead -> if (action.codes.isEmpty()) {
                effectChannel.trySend(BarcodeScanEffect.NoCodeInImage)
            } else {
                action.codes.forEach(::scanned)
            }

            BarcodeScanAction.ImageFailed -> effectChannel.trySend(BarcodeScanEffect.ImageUnreadable)

            is BarcodeScanAction.Select -> local.update { it.copy(selectedKey = action.key, pickerKey = null) }

            is BarcodeScanAction.SetLocation -> edit(action.key) { it.copy(locationId = action.locationId) }

            is BarcodeScanAction.SetExpiry -> edit(action.key) { it.copy(expires = action.choice.dateFrom(today())) }

            is BarcodeScanAction.ChooseFood -> choose(action.key, MatchedFood(action.food.id, action.food.name, FoodMatchVia.MANUAL))

            is BarcodeScanAction.CreateFood -> choose(action.key, MatchedFood(null, action.name.trim(), FoodMatchVia.NEW))

            is BarcodeScanAction.OpenPicker -> local.update { it.copy(selectedKey = action.key, pickerKey = action.key, pickerQuery = "") }

            is BarcodeScanAction.PickerQuery -> local.update { it.copy(pickerQuery = action.text) }

            BarcodeScanAction.ClosePicker -> local.update { it.copy(pickerKey = null, pickerQuery = "") }

            is BarcodeScanAction.Remove -> remove(action.key)

            BarcodeScanAction.SaveToPantry -> saveToPantry()

            BarcodeScanAction.AddToShoppingList -> addToShoppingList()

            BarcodeScanAction.Review -> effectChannel.trySend(BarcodeScanEffect.OpenReview(sessionId.value))
        }
    }

    private fun scanned(raw: String) {
        val code = raw.trim()
        if (code.isEmpty() || !code.all(Char::isDigit)) return
        val now = clock.now().toEpochMilliseconds()
        val items = store.get(sessionId.value)?.items.orEmpty()
        val recent = recentCodes[code]?.let { now - it < RESCAN_AFTER_MILLIS } == true
        recentCodes[code] = now
        if (recent) return
        // Fas S review (fix-ui): the same code again after the camera looked away is one more
        // package (three cans of tomatoes), not nothing: the row counts it, with a vibration.
        items.firstOrNull { it.barcode == code }?.let { existing ->
            edit(existing.key) { it.copy(amount = (it.amount?.takeIf { amount -> amount > 0.0 } ?: 1.0) + 1.0) }
            local.update { it.copy(selectedKey = existing.key, pickerKey = null) }
            effectChannel.trySend(BarcodeScanEffect.Scanned)
            return
        }
        val item = ScanReviewItem(key = ScanMatcher.newKey(), text = code, source = ScanSource.BARCODE, barcode = code, learn = true)
        store.update(sessionId.value) { it.copy(items = it.items + item) }
        lookups.update { it + (item.key to Lookup(BarcodeLookupState.LOOKING_UP)) }
        local.update { it.copy(selectedKey = item.key, pickerKey = null) }
        effectChannel.trySend(BarcodeScanEffect.Scanned)
        viewModelScope.launch { resolve(item.key, code) }
    }

    /** Learned code first (no request), then Open Food Facts, then the product name against the stored foods. */
    private suspend fun resolve(itemKey: String, code: String) {
        val all = foods.value.ifEmpty { matcher.foods().also { foods.value = it } }
        matcher.learned(code, all)?.let { known ->
            setFood(itemKey, known, learn = false)
            lookups.update { it + (itemKey to Lookup(BarcodeLookupState.KNOWN)) }
            return
        }
        val result = when (val outcome = lookup.lookup(code)) {
            is Outcome.Success -> outcome.value

            is Outcome.Failure -> {
                val state = if (outcome.error.isConnectivityProblem) BarcodeLookupState.OFFLINE else BarcodeLookupState.FAILED
                lookups.update { it + (itemKey to Lookup(state)) }
                return
            }
        }
        // An online lookup also re-reads the learned codes: one another phone just learned counts.
        matcher.learned(code, all)?.let { known ->
            setFood(itemKey, known, learn = false)
            lookups.update { it + (itemKey to Lookup(BarcodeLookupState.KNOWN)) }
            return
        }
        when (result) {
            is BarcodeLookupResult.Found -> {
                val product = result.product
                val name = listOfNotNull(product.name, product.brand).joinToString(" ").ifBlank { null }
                val searchText = product.name.orEmpty()
                val suggestions = FoodMatcher.rank(searchText, all, ScanMatcher.SUGGESTIONS).map { it.food }
                matcher.suggested(searchText, all)?.let { setFood(itemKey, it, learn = true, text = product.name) }
                    ?: setText(itemKey, product.name)
                lookups.update { it + (itemKey to Lookup(BarcodeLookupState.PRODUCT, name, suggestions)) }
            }

            BarcodeLookupResult.NotFound -> lookups.update { it + (itemKey to Lookup(BarcodeLookupState.NOT_FOUND)) }
        }
    }

    private fun setFood(itemKey: String, food: MatchedFood, learn: Boolean, text: String? = null) = edit(itemKey) { item ->
        // The user may have chosen meanwhile: never overwrite that.
        if (item.food != null) item else item.copy(food = food, learn = learn, text = text?.takeIf { it.isNotBlank() } ?: item.text)
    }

    private fun setText(itemKey: String, text: String?) = edit(itemKey) { item ->
        if (text.isNullOrBlank()) item else item.copy(text = text)
    }

    private fun choose(itemKey: String, food: MatchedFood) {
        if (food.name.isBlank()) return
        edit(itemKey) { it.copy(food = food, learn = true, include = true) }
        local.update { it.copy(pickerKey = null, pickerQuery = "") }
    }

    private fun edit(itemKey: String, transform: (ScanReviewItem) -> ScanReviewItem) {
        store.update(sessionId.value) { session ->
            session.copy(items = session.items.map { if (it.key == itemKey) transform(it) else it })
        }
    }

    private fun remove(itemKey: String) {
        store.update(sessionId.value) { session -> session.copy(items = session.items.filterNot { it.key == itemKey }) }
        lookups.update { it - itemKey }
        local.update { if (it.selectedKey == itemKey) Local() else it }
    }

    private fun saveToPantry() {
        val state = uiState.value
        val row = state.rows.singleOrNull() ?: return onAction(BarcodeScanAction.Review)
        if (state.saving || state.readOnly || !row.item.ready) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = saver.toPantry(listOf(row.item), state.defaultLocationId)
            local.update { it.copy(saving = false) }
            if (row.item.key in result.savedKeys) {
                remove(row.item.key)
                effectChannel.send(BarcodeScanEffect.Saved(1, state.locationOf(row)?.name))
            }
            result.error?.let { effectChannel.send(BarcodeScanEffect.Failed(it)) }
        }
    }

    private fun addToShoppingList() {
        val state = uiState.value
        val row = state.selected ?: return
        if (state.saving || state.readOnly || !row.item.ready) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = saver.toShoppingList(listOf(row.item))
            local.update { it.copy(saving = false) }
            when (outcome) {
                is Outcome.Success -> {
                    remove(row.item.key)
                    effectChannel.send(BarcodeScanEffect.AddedToList(outcome.value))
                }

                is Outcome.Failure -> effectChannel.send(BarcodeScanEffect.Failed(outcome.error))
            }
        }
    }

    private fun buildState(
        session: ScanSession?,
        lookupMap: Map<String, Lookup>,
        ui: Local,
        locations: List<InventoryLocation>,
        rights: SpacePermissions,
        all: List<Food>,
    ): BarcodeScanUiState {
        val rows = session?.items.orEmpty().asReversed().map { item ->
            val found = lookupMap[item.key] ?: Lookup(BarcodeLookupState.LOOKING_UP)
            BarcodeRow(item, found.state, found.productName, found.suggestions)
        }
        val pickerRow = rows.firstOrNull { it.item.key == ui.pickerKey }
        return BarcodeScanUiState(
            loading = false,
            readOnly = rights.readOnly,
            demo = rights.isDemo,
            locations = locations,
            defaultLocationId = (key.locationId?.takeIf { id -> locations.any { it.id == id } }) ?: locations.firstOrNull()?.id,
            rows = rows,
            selectedKey = ui.selectedKey,
            picker = pickerRow?.let { row ->
                FoodPickerUi(
                    query = ui.pickerQuery,
                    results = FoodMatcher.search(ui.pickerQuery, all, PICKER_RESULTS),
                    suggestions = row.suggestions,
                )
            },
            saving = ui.saving,
            today = today(),
        )
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    /** The session in the saved state (rotation), or a new one after process death. */
    private fun openSession(): String = savedStateHandle.get<String>(SESSION)?.takeIf { store.get(it) != null } ?: newSession()

    private fun newSession(): String = store.create(ScanSource.BARCODE, key.locationId).also { savedStateHandle[SESSION] = it }

    override fun onCleared() {
        // Leaving the scanner drops its unsaved rows (the review above it is always closed first).
        store.remove(sessionId.value)
    }

    private companion object {
        const val SESSION = "barcode_session"
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** The same code again (the camera sees it in every frame) is ignored this long after it was last seen. */
        const val RESCAN_AFTER_MILLIS = 3_000L
        const val PICKER_RESULTS = 8
    }
}
