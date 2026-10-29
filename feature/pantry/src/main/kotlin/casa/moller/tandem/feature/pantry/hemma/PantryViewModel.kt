package casa.moller.tandem.feature.pantry.hemma

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.pantry.StaplesRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryEntryUpdate
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryGroup
import casa.moller.tandem.core.model.pantry.Staple
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.feature.pantry.PantrySyncNotice
import casa.moller.tandem.feature.pantry.pendingCount
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Hemma (plan §8.8 "Skafferiet", #26, #28, #46): the pantry by place, or one place
 * ([locationId], `PantryLocationKey`).
 *
 * - **Offline first:** everything is read from the device; every change is stored at once and
 *   queued by [PantryRepository], so only the waiting count shows that it is offline.
 * - **Days:** the expiry colours are counted from [CalendarDays.observeToday], so a list left open
 *   over midnight moves "går ut i morgon" to "går ut i dag".
 * - **Undo:** "Slut", "−1", "Flytta" and taking a food off "Har hemma" show "Ångra"; deleting a
 *   place (which takes its entries with it on the server) asks first instead.
 * - **Guest** (#42): read-only; writes are ignored here and refused by the repositories too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PantryViewModel.Factory::class)
class PantryViewModel @AssistedInject internal constructor(
    @Assisted private val locationId: Long?,
    private val pantry: PantryRepository,
    private val staples: StaplesRepository,
    private val supermarkets: SupermarketRepository,
    permissions: SpacePermissionsRepository,
    sync: SyncRepository,
    aiSettings: AiSettingsRepository,
    calendar: CalendarDays,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(locationId: Long?): PantryViewModel
    }

    private val effectChannel = Channel<PantryEffect>(Channel.BUFFERED)

    /** One-off effects (snackbars); collect them once. */
    val effects: Flow<PantryEffect> = effectChannel.receiveAsFlow()

    private val transient = MutableStateFlow(Transient())

    /** What "Ångra" takes back. */
    private var undo: (suspend () -> Outcome<*>)? = null

    private val today: Flow<LocalDate> = calendar.observeToday()

    private val stock: Flow<Stock> = combine(
        pantry.observeLocations(),
        pantry.observePantry(),
        pantry.observeOnHandFoods(),
        staples.observeStaples(),
        today.flatMapLatest { day -> pantry.observeUnpackable(day).map { day to it.size } },
    ) { locations, groups, onHandFoods, stapleList, (day, unpack) -> Stock(locations, groups, onHandFoods, stapleList, day, unpack) }

    private val status: Flow<Status> = combine(
        permissions.permissions,
        sync.status.map { it.pause },
        aiSettings.settings.map { it.fridgePhotoAvailable },
    ) { perms, pause, fridge -> Status(perms, PantrySyncNotice.of(pause), fridge && !perms.isDemo) }

    val uiState: StateFlow<PantryUiState> = combine(stock, status, transient) { stock, status, local ->
        build(stock, status, local)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PantryUiState())

    fun onAction(action: PantryAction) {
        when (action) {
            PantryAction.Refresh -> refresh()
            is PantryAction.MarkEmpty -> row(action.entryId)?.let(::markEmpty)
            is PantryAction.Decrement -> row(action.entryId)?.let(::decrement)
            is PantryAction.StartMove -> if (canWrite()) transient.update { it.copy(movingId = action.entryId) }
            is PantryAction.MoveTo -> moveTo(action.locationId)
            PantryAction.CancelMove -> transient.update { it.copy(movingId = null) }
            is PantryAction.RemoveOnHand -> removeOnHand(action.food)
            PantryAction.Undo -> takeBack()
            is PantryAction.CreateDefaultLocations -> createDefaults(action.places)
            is PantryAction.EditLocation -> editLocation(action.locationId)
            is PantryAction.LocationName -> transient.update { it.copy(editor = it.editor?.copy(name = action.name)) }
            is PantryAction.LocationFreezer -> transient.update { it.copy(editor = it.editor?.copy(isFreezer = action.isFreezer)) }
            PantryAction.SaveLocation -> saveLocation()
            PantryAction.CloseLocationEditor -> transient.update { it.copy(editor = null) }
            is PantryAction.AskDeleteLocation -> if (canWrite()) transient.update { it.copy(deleteId = action.locationId) }
            is PantryAction.ConfirmDeleteLocation -> deleteLocation(action.confirmed)
            // Navigation is the route's.
            else -> Unit
        }
    }

    private fun build(stock: Stock, status: Status, local: Transient): PantryUiState {
        val readOnly = status.permissions.readOnly
        val names = stock.locations.associate { it.id to it.name }
        val groups = stock.groups.filter { locationId == null || it.location.id == locationId }
        val sections = groups.map { group ->
            PantrySection(
                location = group.location,
                rows = group.entries.map { entry ->
                    PantryRow(entry, names[entry.locationId] ?: group.location.name, ExpiryUrgency.of(entry.expires, stock.today))
                },
            )
        }
        val rows = stock.groups.flatMap { it.entries }
        val stocked = rows.mapNotNull { it.food.id }.toSet()
        val stockedNames = rows.map { it.food.name.lowercase() }.toSet()
        val onHand = if (locationId != null) emptyList() else onHandFoods(stock, stocked, stockedNames)
        val moving = local.movingId?.let { id -> sections.flatMap { it.rows }.firstOrNull { it.entry.id == id } }
            ?.takeUnless { readOnly }
        return PantryUiState(
            loading = false,
            readOnly = readOnly,
            householdMissing = status.permissions.householdMissing,
            today = stock.today,
            location = locationId?.let { id -> stock.locations.firstOrNull { it.id == id } },
            locations = stock.locations,
            sections = sections,
            onHand = onHand,
            unpackCount = if (readOnly) 0 else stock.unpack,
            pendingCount = pendingCount(rows, stock.locations.count { it.pending }),
            syncNotice = status.notice,
            refreshing = local.refreshing,
            fridgePhoto = status.fridgePhoto && !readOnly,
            moving = moving,
            locationEditor = local.editor.takeUnless { readOnly },
            confirmDelete = local.deleteId?.let { id -> stock.locations.firstOrNull { it.id == id } }?.takeUnless { readOnly },
        )
    }

    /** Foods marked on hand without stock, and staples on hand (by name, each once). */
    private fun onHandFoods(stock: Stock, stocked: Set<Long>, stockedNames: Set<String>): List<OnHandFood> {
        val stapleKeys = stock.staples.map { it.name.lowercase() }.toSet()
        val fromFoods = stock.onHandFoods
            .filter { food -> food.id !in stocked && food.name.lowercase() !in stockedNames }
            .map { OnHandFood(it.name, it.id, staple = it.name.lowercase() in stapleKeys) }
        val known = fromFoods.map { it.name.lowercase() }.toSet()
        val fromStaples = stock.staples
            .filter { it.onHand && it.name.lowercase() !in known && it.name.lowercase() !in stockedNames }
            .map { OnHandFood(it.name, it.foodId, staple = true) }
        return (fromFoods + fromStaples).sortedBy { it.name.lowercase() }
    }

    private fun row(id: Long): PantryRow? = uiState.value.sections.flatMap { it.rows }.firstOrNull { it.entry.id == id }

    private fun canWrite(): Boolean = !uiState.value.readOnly

    private fun refresh() {
        if (transient.value.refreshing) return
        transient.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = pantry.refresh()
            transient.update { it.copy(refreshing = false) }
            if (result is Outcome.Failure) effectChannel.send(PantryEffect.Failed(result.error))
        }
    }

    private fun markEmpty(row: PantryRow) {
        if (!canWrite()) return
        val entry = row.entry
        write(
            change = { pantry.markEmpty(entry.id) },
            message = PantryMessage.MarkedEmpty(entry.food.name),
            undoWith = { pantry.updateEntry(entry.id, InventoryEntryUpdate(amount = entry.amount)) },
        )
    }

    private fun decrement(row: PantryRow) {
        if (!canWrite()) return
        val entry = row.entry
        val left = (entry.amount - 1.0).coerceAtLeast(0.0)
        write(
            change = { pantry.decrement(entry.id) },
            message = PantryMessage.Decremented(entry.food.name, left, entry.unit?.name),
            undoWith = { pantry.updateEntry(entry.id, InventoryEntryUpdate(amount = entry.amount)) },
        )
    }

    private fun moveTo(target: Long) {
        val moving = uiState.value.moving
        transient.update { it.copy(movingId = null) }
        if (moving == null || !canWrite() || moving.entry.locationId == target) return
        val entry = moving.entry
        val name = uiState.value.locations.firstOrNull { it.id == target }?.name.orEmpty()
        write(
            change = { pantry.updateEntry(entry.id, InventoryEntryUpdate(locationId = target)) },
            message = PantryMessage.Moved(entry.food.name, name),
            undoWith = { pantry.updateEntry(entry.id, InventoryEntryUpdate(locationId = entry.locationId)) },
        )
    }

    private fun removeOnHand(food: OnHandFood) {
        if (!canWrite()) return
        val id = food.foodId
        when {
            food.staple -> write(
                change = { staples.setOnHand(listOf(food.name), false) },
                message = PantryMessage.OnHandRemoved(food.name),
                undoWith = { staples.setOnHand(listOf(food.name), true) },
            )

            id != null -> write(
                change = { supermarkets.setFoodOnHand(id, false) },
                message = PantryMessage.OnHandRemoved(food.name),
                undoWith = { supermarkets.setFoodOnHand(id, true) },
            )
        }
    }

    private fun createDefaults(places: List<Pair<String, Boolean>>) {
        if (!canWrite() || places.isEmpty()) return
        viewModelScope.launch {
            var created = 0
            for ((name, freezer) in places) {
                when (val result = pantry.createLocation(name, freezer)) {
                    is Outcome.Success -> created += 1
                    is Outcome.Failure -> {
                        effectChannel.send(PantryEffect.Failed(result.error))
                        return@launch
                    }
                }
            }
            effectChannel.send(PantryEffect.Show(PantryMessage.LocationsCreated(created)))
        }
    }

    private fun editLocation(id: Long?) {
        if (!canWrite()) return
        val existing = id?.let { key -> uiState.value.locations.firstOrNull { it.id == key } }
        transient.update {
            it.copy(editor = LocationEditor(id = existing?.id, name = existing?.name.orEmpty(), isFreezer = existing?.isFreezer == true))
        }
    }

    private fun saveLocation() {
        val editor = transient.value.editor ?: return
        if (!canWrite() || !editor.canSave) return
        transient.update { it.copy(editor = null) }
        val name = editor.name.trim()
        viewModelScope.launch {
            val result = if (editor.id == null) {
                pantry.createLocation(name, editor.isFreezer)
            } else {
                pantry.renameLocation(editor.id, name, editor.isFreezer)
            }
            when (result) {
                is Outcome.Success -> effectChannel.send(PantryEffect.Show(PantryMessage.LocationSaved(name)))
                is Outcome.Failure -> effectChannel.send(PantryEffect.Failed(result.error))
            }
        }
    }

    private fun deleteLocation(confirmed: Boolean) {
        val target = uiState.value.confirmDelete
        transient.update { it.copy(deleteId = null) }
        if (!confirmed || target == null || !canWrite()) return
        viewModelScope.launch {
            when (val result = pantry.deleteLocation(target.id)) {
                is Outcome.Success -> effectChannel.send(PantryEffect.Show(PantryMessage.LocationDeleted(target.name)))
                is Outcome.Failure -> effectChannel.send(PantryEffect.Failed(result.error))
            }
        }
    }

    /** Runs [change]; on success remembers [undoWith] for "Ångra" and shows [message]. */
    private fun write(change: suspend () -> Outcome<*>, message: PantryMessage, undoWith: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            when (val result = change()) {
                is Outcome.Success -> {
                    undo = undoWith
                    effectChannel.send(PantryEffect.Show(message, undoable = true))
                }

                is Outcome.Failure -> effectChannel.send(PantryEffect.Failed(result.error))
            }
        }
    }

    private fun takeBack() {
        val action = undo ?: return
        undo = null
        if (!canWrite()) return
        viewModelScope.launch {
            val result = action()
            if (result is Outcome.Failure) effectChannel.send(PantryEffect.Failed(result.error))
        }
    }

    private data class Stock(
        val locations: List<InventoryLocation>,
        val groups: List<PantryGroup>,
        val onHandFoods: List<PantryFood>,
        val staples: List<Staple>,
        val today: LocalDate,
        val unpack: Int,
    )

    private data class Status(val permissions: SpacePermissions, val notice: PantrySyncNotice?, val fridgePhoto: Boolean)

    private data class Transient(
        val refreshing: Boolean = false,
        val movingId: Long? = null,
        val editor: LocationEditor? = null,
        val deleteId: Long? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The entries of a place by id (tests and the route). */
internal fun PantryUiState.entry(id: Long): InventoryEntry? = sections.flatMap { it.rows }.firstOrNull { it.entry.id == id }?.entry
