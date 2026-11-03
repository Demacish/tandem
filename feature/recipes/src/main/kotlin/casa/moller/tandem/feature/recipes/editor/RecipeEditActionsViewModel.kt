package casa.moller.tandem.feature.recipes.editor

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeEditRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import casa.moller.tandem.feature.recipes.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the recipe detail shows of the editor (Fas 3, redigerare lane): "Redigera" and "Ta bort"
 * in its menu, and the notice of an edit of this device still to send, held back by a conflict or
 * refused.
 *
 * @property canEdit every member may edit (a guest sees nothing of it).
 * @property status where this device's edit of the recipe stands.
 */
@Immutable
data class RecipeEditActionsState(val canEdit: Boolean = false, val status: RecipeEditStatus = RecipeEditStatus.NONE)

sealed interface RecipeEditActionsEffect {
    /** The recipe was deleted: say so (with its name) and leave the detail. */
    data class Deleted(val name: String) : RecipeEditActionsEffect

    data class Message(@StringRes val text: Int) : RecipeEditActionsEffect
}

/** The detail's own ViewModel for the editor's entrances, beside `RecipeDetailViewModel` (which stays as it is). */
@HiltViewModel(assistedFactory = RecipeEditActionsViewModel.Factory::class)
class RecipeEditActionsViewModel @AssistedInject constructor(
    @Assisted private val recipeId: Long,
    private val edits: RecipeEditRepository,
    private val recipes: RecipeRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): RecipeEditActionsViewModel
    }

    private val effectChannel = Channel<RecipeEditActionsEffect>(Channel.BUFFERED)
    val effects: Flow<RecipeEditActionsEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<RecipeEditActionsState> = combine(
        permissions.permissions.map { it.canEditRecipes },
        edits.observeEditStatus(recipeId),
    ) { canEdit, status -> RecipeEditActionsState(canEdit, status) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeEditActionsState())

    /** "Ta bort" (confirmed by the screen): the recipe goes at once, the server is told when online. */
    fun delete() {
        viewModelScope.launch {
            val state = recipes.observeStoredRecipe(recipeId).first()
            val name = state.recipe?.name ?: state.overview?.name.orEmpty()
            when (edits.deleteRecipe(recipeId)) {
                is Outcome.Failure -> effectChannel.trySend(RecipeEditActionsEffect.Message(R.string.recipe_detail_delete_failed))
                is Outcome.Success -> effectChannel.trySend(RecipeEditActionsEffect.Deleted(name))
            }
        }
    }

    /** "Släng min ändring" after a conflict: the web's version stays. */
    fun discardEdit() {
        viewModelScope.launch {
            if (edits.discardEdit(recipeId) is Outcome.Failure) {
                effectChannel.trySend(RecipeEditActionsEffect.Message(R.string.recipe_detail_delete_failed))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
