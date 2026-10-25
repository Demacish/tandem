package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.shopping.IngredientParser
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.shopping.ParsedIngredient
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.feature.shopping.navigation.ShoppingListKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Handla tab (plan 6.2 "Handla", features #10, #11, #14; Stitch ink_pslista_*).
 *
 * - **Offline first:** everything comes from the stored list ([ShoppingSource]); every change is
 *   stored at once and queued by the repository, so the screen works the same offline and only
 *   shows how many changes wait.
 * - **Grouping:** by aisle in the chosen store's order ([ShoppingGrouping]); "Köp senare" and
 *   "Avbockat" below.
 * - **Undo:** checking, "köp senare", adding and removing show a snackbar with "Ångra".
 * - **Free text** (#11): previewed by the local parser while typing; Tandoor's parser (which
 *   creates foods) runs only when the user adds, with the local parser as the offline fallback.
 * - **Guest** (#42): read-only; actions that write are ignored (the repositories refuse them too).
 * - **Survives** rotation and process death: chosen list, store override, open sections and the
 *   text being typed live in [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ShoppingViewModel.Factory::class)
class ShoppingViewModel @AssistedInject internal constructor(
    @Assisted key: ShoppingListKey,
    private val savedStateHandle: SavedStateHandle,
    source: ShoppingSource,
    private val shopping: ShoppingRepository,
    private val supermarkets: SupermarketRepository,
    private val parser: IngredientParser,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: ShoppingListKey): ShoppingViewModel
    }

    private val effectChannel = Channel<ShoppingEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once (e.g. in a LaunchedEffect). */
    val effects: Flow<ShoppingEffect> = effectChannel.receiveAsFlow()

    private val transient = MutableStateFlow(Transient())

    /** The last change that "Ångra" takes back. */
    private var undo: (suspend () -> Outcome<*>)? = null

    init {
        if (!savedStateHandle.contains(KEY_LIST)) savedStateHandle[KEY_LIST] = key.listId
        if (!savedStateHandle.contains(KEY_STORE)) savedStateHandle[KEY_STORE] = key.supermarketId
    }

    private val listId = savedStateHandle.getStateFlow<Long?>(KEY_LIST, key.listId)
    private val storeOverride = savedStateHandle.getStateFlow<Long?>(KEY_STORE, key.supermarketId)
    private val addText = savedStateHandle.getStateFlow(KEY_ADD_TEXT, "")

    private val sections = combine(
        savedStateHandle.getStateFlow(KEY_CHECKED_OPEN, false),
        savedStateHandle.getStateFlow(KEY_LATER_OPEN, true),
        savedStateHandle.getStateFlow(KEY_ADD_OPEN, false),
    ) { checkedOpen, laterOpen, addOpen -> Triple(checkedOpen, laterOpen, addOpen) }

    private val suggestions: Flow<List<String>> = addText
        .map { ShoppingGrouping.splitItems(it).lastOrNull().orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                shopping.observeSuggestions(query, SUGGESTIONS).map { list -> list.filterNot { it.equals(query, ignoreCase = true) } }
            }
        }

    private val recent: Flow<List<String>> = shopping.observeHistory().map { history ->
        history.map { it.foodName.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(SUGGESTIONS)
    }

    /** Tandoor's `default_delay` in hours, from the last snapshot ("köp senare"). */
    private var latestDelayHours: Double = DEFAULT_DELAY_HOURS

    private val addState: Flow<AddItemState> = combine(addText, suggestions, recent) { text, found, recentFoods ->
        AddItemState(text = text, preview = preview(text), suggestions = found, recent = recentFoods)
    }

    val uiState: StateFlow<ShoppingUiState> = combine(
        source.observe(listId, storeOverride),
        listId,
        sections,
        addState,
        combine(transient, shopping.observeHistory()) { state, history -> state to history },
    ) { snapshot, list, (checkedOpen, laterOpen, addOpen), add, (local, history) ->
        val readOnly = snapshot.permissions.readOnly
        ShoppingUiState(
            loading = false,
            readOnly = readOnly,
            lists = snapshot.lists,
            selectedListId = list,
            supermarkets = snapshot.supermarkets,
            store = snapshot.store,
            categories = snapshot.categories,
            grouped = snapshot.grouped(),
            amountStyle = AmountStyle.of(snapshot.preferences),
            pendingCount = snapshot.pendingCount,
            syncNotice = ShoppingSyncNotice.of(snapshot.pause),
            refreshing = local.refreshing,
            checkedExpanded = checkedOpen,
            laterExpanded = laterOpen,
            add = add.copy(adding = local.adding),
            addSheetOpen = addOpen && !readOnly,
            edit = local.edit.takeUnless { readOnly },
            confirmClearChecked = local.confirmClear && !readOnly,
            history = history,
        ).also { latestDelayHours = snapshot.preferences.defaultDelayHours }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ShoppingUiState())

    init {
        viewModelScope.launch {
            shopping.observeChanges().collect { effectChannel.send(ShoppingEffect.OtherChange(it)) }
        }
    }

    fun onAction(action: ShoppingAction) {
        when (action) {
            ShoppingAction.Refresh -> refresh()
            is ShoppingAction.ToggleChecked -> entry(action.id)?.let(::toggleChecked)
            is ShoppingAction.CheckSection -> checkSection(action.sectionKey)
            is ShoppingAction.ToggleLater -> entry(action.id)?.let(::toggleLater)
            ShoppingAction.Undo -> takeBack()
            is ShoppingAction.SelectList -> savedStateHandle[KEY_LIST] = action.listId
            is ShoppingAction.SelectStore -> selectStore(action.supermarketId)
            ShoppingAction.ToggleCheckedExpanded -> savedStateHandle[KEY_CHECKED_OPEN] = !uiState.value.checkedExpanded
            ShoppingAction.ToggleLaterExpanded -> savedStateHandle[KEY_LATER_OPEN] = !uiState.value.laterExpanded
            ShoppingAction.AskClearChecked -> if (canWrite()) transient.update { it.copy(confirmClear = true) }
            is ShoppingAction.ConfirmClearChecked -> clearChecked(action.confirmed)
            ShoppingAction.OpenAdd -> if (canWrite()) savedStateHandle[KEY_ADD_OPEN] = true
            ShoppingAction.CloseAdd -> savedStateHandle[KEY_ADD_OPEN] = false
            is ShoppingAction.AddTextChanged -> savedStateHandle[KEY_ADD_TEXT] = action.text
            ShoppingAction.SubmitAdd -> submitAdd()
            is ShoppingAction.AddFood -> addFood(action.name)
            is ShoppingAction.OpenEdit -> openEdit(action.id)
            ShoppingAction.CloseEdit -> transient.update { it.copy(edit = null) }
            is ShoppingAction.EditFood -> editing { it.copy(food = action.text) }
            is ShoppingAction.EditAmount -> editing { it.copy(amount = action.text, amountError = false) }
            is ShoppingAction.EditUnit -> editing { it.copy(unit = action.text) }
            is ShoppingAction.EditCategory -> editing { it.copy(categoryId = action.categoryId) }
            ShoppingAction.SaveEdit -> saveEdit()
            ShoppingAction.DeleteEdited -> deleteEdited()
            // Navigation and sharing are handled by the route.
            ShoppingAction.OpenStoreMode, ShoppingAction.OpenStoreChoice, ShoppingAction.OpenHistory,
            is ShoppingAction.OpenRecipe, ShoppingAction.Share,
            -> Unit
        }
    }

    private fun canWrite(): Boolean = !uiState.value.loading && !uiState.value.readOnly

    private fun entry(id: Long): ShoppingEntry? = uiState.value.grouped.all.firstOrNull { it.id == id }

    private fun refresh() {
        if (transient.value.refreshing) return
        transient.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            // Pull to refresh re-reads the whole list (plan 3.5: deletions are only seen by a full read).
            val error = shopping.refresh(full = true).errorOrNull()
            transient.update { it.copy(refreshing = false) }
            if (error != null) effectChannel.send(ShoppingEffect.Failed(error))
        }
    }

    private fun toggleChecked(entry: ShoppingEntry) {
        if (!canWrite()) return
        val checked = !entry.checked
        write(
            message = if (checked) ShoppingMessage.Checked(entry.food.name, 1) else ShoppingMessage.Unchecked(entry.food.name),
            undo = { shopping.setChecked(listOf(entry.id), !checked) },
        ) { shopping.setChecked(listOf(entry.id), checked) }
    }

    private fun checkSection(key: String) {
        if (!canWrite()) return
        val section = uiState.value.grouped.sections.firstOrNull { it.key == key } ?: return
        val ids = section.entries.map { it.id }
        write(
            message = ShoppingMessage.Checked(section.entries.first().food.name, ids.size),
            undo = { shopping.setChecked(ids, false) },
        ) { shopping.setChecked(ids, true) }
    }

    private fun toggleLater(entry: ShoppingEntry) {
        if (!canWrite()) return
        val now = clock.now()
        if (entry.isDelayed(now)) {
            val until = entry.delayUntil
            write(ShoppingMessage.Undelayed(entry.food.name), undo = { shopping.setDelayed(listOf(entry.id), until) }) {
                shopping.setDelayed(listOf(entry.id), null)
            }
        } else {
            // Tandoor's "köp senare": now + the user's default_delay (plan 8.2), at least one minute.
            val delay = (latestDelayHours * MINUTES_PER_HOUR).coerceAtLeast(1.0).minutes
            write(ShoppingMessage.Delayed(entry.food.name), undo = { shopping.setDelayed(listOf(entry.id), null) }) {
                shopping.setDelayed(listOf(entry.id), now + delay)
            }
        }
    }

    private fun takeBack() {
        val action = undo ?: return
        undo = null
        viewModelScope.launch {
            action().errorOrNull()?.let { effectChannel.send(ShoppingEffect.Failed(it)) }
        }
    }

    private fun selectStore(id: Long?) {
        savedStateHandle[KEY_STORE] = null
        viewModelScope.launch { supermarkets.selectSupermarket(id) }
    }

    private fun clearChecked(confirmed: Boolean) {
        transient.update { it.copy(confirmClear = false) }
        if (!confirmed || !canWrite()) return
        val count = uiState.value.grouped.checked.size
        write(ShoppingMessage.ClearedChecked(count), undo = null) { shopping.clearChecked() }
    }

    private fun submitAdd() {
        if (!canWrite() || transient.value.adding) return
        val items = ShoppingGrouping.splitItems(addText.value)
        if (items.isEmpty()) return
        transient.update { it.copy(adding = true) }
        viewModelScope.launch {
            // Tandoor's parser online; the local parser when it fails or the device is offline.
            val parsed = parser.parse(items).getOrNull()?.takeIf { it.size == items.size } ?: items.map(::parseLocally)
            val drafts = parsed.map { it.toDraft() }
            val result = shopping.addAll(drafts)
            transient.update { it.copy(adding = false) }
            when (result) {
                is Outcome.Success -> {
                    savedStateHandle[KEY_ADD_TEXT] = ""
                    val ids = result.value
                    undo = { shopping.remove(ids) }
                    effectChannel.send(ShoppingEffect.Show(ShoppingMessage.Added(drafts.first().foodName, drafts.size), undoable = true))
                }

                is Outcome.Failure -> effectChannel.send(ShoppingEffect.Failed(result.error))
            }
        }
    }

    private fun addFood(name: String) {
        if (!canWrite()) return
        val text = addText.value
        // A suggestion for the item being typed replaces it; a recent food leaves the text alone.
        if (text.isNotBlank()) savedStateHandle[KEY_ADD_TEXT] = ShoppingGrouping.splitItems(text).dropLast(1).joinToString(", ")
        viewModelScope.launch {
            when (val result = shopping.add(ShoppingEntryDraft(foodName = name, listIds = selectedLists()))) {
                is Outcome.Success -> {
                    undo = { shopping.remove(listOf(result.value)) }
                    effectChannel.send(ShoppingEffect.Show(ShoppingMessage.Added(name, 1), undoable = true))
                }

                is Outcome.Failure -> effectChannel.send(ShoppingEffect.Failed(result.error))
            }
        }
    }

    private fun openEdit(id: Long) {
        if (!canWrite()) return
        val entry = entry(id) ?: return
        val state = uiState.value
        transient.update {
            it.copy(
                edit = EditEntryState(
                    entry = entry,
                    food = entry.food.name,
                    amount = editableAmount(entry.amount),
                    unit = entry.unit?.name.orEmpty(),
                    categoryId = entry.food.categoryId,
                    categories = state.categories.sortedBy { category -> category.name.lowercase() },
                    delayed = entry.isDelayed(clock.now()),
                ),
            )
        }
    }

    private inline fun editing(change: (EditEntryState) -> EditEntryState) {
        transient.update { state -> state.copy(edit = state.edit?.let(change)) }
    }

    private fun saveEdit() {
        val edit = transient.value.edit ?: return
        if (!canWrite() || edit.saving) return
        val amount = ShoppingGrouping.parseAmount(edit.amount)
        if (amount == null) {
            editing { it.copy(amountError = true) }
            return
        }
        val entry = edit.entry
        val food = edit.food.trim().takeIf { it.isNotEmpty() && it != entry.food.name }
        val unit = edit.unit.trim().takeIf { it.isNotEmpty() && it != entry.unit?.name }
        val newAmount = amount.takeIf { it != entry.amount }
        val foodId = entry.food.id
        editing { it.copy(saving = true) }
        viewModelScope.launch {
            val updated = if (food != null || unit != null || newAmount != null) {
                shopping.update(entry.id, amount = newAmount, unitName = unit, foodName = food)
            } else {
                Outcome.Success(Unit)
            }
            // The aisle belongs to the food (for the whole space): only for the food the entry already had.
            val moved = if (updated is Outcome.Success && foodId != null && edit.categoryId != entry.food.categoryId && food == null) {
                supermarkets.setFoodCategory(foodId, edit.categoryId)
            } else {
                Outcome.Success(Unit)
            }
            val error = updated.errorOrNull() ?: moved.errorOrNull()
            if (error != null) {
                editing { it.copy(saving = false) }
                effectChannel.send(ShoppingEffect.Failed(error))
            } else {
                transient.update { it.copy(edit = null) }
                effectChannel.send(ShoppingEffect.Show(ShoppingMessage.Saved))
            }
        }
    }

    private fun deleteEdited() {
        val edit = transient.value.edit ?: return
        if (!canWrite()) return
        val entry = edit.entry
        transient.update { it.copy(edit = null) }
        // "Ångra" adds the food back as a new entry (a recipe's label is not restored).
        val draft = ShoppingEntryDraft(
            foodName = entry.food.name,
            amount = entry.amount,
            unitName = entry.unit?.name,
            unitId = entry.unit?.id,
            foodId = entry.food.id,
            listIds = entry.listIds,
        )
        write(ShoppingMessage.Removed(entry.food.name), undo = { shopping.add(draft) }) { shopping.remove(listOf(entry.id)) }
    }

    /** Runs [block]; on success remembers [undo] and shows [message], on failure the error. */
    private fun write(message: ShoppingMessage, undo: (suspend () -> Outcome<*>)?, block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            when (val result = block()) {
                is Outcome.Success -> {
                    this@ShoppingViewModel.undo = undo
                    effectChannel.send(ShoppingEffect.Show(message, undoable = undo != null))
                }

                is Outcome.Failure -> effectChannel.send(ShoppingEffect.Failed(result.error))
            }
        }
    }

    private fun selectedLists(): Set<Long> = listId.value?.let { setOf(it) }.orEmpty()

    private fun preview(text: String): List<ParsedIngredient> = ShoppingGrouping.splitItems(text).map(::parseLocally)

    /** The local parser, or the whole text as the food when it finds nothing. */
    private fun parseLocally(item: String): ParsedIngredient =
        parser.parseLocally(item)?.takeIf { it.foodName.isNotBlank() } ?: ParsedIngredient(item, 0.0, null, item)

    private fun ParsedIngredient.toDraft(): ShoppingEntryDraft = ShoppingEntryDraft(
        foodName = foodName.ifBlank { originalText },
        amount = amount,
        unitName = unitName,
        unitId = unitId,
        foodId = foodId,
        note = note,
        listIds = selectedLists(),
    )

    /** The amount for the edit field: "1,5" in Swedish, empty for no amount. */
    private fun editableAmount(amount: Double): String {
        if (amount <= 0.0) return ""
        val plain = BigDecimal(amount).setScale(AMOUNT_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return plain.replace('.', DecimalFormatSymbols.getInstance().decimalSeparator)
    }

    private data class Transient(
        val refreshing: Boolean = false,
        val adding: Boolean = false,
        val edit: EditEntryState? = null,
        val confirmClear: Boolean = false,
    )

    internal companion object {
        const val KEY_LIST = "shopping.list"
        const val KEY_STORE = "shopping.store"
        const val KEY_ADD_TEXT = "shopping.addText"
        const val KEY_ADD_OPEN = "shopping.addOpen"
        const val KEY_CHECKED_OPEN = "shopping.checkedOpen"
        const val KEY_LATER_OPEN = "shopping.laterOpen"
        const val SUGGESTIONS = 8
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_DELAY_HOURS = 4.0
        const val MINUTES_PER_HOUR = 60.0
        const val AMOUNT_DECIMALS = 3
    }
}
