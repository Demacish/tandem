package casa.moller.tandem.feature.scan.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.domain.scan.FoodMatcher
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.MatchedFood
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.feature.scan.ScanMatcher
import casa.moller.tandem.feature.scan.ScanSaver
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import casa.moller.tandem.feature.scan.session.ScanSession
import casa.moller.tandem.feature.scan.session.ScanSessionStore
import casa.moller.tandem.feature.scan.ui.FoodPickerUi
import casa.moller.tandem.feature.scan.ui.ScanFormat
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlin.time.Clock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The row being edited in the review (a sheet on phones, a pane beside the list on wide windows).
 *
 * @property amount the amount as typed; [amountError] when it is not a positive number.
 * @property canLearn the row has a barcode or a receipt line to learn (not the AI's fridge text).
 */
data class ReviewEdit(
    val key: String,
    val text: String,
    val barcode: String? = null,
    val food: MatchedFood? = null,
    val amount: String = "",
    val amountError: Boolean = false,
    val unit: String = "",
    val locationId: Long? = null,
    val expires: LocalDate? = null,
    val include: Boolean = true,
    val learn: Boolean = false,
    val canLearn: Boolean = false,
    val picker: FoodPickerUi = FoodPickerUi(),
)

/**
 * The shared review list (plan §8.8: "Allt visas i en granskningslista innan något sparas") for the
 * barcode, receipt and fridge-photo scans.
 *
 * @property missing the session is gone (process death): the screen offers to scan again.
 * @property rows the session's rows in scan order.
 * @property defaultLocationId where rows without their own place go.
 */
data class ScanReviewUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val source: ScanSource = ScanSource.RECEIPT,
    val rows: List<ScanReviewItem> = emptyList(),
    val locations: List<InventoryLocation> = emptyList(),
    val defaultLocationId: Long? = null,
    val readOnly: Boolean = false,
    val demo: Boolean = false,
    val saving: Boolean = false,
    val edit: ReviewEdit? = null,
    val today: LocalDate = LocalDate(2000, 1, 1),
    // ai-foto lane: the fridge photo's answer was cut off (the list may be incomplete).
    val truncated: Boolean = false,
) {
    /** Rows that will be saved: included and matched. */
    val readyCount: Int
        get() = rows.count { it.ready }

    /** Included rows still without a food (they are not saved). */
    val unmatchedCount: Int
        get() = rows.count { it.include && it.food == null }

    fun locationOf(item: ScanReviewItem): InventoryLocation? = locations.firstOrNull { it.id == (item.locationId ?: defaultLocationId) }

    /** The rows to check first (included, no food), the ready ones, then the skipped ones. */
    val toCheck: List<ScanReviewItem>
        get() = rows.filter { it.include && it.food == null }

    val ready: List<ScanReviewItem>
        get() = rows.filter { it.ready }

    val skipped: List<ScanReviewItem>
        get() = rows.filterNot { it.include }
}

sealed interface ScanReviewAction {
    data class Edit(val key: String) : ScanReviewAction

    data class EditQuery(val text: String) : ScanReviewAction

    data class EditPick(val food: Food) : ScanReviewAction

    data class EditCreate(val name: String) : ScanReviewAction

    data class EditAmount(val text: String) : ScanReviewAction

    data class EditUnit(val text: String) : ScanReviewAction

    data class EditLocation(val locationId: Long) : ScanReviewAction

    data class EditExpiry(val choice: ExpiryChoice) : ScanReviewAction

    data class EditLearn(val learn: Boolean) : ScanReviewAction

    data class EditInclude(val include: Boolean) : ScanReviewAction

    /** "Klar": the edit goes into the row (nothing is saved yet). */
    data object ApplyEdit : ScanReviewAction

    data object CancelEdit : ScanReviewAction

    data class ToggleInclude(val key: String) : ScanReviewAction

    data class Remove(val key: String) : ScanReviewAction

    /** The place for every row without its own. */
    data class SetDefaultLocation(val locationId: Long) : ScanReviewAction

    /** "Spara n varor": the only step that writes. */
    data object Save : ScanReviewAction
}

sealed interface ScanReviewEffect {
    /** Saved [count] entries; the review closes (the scanner below closes or starts over). */
    data class Saved(val count: Int) : ScanReviewEffect

    data class Failed(val error: TandemError) : ScanReviewEffect
}

/**
 * The shared review list (#27, #44, #45): every row editable (food, amount, unit, place, expiry),
 * skipped or removed, "lär dig" (learn the barcode or receipt line as a `FOOD_ALIAS`); nothing is
 * stored until "Spara". Works on the session in [ScanSessionStore] that the scanner made; the
 * ai-foto lane's fridge photo uses it the same way (source [ScanSource.FRIDGE], never learned, with
 * the reminder that the AI can be wrong).
 */
@HiltViewModel(assistedFactory = ScanReviewViewModel.Factory::class)
class ScanReviewViewModel @AssistedInject internal constructor(
    @Assisted private val key: ScanReviewKey,
    private val store: ScanSessionStore,
    private val matcher: ScanMatcher,
    private val saver: ScanSaver,
    private val pantry: PantryRepository,
    permissions: SpacePermissionsRepository,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: ScanReviewKey): ScanReviewViewModel
    }

    private data class Local(val edit: ReviewEdit? = null, val saving: Boolean = false, val done: Boolean = false)

    private val effectChannel = Channel<ScanReviewEffect>(Channel.BUFFERED)
    val effects: Flow<ScanReviewEffect> = effectChannel.receiveAsFlow()

    private val local = MutableStateFlow(Local())
    private val foods = MutableStateFlow<List<Food>>(emptyList())

    // The app's language: amounts are shown as typed there ("1,5"); both separators are read back.
    private val format = ScanFormat(Locale.getDefault())
    private var lastSession: ScanSession? = null

    val uiState: StateFlow<ScanReviewUiState> = combine(
        store.observe(key.sessionId),
        local,
        pantry.observeLocations(),
        permissions.permissions,
    ) { live, ui, locations, rights ->
        // After saving, the scanner drops the session: keep showing the last rows until the page closes.
        val session = live ?: lastSession.takeIf { ui.done }
        if (live != null) lastSession = live
        if (session == null) return@combine ScanReviewUiState(loading = false, missing = true)
        ScanReviewUiState(
            loading = false,
            source = session.source,
            rows = session.items,
            locations = locations,
            defaultLocationId = session.locationId?.takeIf { id -> locations.any { it.id == id } } ?: locations.firstOrNull()?.id,
            readOnly = rights.readOnly,
            demo = rights.isDemo,
            saving = ui.saving || ui.done,
            edit = ui.edit,
            today = today(),
            truncated = session.truncated,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ScanReviewUiState())

    init {
        viewModelScope.launch { foods.value = matcher.foods() }
        viewModelScope.launch { if (pantry.observeLocations().first().isEmpty()) pantry.refresh() }
    }

    fun onAction(action: ScanReviewAction) {
        when (action) {
            is ScanReviewAction.Edit -> startEdit(action.key)

            is ScanReviewAction.EditQuery -> editing { it.copy(picker = picker(it.text, action.text)) }

            is ScanReviewAction.EditPick -> editing { pick(it, MatchedFood(action.food.id, action.food.name, FoodMatchVia.MANUAL)) }

            is ScanReviewAction.EditCreate -> action.name.trim().takeIf { it.isNotEmpty() }?.let { name ->
                editing { pick(it, MatchedFood(null, name, FoodMatchVia.NEW)) }
            }

            is ScanReviewAction.EditAmount -> editing {
                it.copy(amount = action.text, amountError = action.text.isNotBlank() && format.parseAmount(action.text) == null)
            }

            is ScanReviewAction.EditUnit -> editing { it.copy(unit = action.text) }

            is ScanReviewAction.EditLocation -> editing { it.copy(locationId = action.locationId) }

            is ScanReviewAction.EditExpiry -> editing { it.copy(expires = action.choice.dateFrom(today())) }

            is ScanReviewAction.EditLearn -> editing { it.copy(learn = action.learn) }

            is ScanReviewAction.EditInclude -> editing { it.copy(include = action.include) }

            ScanReviewAction.ApplyEdit -> applyEdit()

            ScanReviewAction.CancelEdit -> local.update { it.copy(edit = null) }

            is ScanReviewAction.ToggleInclude -> change(action.key) { it.copy(include = !it.include) }

            is ScanReviewAction.Remove -> {
                store.update(key.sessionId) { session -> session.copy(items = session.items.filterNot { it.key == action.key }) }
                local.update { if (it.edit?.key == action.key) it.copy(edit = null) else it }
            }

            is ScanReviewAction.SetDefaultLocation -> store.update(key.sessionId) { it.copy(locationId = action.locationId) }

            ScanReviewAction.Save -> save()
        }
    }

    private fun startEdit(itemKey: String) {
        val item = store.get(key.sessionId)?.items?.firstOrNull { it.key == itemKey } ?: return
        val canLearn = item.source != ScanSource.FRIDGE && (item.barcode != null || item.text.isNotBlank())
        local.update {
            it.copy(
                edit = ReviewEdit(
                    key = item.key,
                    text = item.text,
                    barcode = item.barcode,
                    food = item.food,
                    amount = format.amountText(item.amount),
                    unit = item.unit.orEmpty(),
                    locationId = item.locationId,
                    expires = item.expires,
                    include = item.include,
                    learn = item.learn && canLearn,
                    canLearn = canLearn,
                    picker = picker(item.text, ""),
                ),
            )
        }
    }

    private fun picker(text: String, query: String): FoodPickerUi {
        val all = foods.value
        val suggestions = if (hasWords(text)) matcher.suggestions(text, all) else emptyList()
        return FoodPickerUi(query = query, results = FoodMatcher.search(query, all, PICKER_RESULTS), suggestions = suggestions)
    }

    private fun hasWords(text: String): Boolean = text.isNotBlank() && text.any(Char::isLetter)

    /** A food was picked: the row learns it (when it can) and is included again. */
    private fun pick(edit: ReviewEdit, food: MatchedFood): ReviewEdit =
        edit.copy(food = food, include = true, learn = edit.canLearn, picker = edit.picker.copy(query = ""))

    private fun editing(transform: (ReviewEdit) -> ReviewEdit) {
        local.update { state -> state.edit?.let { state.copy(edit = transform(it)) } ?: state }
    }

    private fun applyEdit() {
        val edit = local.value.edit ?: return
        if (edit.amountError) return
        change(edit.key) { item ->
            item.copy(
                food = edit.food,
                amount = format.parseAmount(edit.amount),
                unit = edit.unit.trim().ifEmpty { null },
                locationId = edit.locationId,
                expires = edit.expires,
                include = edit.include,
                learn = edit.learn && edit.canLearn,
            )
        }
        local.update { it.copy(edit = null) }
    }

    private fun change(itemKey: String, transform: (ScanReviewItem) -> ScanReviewItem) {
        store.update(key.sessionId) { session ->
            session.copy(items = session.items.map { if (it.key == itemKey) transform(it) else it })
        }
    }

    private fun save() {
        val state = uiState.value
        if (state.saving || state.readOnly || state.readyCount == 0) return
        local.update { it.copy(saving = true, edit = null) }
        viewModelScope.launch {
            val result = saver.toPantry(state.rows, state.defaultLocationId)
            if (result.error == null) {
                local.update { it.copy(saving = false, done = true) }
                store.markSaved(key.sessionId, result.savedKeys.size)
                effectChannel.send(ScanReviewEffect.Saved(result.savedKeys.size))
            } else {
                // Keep what was not saved (and say why); the saved rows leave the list.
                store.update(key.sessionId) { session -> session.copy(items = session.items.filterNot { it.key in result.savedKeys }) }
                local.update { it.copy(saving = false) }
                effectChannel.send(ScanReviewEffect.Failed(result.error))
            }
        }
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val PICKER_RESULTS = 8
    }
}
