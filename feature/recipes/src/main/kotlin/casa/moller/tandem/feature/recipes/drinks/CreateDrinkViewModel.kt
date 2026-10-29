package casa.moller.tandem.feature.recipes.drinks

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.drink.DrinkProblem
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.feature.recipes.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Ny dryck" (plan §8.8: a minimal sheet; the full recipe editor is Phase 3, the web does the
 * rest): name, types, a note (price, where to buy) and an optional photo. Saved as a recipe with
 * "Dryck" (offline: queued, the id is temporary until it is sent); opened from a dish
 * ([pairWithRecipeId]) the drink is paired with it at once. The form survives process death.
 */
@Immutable
data class CreateDrinkUiState(
    val name: String = "",
    val note: String = "",
    val types: List<String> = emptyList(),
    val selectedTypes: Set<String> = emptySet(),
    val photoUri: String? = null,
    val pairWithName: String? = null,
    val nameMissing: Boolean = false,
    val saving: Boolean = false,
    val readOnly: Boolean = false,
)

sealed interface CreateDrinkAction {
    data class SetName(val name: String) : CreateDrinkAction

    data class SetNote(val note: String) : CreateDrinkAction

    data class ToggleType(val type: String) : CreateDrinkAction

    /** A photo from the camera or the photo picker (`content://`), null removes it. */
    data class SetPhoto(val uri: String?) : CreateDrinkAction

    data object Save : CreateDrinkAction
}

sealed interface CreateDrinkEffect {
    /** Saved: show [text] (with the drink's name) and close the page. */
    data class Saved(@StringRes val text: Int, val name: String) : CreateDrinkEffect

    data class Message(@StringRes val text: Int) : CreateDrinkEffect
}

@HiltViewModel(assistedFactory = CreateDrinkViewModel.Factory::class)
class CreateDrinkViewModel @AssistedInject constructor(
    @Assisted private val pairWithRecipeId: Long?,
    private val handle: SavedStateHandle,
    private val drinks: DrinkRepository,
    recipes: RecipeRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(pairWithRecipeId: Long?): CreateDrinkViewModel
    }

    private val effectChannel = Channel<CreateDrinkEffect>(Channel.BUFFERED)
    val effects: Flow<CreateDrinkEffect> = effectChannel.receiveAsFlow()

    private val progress = MutableStateFlow(Progress())

    private val form = combine(
        handle.getStateFlow(KEY_NAME, ""),
        handle.getStateFlow(KEY_NOTE, ""),
        handle.getStateFlow(KEY_TYPES, emptyArray<String>()),
        handle.getStateFlow<String?>(KEY_PHOTO, null),
    ) { name, note, types, photo -> Form(name, note, types.toSet(), photo) }

    private val pairWithName: Flow<String?> = if (pairWithRecipeId == null) {
        flowOf(null)
    } else {
        recipes.observeStoredRecipe(pairWithRecipeId).map { it.recipe?.name ?: it.overview?.name }
    }

    val uiState: StateFlow<CreateDrinkUiState> = combine(
        form,
        drinks.observeDrinkTypes(),
        pairWithName,
        permissions.permissions.map { it.readOnly },
        progress,
    ) { form, types, pairWith, readOnly, progress ->
        CreateDrinkUiState(
            name = form.name,
            note = form.note,
            types = (types + form.types).distinct(),
            selectedTypes = form.types,
            photoUri = form.photo,
            pairWithName = pairWith,
            nameMissing = progress.nameMissing,
            saving = progress.saving,
            readOnly = readOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CreateDrinkUiState())

    fun onAction(action: CreateDrinkAction) {
        when (action) {
            is CreateDrinkAction.SetName -> {
                handle[KEY_NAME] = action.name
                if (action.name.isNotBlank()) progress.value = progress.value.copy(nameMissing = false)
            }

            is CreateDrinkAction.SetNote -> handle[KEY_NOTE] = action.note

            is CreateDrinkAction.ToggleType -> {
                val types = handle.get<Array<String>>(KEY_TYPES).orEmpty().toSet()
                handle[KEY_TYPES] = (if (action.type in types) types - action.type else types + action.type).toTypedArray()
            }

            is CreateDrinkAction.SetPhoto -> handle[KEY_PHOTO] = action.uri

            CreateDrinkAction.Save -> save()
        }
    }

    private fun save() {
        val state = uiState.value
        if (state.saving) return
        if (state.name.isBlank()) {
            progress.value = progress.value.copy(nameMissing = true)
            return
        }
        progress.value = progress.value.copy(saving = true)
        viewModelScope.launch {
            val draft = DrinkDraft(
                name = state.name.trim(),
                description = state.note.trim(),
                types = state.selectedTypes.toList(),
                imageUri = state.photoUri,
            )
            when (val created = drinks.createDrink(draft)) {
                is Outcome.Failure -> {
                    val nameMissing = DrinkProblem.of(created.error) == DrinkProblem.NAME_EMPTY
                    progress.value = progress.value.copy(saving = false, nameMissing = nameMissing)
                    effectChannel.trySend(CreateDrinkEffect.Message(createFailedMessage(created.error)))
                }

                is Outcome.Success -> {
                    val drink = created.value
                    // Paired with the dish it was created from; a failure here leaves the drink (pair it later).
                    val paired = pairWithRecipeId?.let { drinks.pairWithDrink(it, drink.id) }
                    if (paired is Outcome.Failure) effectChannel.trySend(CreateDrinkEffect.Message(drinkErrorMessage(paired.error)))
                    val text = if (drink.pending) R.string.drinks_create_saved_offline else R.string.drinks_create_saved
                    effectChannel.trySend(CreateDrinkEffect.Saved(text, drink.name))
                }
            }
        }
    }

    private fun createFailedMessage(error: TandemError): Int =
        drinkErrorMessage(error).takeUnless { it == R.string.drinks_change_failed } ?: R.string.drinks_create_failed

    private data class Form(val name: String, val note: String, val types: Set<String>, val photo: String?)

    private data class Progress(val saving: Boolean = false, val nameMissing: Boolean = false)

    private companion object {
        const val KEY_NAME = "drink_name"
        const val KEY_NOTE = "drink_note"
        const val KEY_TYPES = "drink_types"
        const val KEY_PHOTO = "drink_photo"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
