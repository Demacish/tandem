package casa.moller.tandem.feature.pantry.staples

import androidx.compose.runtime.getValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.StaplesRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.Staple
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One staple on the checklist.
 *
 * @property known it is one of the household's staples already (it can be removed from them).
 * @property atHome ticked: marked "har hemma".
 */
data class StapleRow(val name: String, val known: Boolean, val atHome: Boolean)

/**
 * "Basvaror" (plan §8.8): a checklist of common staples (salt, peppar, olja …) and the
 * household's own, to mark as "har hemma"; without them almost no recipe "has all". Shown the
 * first time "Kan laga nu" is opened and from Hemma's menu.
 *
 * @property text a staple being typed ("Lägg till en egen").
 */
data class StaplesUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val rows: List<StapleRow> = emptyList(),
    val text: String = "",
    val saving: Boolean = false,
) {
    val atHomeCount: Int
        get() = rows.count { it.atHome }

    val canSave: Boolean
        get() = !readOnly && !saving && !loading
}

/** What "Basvaror" can ask for. */
sealed interface StaplesAction {
    /** The suggestions in the app's language (string resources, read by the route). */
    data class Suggestions(val names: List<String>) : StaplesAction

    data class Toggle(val name: String) : StaplesAction

    data class TypedText(val text: String) : StaplesAction

    /** Adds what was typed to the list, ticked. */
    data object AddTyped : StaplesAction

    /** Removes one of the household's staples (with "Ångra"). */
    data class Remove(val name: String) : StaplesAction

    data object Undo : StaplesAction

    /** "Spara": the ticks are sent in one batch. */
    data object Save : StaplesAction

    /** "Inte nu" / the back arrow: the list is not offered again by itself. */
    data object Close : StaplesAction
}

/** One-off effects of "Basvaror". */
sealed interface StaplesEffect {
    /** Saved or dismissed: the route goes back. */
    data object Done : StaplesEffect

    data class Removed(val name: String) : StaplesEffect

    data class Failed(val error: TandemError) : StaplesEffect
}

/**
 * See [StaplesUiState]. The ticks are kept locally until "Spara" (in [SavedStateHandle]), then
 * new staples are added (Tandoor finds or creates their foods and marks them on hand), and the
 * changed ones are marked on hand or not in one batch each (`food/batch_update/`).
 */
@HiltViewModel
class StaplesViewModel @Inject internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val staples: StaplesRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {

    private val effectChannel = Channel<StaplesEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<StaplesEffect> = effectChannel.receiveAsFlow()

    private val suggestions = MutableStateFlow<List<String>>(emptyList())
    private val saving = MutableStateFlow(false)

    /** What "Ångra" puts back after removing a staple. */
    private var removed: Staple? = null

    private val local = combine(
        savedStateHandle.getStateFlow(KEY_TICKED, emptyArray<String>()),
        savedStateHandle.getStateFlow(KEY_UNTICKED, emptyArray<String>()),
        savedStateHandle.getStateFlow(KEY_ADDED, emptyArray<String>()),
        savedStateHandle.getStateFlow(KEY_TEXT, ""),
    ) { ticked, unticked, added, text -> Local(ticked.toSet(), unticked.toSet(), added.toList(), text) }

    val uiState: StateFlow<StaplesUiState> = combine(
        staples.observeStaples(),
        suggestions,
        local,
        permissions.permissions,
        saving,
    ) { known, suggested, local, perms, busy ->
        StaplesUiState(
            loading = false,
            readOnly = perms.readOnly,
            rows = rows(known, suggested, local),
            text = local.text,
            saving = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StaplesUiState())

    fun onAction(action: StaplesAction) {
        when (action) {
            is StaplesAction.Suggestions -> suggestions.value = action.names
            is StaplesAction.Toggle -> toggle(action.name)
            is StaplesAction.TypedText -> savedStateHandle[KEY_TEXT] = action.text
            StaplesAction.AddTyped -> addTyped()
            is StaplesAction.Remove -> remove(action.name)
            StaplesAction.Undo -> undoRemove()
            StaplesAction.Save -> save()
            StaplesAction.Close -> close()
        }
    }

    /** The household's staples first (by name), then the suggestions not among them, then typed ones. */
    private fun rows(known: List<Staple>, suggested: List<String>, local: Local): List<StapleRow> {
        val byKey = known.associateBy { it.name.lowercase() }
        val names = (known.map { it.name } + local.added + suggested).distinctBy { it.trim().lowercase() }
        return names.map { name ->
            val key = name.trim().lowercase()
            val staple = byKey[key]
            val atHome = when (key) {
                in local.ticked -> true
                in local.unticked -> false
                else -> staple?.onHand == true || key in local.added.map { it.lowercase() }
            }
            StapleRow(name = staple?.name ?: name.trim(), known = staple != null, atHome = atHome)
        }
    }

    private fun toggle(name: String) {
        if (uiState.value.readOnly) return
        val key = name.trim().lowercase()
        val row = uiState.value.rows.firstOrNull { it.name.lowercase() == key } ?: return
        val ticked = savedStateHandle.get<Array<String>>(KEY_TICKED).orEmpty().toMutableSet()
        val unticked = savedStateHandle.get<Array<String>>(KEY_UNTICKED).orEmpty().toMutableSet()
        if (row.atHome) {
            ticked -= key
            unticked += key
        } else {
            unticked -= key
            ticked += key
        }
        savedStateHandle[KEY_TICKED] = ticked.toTypedArray()
        savedStateHandle[KEY_UNTICKED] = unticked.toTypedArray()
    }

    private fun addTyped() {
        val text = savedStateHandle.get<String>(KEY_TEXT).orEmpty().trim()
        if (text.isEmpty() || uiState.value.readOnly) return
        val added = savedStateHandle.get<Array<String>>(KEY_ADDED).orEmpty().toList()
        if (uiState.value.rows.none { it.name.equals(text, ignoreCase = true) }) {
            savedStateHandle[KEY_ADDED] = (added + text).toTypedArray()
        } else {
            val key = text.lowercase()
            savedStateHandle[KEY_TICKED] = (savedStateHandle.get<Array<String>>(KEY_TICKED).orEmpty().toSet() + key).toTypedArray()
            savedStateHandle[KEY_UNTICKED] = (savedStateHandle.get<Array<String>>(KEY_UNTICKED).orEmpty().toSet() - key).toTypedArray()
        }
        savedStateHandle[KEY_TEXT] = ""
    }

    private fun remove(name: String) {
        if (uiState.value.readOnly) return
        viewModelScope.launch {
            val staple = staples.observeStaplesOnce().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@launch
            when (val result = staples.removeStaple(staple.name)) {
                is Outcome.Success -> {
                    removed = staple
                    effectChannel.send(StaplesEffect.Removed(staple.name))
                }

                is Outcome.Failure -> effectChannel.send(StaplesEffect.Failed(result.error))
            }
        }
    }

    private fun undoRemove() {
        val staple = removed ?: return
        removed = null
        if (uiState.value.readOnly) return
        viewModelScope.launch {
            val added = staples.addStaples(listOf(staple.name))
            val result = if (added is Outcome.Success && !staple.onHand) staples.setOnHand(listOf(staple.name), false) else added
            if (result is Outcome.Failure) effectChannel.send(StaplesEffect.Failed(result.error))
        }
    }

    private fun save() {
        val state = uiState.value
        if (!state.canSave) return
        saving.value = true
        viewModelScope.launch {
            val known = staples.observeStaplesOnce().associateBy { it.name.lowercase() }
            val toAdd = state.rows.filter { it.atHome && it.name.lowercase() !in known }.map { it.name }
            val toOnHand = state.rows.filter { row -> row.atHome && known[row.name.lowercase()]?.onHand == false }.map { it.name }
            val toOff = state.rows.filter { row -> !row.atHome && known[row.name.lowercase()]?.onHand == true }.map { it.name }
            val steps = listOf<suspend () -> Outcome<Unit>>(
                { if (toAdd.isEmpty()) Outcome.Success(Unit) else staples.addStaples(toAdd) },
                { if (toOnHand.isEmpty()) Outcome.Success(Unit) else staples.setOnHand(toOnHand, true) },
                { if (toOff.isEmpty()) Outcome.Success(Unit) else staples.setOnHand(toOff, false) },
                { staples.markSuggestionsShown() },
            )
            for (step in steps) {
                val result = step()
                if (result is Outcome.Failure) {
                    saving.value = false
                    effectChannel.send(StaplesEffect.Failed(result.error))
                    return@launch
                }
            }
            saving.value = false
            clearLocal()
            effectChannel.send(StaplesEffect.Done)
        }
    }

    /** Leaving without saving still counts as "shown": the list is not pushed on the user again. */
    private fun close() {
        viewModelScope.launch {
            staples.markSuggestionsShown()
            effectChannel.send(StaplesEffect.Done)
        }
    }

    private fun clearLocal() {
        savedStateHandle[KEY_TICKED] = emptyArray<String>()
        savedStateHandle[KEY_UNTICKED] = emptyArray<String>()
        savedStateHandle[KEY_ADDED] = emptyArray<String>()
    }

    private suspend fun StaplesRepository.observeStaplesOnce(): List<Staple> = observeStaples().first()

    private data class Local(val ticked: Set<String>, val unticked: Set<String>, val added: List<String>, val text: String)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_TICKED = "staples-ticked"
        const val KEY_UNTICKED = "staples-unticked"
        const val KEY_ADDED = "staples-added"
        const val KEY_TEXT = "staples-text"
    }
}
