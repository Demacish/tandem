package casa.moller.tandem.feature.pantry.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryEntryDraft
import casa.moller.tandem.core.model.pantry.InventoryEntryUpdate
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.feature.pantry.amountFieldText
import casa.moller.tandem.feature.pantry.navigation.PantryEntryKey
import casa.moller.tandem.feature.pantry.parseAmount
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Adding or changing one stock entry (`PantryEntryKey`, plan §8.8).
 *
 * @property suggestions stored foods matching what is typed (one tap picks the food and its id).
 * @property units units already used in the pantry ("st", "dl", "paket"), as quick picks.
 * @property expiryChoices the quick picks for the chosen place ([ExpiryChoice.forLocation]).
 * @property missing the entry no longer exists (removed on another phone).
 */
data class PantryEntryUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val isNew: Boolean = true,
    val missing: Boolean = false,
    val today: LocalDate? = null,
    val food: String = "",
    val foodId: Long? = null,
    val suggestions: List<Food> = emptyList(),
    val amount: String = "",
    val amountError: Boolean = false,
    val unit: String = "",
    val units: List<String> = emptyList(),
    val locations: List<InventoryLocation> = emptyList(),
    val locationId: Long? = null,
    val expires: LocalDate? = null,
    val expiryChoices: List<ExpiryChoice> = ExpiryChoice.forLocation(null),
    val note: String = "",
    val saving: Boolean = false,
    val confirmDelete: Boolean = false,
) {
    val location: InventoryLocation?
        get() = locations.firstOrNull { it.id == locationId }

    /** The quick pick that gives [expires] from [today], null when the day was picked in the calendar. */
    val selectedChoice: ExpiryChoice?
        get() = today?.let { day -> ExpiryChoice.entries.firstOrNull { it.dateFrom(day) == expires } }

    val canSave: Boolean
        get() = !readOnly && !loading && !missing && !saving && food.isNotBlank() && locationId != null && amount.isNotBlank()
}

/** What the entry page can ask for. */
sealed interface PantryEntryAction {
    data class FoodText(val text: String) : PantryEntryAction

    /** A suggestion: the stored food with its id. */
    data class PickFood(val food: Food) : PantryEntryAction

    data class AmountText(val text: String) : PantryEntryAction

    data class UnitText(val text: String) : PantryEntryAction

    data class PickLocation(val locationId: Long) : PantryEntryAction

    data class PickExpiry(val choice: ExpiryChoice) : PantryEntryAction

    /** A day picked in the calendar (null clears it). */
    data class PickDate(val date: LocalDate?) : PantryEntryAction

    data class NoteText(val text: String) : PantryEntryAction

    data object Save : PantryEntryAction

    data object AskDelete : PantryEntryAction

    data class ConfirmDelete(val confirmed: Boolean) : PantryEntryAction

    data class SetLocale(val locale: Locale) : PantryEntryAction

    /** The back arrow (the route's). */
    data object Close : PantryEntryAction
}

/** One-off effects of the entry page. */
sealed interface PantryEntryEffect {
    /** Saved or removed: the route goes back. */
    data object Done : PantryEntryEffect

    data class Failed(val error: TandemError) : PantryEntryEffect
}

/**
 * See [PantryEntryUiState]. The form lives in [SavedStateHandle] (rotation, process death). An
 * existing entry fills it once. Tandoor's entry keeps its food and unit, so a changed food or unit
 * replaces the entry (a new one, then the old one removed); everything else is a change of it.
 * Removing asks first (the page closes, so there is nowhere to show "Ångra").
 */
@HiltViewModel(assistedFactory = PantryEntryViewModel.Factory::class)
class PantryEntryViewModel @AssistedInject internal constructor(
    @Assisted private val key: PantryEntryKey,
    private val savedStateHandle: SavedStateHandle,
    private val pantry: PantryRepository,
    recipes: RecipeRepository,
    permissions: SpacePermissionsRepository,
    calendar: CalendarDays,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: PantryEntryKey): PantryEntryViewModel
    }

    private val effectChannel = Channel<PantryEntryEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<PantryEntryEffect> = effectChannel.receiveAsFlow()

    private val locale = MutableStateFlow(Locale.getDefault())
    private val busy = MutableStateFlow(Busy())

    /** The entry being changed as it was when the form was filled. */
    private var original: InventoryEntry? = null

    private val existing: Flow<InventoryEntry?> = key.entryId?.let { id -> pantry.observeEntry(id) } ?: flowOf(null)

    private val form: Flow<Form> = combine(
        savedStateHandle.getStateFlow(KEY_FOOD, ""),
        savedStateHandle.getStateFlow(KEY_FOOD_ID, NONE),
        savedStateHandle.getStateFlow(KEY_AMOUNT, DEFAULT_AMOUNT),
        savedStateHandle.getStateFlow(KEY_UNIT, ""),
        combine(
            savedStateHandle.getStateFlow(KEY_LOCATION, NONE),
            savedStateHandle.getStateFlow(KEY_EXPIRES, ""),
            savedStateHandle.getStateFlow(KEY_NOTE, ""),
            savedStateHandle.getStateFlow(KEY_FILLED, false),
        ) { location, expires, note, filled -> Rest(location, expires, note, filled) },
    ) { food, foodId, amount, unit, rest ->
        Form(
            food = food,
            foodId = foodId.takeIf { it != NONE },
            amount = amount,
            unit = unit,
            locationId = rest.location.takeIf { it != NONE },
            expires = rest.expires.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            note = rest.note,
            filled = rest.filled,
        )
    }

    private val sources: Flow<Sources> = combine(
        existing.onEach { fillFrom(it) },
        pantry.observeLocations().onEach { defaultLocation(it) },
        pantry.observePantry().map { groups ->
            groups.flatMap { it.entries }.mapNotNull { it.unit?.name?.trim() }.filter { it.isNotEmpty() }
        },
        recipes.observeFoods(),
        combine(permissions.permissions, calendar.observeToday()) { perms, day -> perms.readOnly to day },
    ) { entry, locations, units, foods, (readOnly, today) -> Sources(entry, locations, units, foods, readOnly, today) }

    val uiState: StateFlow<PantryEntryUiState> = combine(sources, form, busy) { sources, form, busy ->
        build(sources, form, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PantryEntryUiState(isNew = key.entryId == null))

    fun onAction(action: PantryEntryAction) {
        when (action) {
            is PantryEntryAction.FoodText -> {
                savedStateHandle[KEY_FOOD] = action.text
                savedStateHandle[KEY_FOOD_ID] = NONE
            }

            is PantryEntryAction.PickFood -> {
                savedStateHandle[KEY_FOOD] = action.food.name
                savedStateHandle[KEY_FOOD_ID] = action.food.id
            }

            is PantryEntryAction.AmountText -> {
                savedStateHandle[KEY_AMOUNT] = action.text
                busy.value = busy.value.copy(amountError = false)
            }

            is PantryEntryAction.UnitText -> savedStateHandle[KEY_UNIT] = action.text

            is PantryEntryAction.PickLocation -> savedStateHandle[KEY_LOCATION] = action.locationId

            is PantryEntryAction.PickExpiry -> pickExpiry(action.choice)

            is PantryEntryAction.PickDate -> savedStateHandle[KEY_EXPIRES] = action.date?.toString().orEmpty()

            is PantryEntryAction.NoteText -> savedStateHandle[KEY_NOTE] = action.text

            PantryEntryAction.Save -> save()

            PantryEntryAction.AskDelete -> askDelete()

            is PantryEntryAction.ConfirmDelete -> delete(action.confirmed)

            is PantryEntryAction.SetLocale -> locale.value = action.locale

            PantryEntryAction.Close -> Unit
        }
    }

    private fun askDelete() {
        val state = uiState.value
        if (!state.readOnly && !state.isNew) busy.value = busy.value.copy(confirmDelete = true)
    }

    private fun pickExpiry(choice: ExpiryChoice) {
        val today = uiState.value.today ?: return
        savedStateHandle[KEY_EXPIRES] = choice.dateFrom(today)?.toString().orEmpty()
    }

    /** Fills the form once from the entry being changed. */
    private fun fillFrom(entry: InventoryEntry?) {
        if (entry == null || savedStateHandle.get<Boolean>(KEY_FILLED) == true) {
            if (entry != null && original == null) original = entry
            return
        }
        original = entry
        savedStateHandle[KEY_FOOD] = entry.food.name
        savedStateHandle[KEY_FOOD_ID] = entry.food.id ?: NONE
        savedStateHandle[KEY_AMOUNT] = amountFieldText(entry.amount, locale.value)
        savedStateHandle[KEY_UNIT] = entry.unit?.name.orEmpty()
        savedStateHandle[KEY_LOCATION] = entry.locationId
        savedStateHandle[KEY_EXPIRES] = entry.expires?.toString().orEmpty()
        savedStateHandle[KEY_NOTE] = entry.note.orEmpty()
        savedStateHandle[KEY_FILLED] = true
    }

    /** A new entry goes in the place it was opened from, else the first one that is not a freezer. */
    private fun defaultLocation(locations: List<InventoryLocation>) {
        if (key.entryId != null || savedStateHandle.get<Long>(KEY_LOCATION).let { it != null && it != NONE }) return
        val chosen = key.locationId?.takeIf { id -> locations.any { it.id == id } }
            ?: locations.firstOrNull { !it.isFreezer }?.id
            ?: locations.firstOrNull()?.id
            ?: return
        savedStateHandle[KEY_LOCATION] = chosen
    }

    private fun build(sources: Sources, form: Form, busy: Busy): PantryEntryUiState {
        val isNew = key.entryId == null
        val missing = !isNew && sources.entry == null && original != null
        val loading = !isNew && sources.entry == null && original == null
        val query = form.food.trim()
        val suggestions = if (query.isEmpty() || form.foodId != null) {
            emptyList()
        } else {
            sources.foods
                .filter { it.name.contains(query, ignoreCase = true) && !it.name.equals(query, ignoreCase = true) }
                .sortedBy { !it.name.startsWith(query, ignoreCase = true) }
                .take(MAX_SUGGESTIONS)
        }
        val location = sources.locations.firstOrNull { it.id == form.locationId }
        return PantryEntryUiState(
            loading = loading,
            readOnly = sources.readOnly,
            isNew = isNew,
            missing = missing,
            today = sources.today,
            food = form.food,
            foodId = form.foodId,
            suggestions = suggestions,
            amount = form.amount,
            amountError = busy.amountError,
            unit = form.unit,
            units = sources.units.distinctBy { it.lowercase() }.take(MAX_UNITS),
            locations = sources.locations,
            locationId = form.locationId,
            expires = form.expires,
            expiryChoices = ExpiryChoice.forLocation(location),
            note = form.note,
            saving = busy.saving,
            confirmDelete = busy.confirmDelete && !sources.readOnly,
        )
    }

    private fun save() {
        val state = uiState.value
        if (!state.canSave) return
        val amount = parseAmount(state.amount, locale.value)
        val locationId = state.locationId
        if (amount == null || locationId == null) {
            busy.value = busy.value.copy(amountError = amount == null)
            return
        }
        busy.value = busy.value.copy(saving = true)
        viewModelScope.launch {
            val before = original
            val draft = InventoryEntryDraft(
                locationId = locationId,
                foodName = state.food.trim(),
                foodId = state.foodId,
                amount = amount,
                unitName = state.unit.trim().takeIf { it.isNotEmpty() },
                expires = state.expires,
                note = state.note.trim().takeIf { it.isNotEmpty() },
            )
            val result = when {
                before == null -> pantry.addEntry(draft)

                replaces(before, draft) -> pantry.addEntry(draft).let { added ->
                    if (added is Outcome.Success) pantry.deleteEntry(before.id) else added
                }

                else -> pantry.updateEntry(
                    before.id,
                    InventoryEntryUpdate(
                        amount = amount,
                        locationId = locationId,
                        expires = state.expires,
                        clearExpires = state.expires == null && before.expires != null,
                        note = state.note.trim().takeIf { it.isNotEmpty() } ?: before.note?.let { "" },
                    ),
                )
            }
            busy.value = busy.value.copy(saving = false)
            when (result) {
                is Outcome.Success -> effectChannel.send(PantryEntryEffect.Done)
                is Outcome.Failure -> effectChannel.send(PantryEntryEffect.Failed(result.error))
            }
        }
    }

    /** Another food or unit: Tandoor's entry cannot change them, so a new entry replaces it. */
    private fun replaces(before: InventoryEntry, draft: InventoryEntryDraft): Boolean {
        val foodChanged = !before.food.name.trim().equals(draft.foodName, ignoreCase = true)
        val unitChanged = !(before.unit?.name?.trim().orEmpty()).equals(draft.unitName.orEmpty(), ignoreCase = true)
        return foodChanged || unitChanged
    }

    private fun delete(confirmed: Boolean) {
        busy.value = busy.value.copy(confirmDelete = false)
        val id = original?.id ?: key.entryId ?: return
        if (!confirmed || uiState.value.readOnly) return
        viewModelScope.launch {
            when (val result = pantry.deleteEntry(id)) {
                is Outcome.Success -> effectChannel.send(PantryEntryEffect.Done)
                is Outcome.Failure -> effectChannel.send(PantryEntryEffect.Failed(result.error))
            }
        }
    }

    private data class Rest(val location: Long, val expires: String, val note: String, val filled: Boolean)

    private data class Form(
        val food: String,
        val foodId: Long?,
        val amount: String,
        val unit: String,
        val locationId: Long?,
        val expires: LocalDate?,
        val note: String,
        val filled: Boolean,
    )

    private data class Sources(
        val entry: InventoryEntry?,
        val locations: List<InventoryLocation>,
        val units: List<String>,
        val foods: List<Food>,
        val readOnly: Boolean,
        val today: LocalDate,
    )

    private data class Busy(val saving: Boolean = false, val amountError: Boolean = false, val confirmDelete: Boolean = false)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_SUGGESTIONS = 6
        const val MAX_UNITS = 8
        const val NONE = Long.MIN_VALUE
        const val DEFAULT_AMOUNT = "1"
        const val KEY_FOOD = "pantry-entry-food"
        const val KEY_FOOD_ID = "pantry-entry-food-id"
        const val KEY_AMOUNT = "pantry-entry-amount"
        const val KEY_UNIT = "pantry-entry-unit"
        const val KEY_LOCATION = "pantry-entry-location"
        const val KEY_EXPIRES = "pantry-entry-expires"
        const val KEY_NOTE = "pantry-entry-note"
        const val KEY_FILLED = "pantry-entry-filled"
    }
}
