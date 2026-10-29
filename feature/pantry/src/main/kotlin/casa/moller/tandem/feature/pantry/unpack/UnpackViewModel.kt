package casa.moller.tandem.feature.pantry.unpack

import androidx.compose.runtime.getValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.UnpackChoice
import casa.moller.tandem.core.model.pantry.UnpackItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * One item to unpack with the user's choice.
 *
 * @property locationId where it goes, null when the household has no place yet.
 * @property expiry the quick pick for its day (counted from today when it is saved).
 * @property include false = not put in the pantry (it is still marked done for today).
 */
data class UnpackRow(val item: UnpackItem, val locationId: Long?, val expiry: ExpiryChoice, val include: Boolean)

/**
 * "Packa upp" (#46, plan §8.8): what was checked on the shopping list today → a place and a
 * best-before day per item or for all → pantry entries. Skipping is fine: "Hoppa över" marks
 * everything done for today without adding anything.
 *
 * @property allLocationId the place chosen for all items, null when they differ.
 * @property allExpiry the day chosen for all items, null when they differ.
 */
data class UnpackUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val today: LocalDate? = null,
    val rows: List<UnpackRow> = emptyList(),
    val locations: List<InventoryLocation> = emptyList(),
    val saving: Boolean = false,
) {
    val chosenCount: Int
        get() = rows.count { it.include && it.locationId != null }

    val allLocationId: Long?
        get() = rows.map { it.locationId }.distinct().singleOrNull()

    val allExpiry: ExpiryChoice?
        get() = rows.map { it.expiry }.distinct().singleOrNull()

    /** The quick picks for all items: the freezer's when every item goes there. */
    val allExpiryChoices: List<ExpiryChoice>
        get() = ExpiryChoice.forLocation(locations.firstOrNull { it.id == allLocationId })

    val canSave: Boolean
        get() = !readOnly && !saving && chosenCount > 0
}

/** What "Packa upp" can ask for. */
sealed interface UnpackAction {
    data class AllLocation(val locationId: Long) : UnpackAction

    data class AllExpiry(val choice: ExpiryChoice) : UnpackAction

    data class ItemLocation(val shoppingEntryId: Long, val locationId: Long) : UnpackAction

    data class ItemExpiry(val shoppingEntryId: Long, val choice: ExpiryChoice) : UnpackAction

    data class ToggleItem(val shoppingEntryId: Long) : UnpackAction

    /** "Lägg in n varor". */
    data object Save : UnpackAction

    /** "Hoppa över": nothing is added, today's items are done. */
    data object Skip : UnpackAction

    /** The back arrow (the route's). */
    data object Close : UnpackAction
}

/** One-off effects of "Packa upp". */
sealed interface UnpackEffect {
    /** [count] entries were added (0 after "Hoppa över"); the route goes back. */
    data class Done(val count: Int) : UnpackEffect

    data class Failed(val error: TandemError) : UnpackEffect
}

/**
 * See [UnpackUiState]. The choices live in [SavedStateHandle] by shopping entry id (rotation,
 * process death); an item without a choice goes in the first place that is not a freezer, without
 * a day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UnpackViewModel @Inject internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val pantry: PantryRepository,
    permissions: SpacePermissionsRepository,
    calendar: CalendarDays,
) : ViewModel() {

    private val effectChannel = Channel<UnpackEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<UnpackEffect> = effectChannel.receiveAsFlow()

    private val saving = MutableStateFlow(false)

    private val choices: Flow<Map<Long, Choice>> = savedStateHandle.getStateFlow(KEY_CHOICES, "").map { decode(it) }

    private val items: Flow<Pair<LocalDate, List<UnpackItem>>> = calendar.observeToday().flatMapLatest { day ->
        pantry.observeUnpackable(day).map { day to it }
    }

    val uiState: StateFlow<UnpackUiState> = combine(
        items,
        pantry.observeLocations(),
        choices,
        permissions.permissions,
        saving,
    ) { (day, list), locations, chosen, perms, busy ->
        val fallback = locations.firstOrNull { !it.isFreezer }?.id ?: locations.firstOrNull()?.id
        UnpackUiState(
            loading = false,
            readOnly = perms.readOnly,
            today = day,
            rows = list.map { item ->
                val choice = chosen[item.shoppingEntryId]
                UnpackRow(
                    item = item,
                    locationId = choice?.locationId?.takeIf { id -> locations.any { it.id == id } } ?: fallback,
                    expiry = choice?.expiry ?: ExpiryChoice.NONE,
                    include = choice?.include ?: true,
                )
            },
            locations = locations,
            saving = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UnpackUiState())

    fun onAction(action: UnpackAction) {
        when (action) {
            is UnpackAction.AllLocation -> updateAll { it.copy(locationId = action.locationId) }
            is UnpackAction.AllExpiry -> updateAll { it.copy(expiry = action.choice) }
            is UnpackAction.ItemLocation -> update(action.shoppingEntryId) { it.copy(locationId = action.locationId) }
            is UnpackAction.ItemExpiry -> update(action.shoppingEntryId) { it.copy(expiry = action.choice) }
            is UnpackAction.ToggleItem -> update(action.shoppingEntryId) { it.copy(include = !it.include) }
            UnpackAction.Save -> finish(skip = false)
            UnpackAction.Skip -> finish(skip = true)
            UnpackAction.Close -> Unit
        }
    }

    private fun current(): Map<Long, Choice> = uiState.value.rows.associate { row ->
        row.item.shoppingEntryId to Choice(row.locationId, row.expiry, row.include)
    }

    private fun update(id: Long, change: (Choice) -> Choice) {
        if (uiState.value.readOnly) return
        val all = current()
        val choice = all[id] ?: return
        savedStateHandle[KEY_CHOICES] = encode(all + (id to change(choice)))
    }

    private fun updateAll(change: (Choice) -> Choice) {
        if (uiState.value.readOnly) return
        savedStateHandle[KEY_CHOICES] = encode(current().mapValues { (_, choice) -> change(choice) })
    }

    private fun finish(skip: Boolean) {
        val state = uiState.value
        val today = state.today ?: return
        if (state.readOnly || state.saving || state.rows.isEmpty()) return
        if (!skip && !state.canSave) return
        saving.value = true
        viewModelScope.launch {
            val chosen = state.rows.mapNotNull { row ->
                val locationId = row.locationId ?: return@mapNotNull null
                UnpackChoice(
                    item = row.item,
                    locationId = locationId,
                    expires = row.expiry.dateFrom(today),
                    include = !skip && row.include,
                )
            }
            val result = pantry.unpack(chosen)
            saving.value = false
            when (result) {
                is Outcome.Success -> {
                    savedStateHandle[KEY_CHOICES] = ""
                    effectChannel.send(UnpackEffect.Done(result.value))
                }

                is Outcome.Failure -> effectChannel.send(UnpackEffect.Failed(result.error))
            }
        }
    }

    /** One item's choice, kept in the saved state as `id:location:EXPIRY:include` joined by `;`. */
    private data class Choice(val locationId: Long?, val expiry: ExpiryChoice, val include: Boolean)

    private fun encode(choices: Map<Long, Choice>): String = choices.entries.joinToString(";") { (id, choice) ->
        "$id:${choice.locationId ?: ""}:${choice.expiry.name}:${choice.include}"
    }

    private fun decode(text: String): Map<Long, Choice> = text.split(';').mapNotNull { part ->
        val fields = part.split(':')
        if (fields.size != FIELDS) return@mapNotNull null
        val id = fields[0].toLongOrNull() ?: return@mapNotNull null
        val expiry = ExpiryChoice.entries.firstOrNull { it.name == fields[2] } ?: ExpiryChoice.NONE
        id to Choice(fields[1].toLongOrNull(), expiry, fields[3].toBoolean())
    }.toMap()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_CHOICES = "unpack-choices"
        const val FIELDS = 4
    }
}
