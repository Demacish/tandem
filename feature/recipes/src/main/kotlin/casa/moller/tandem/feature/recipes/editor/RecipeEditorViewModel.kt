package casa.moller.tandem.feature.recipes.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.data.recipe.RecipeEditRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.domain.shopping.LocalIngredientParser
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.map
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.recipeimport.ImportFiles
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The recipe editor (Fas 3, redigerare lane; #22, plan §8.9): a new recipe, or a stored one edited
 * in place. The draft lives in [SavedStateHandle] (as JSON, [SavedDraft]) from the moment it is
 * started, so a half-written recipe survives process death and a rotation. A stored recipe is
 * read once from [RecipeEditRepository.observeDraft] (the edit kept after a conflict, else the
 * stored copy); until then the form is not shown. Ingredient lines typed as text ("2 dl grädde,
 * kall") are split by core:domain's [LocalIngredientParser] with the stored units. Saving stores
 * the recipe at once and queues it (offline: the id is temporary until it is sent).
 *
 * What the form opened with stays beside the draft (the baseline), so leaving with changes can ask
 * first; an import's baseline is an empty recipe, because the import page is gone. A removed step
 * or line with content has "Ångra". The file an import was read from belongs to this page: it is
 * shown with the form and deleted when the page closes.
 */
@HiltViewModel(assistedFactory = RecipeEditorViewModel.Factory::class)
class RecipeEditorViewModel @AssistedInject constructor(
    @Assisted private val recipeId: Long?,
    @Assisted("importedDraft") private val importedDraft: String?,
    @Assisted("sourcePath") private val sourcePath: String?,
    private val handle: SavedStateHandle,
    private val edits: RecipeEditRepository,
    recipes: RecipeRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        /**
         * [importedDraft] (Fas 3 import lane): a new recipe's starting point as `SavedDraft` JSON, else
         * null; [sourcePath] the photo or PDF it was read from.
         */
        fun create(
            recipeId: Long?,
            @Assisted("importedDraft") importedDraft: String?,
            @Assisted("sourcePath") sourcePath: String?,
        ): RecipeEditorViewModel
    }

    private val effectChannel = Channel<RecipeEditorEffect>(Channel.BUFFERED)
    val effects: Flow<RecipeEditorEffect> = effectChannel.receiveAsFlow()

    private val progress = MutableStateFlow(Progress())
    private val amountTexts = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The draft, null until started (a new recipe starts empty at once). */
    private val draft: StateFlow<RecipeDraft?> = handle.getStateFlow<String?>(KEY_DRAFT, null)
        .map { SavedDraft.decode(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SavedDraft.decode(handle[KEY_DRAFT]))

    /** What the form opened with, as stored: leaving with anything else asks first. */
    private val baseline: StateFlow<String?> = handle.getStateFlow(KEY_BASELINE, null)

    /** The draft before the last removal, for "Ångra". */
    private var removed: Removal? = null

    /** The last row key handed out ([withRowKeys]). */
    private var lastRowKey = 0L

    private val units: Flow<List<String>> = edits.observeUnits().map { list -> list.map { it.name } }

    private val foods: Flow<List<String>> = recipes.observeFoods().map { list -> list.map { it.name }.distinct() }

    val uiState: StateFlow<RecipeEditorUiState> = combine(
        draft,
        units,
        recipes.observeKeywords(),
        if (recipeId == null) flowOf(RecipeEditStatus.NONE) else edits.observeEditStatus(recipeId),
        combine(permissions.permissions.map { it.readOnly }, progress, amountTexts, foods, baseline, ::EditorExtras),
    ) { draft, units, keywords, status, extras ->
        val progress = extras.progress
        val current = draft ?: RecipeDraft()
        val onRecipe = current.keywords.map { it.name.lowercase() }.toSet()
        RecipeEditorUiState(
            loaded = draft != null,
            isNew = recipeId == null,
            draft = current,
            amountTexts = extras.amounts,
            units = units,
            keywordSuggestions = keywords.filter { it.name.lowercase() !in onRecipe }.map { DraftKeyword(it.id, it.name) },
            keptEdit = status == RecipeEditStatus.CONFLICT && !progress.discarded,
            nameMissing = progress.nameMissing,
            unknownRecipe = progress.unknownRecipe,
            saving = progress.saving,
            readOnly = extras.readOnly,
            foods = extras.foods,
            hasChanges = draft != null && !extras.readOnly && SavedDraft.encode(current.withoutRowKeys()) != extras.baseline,
            sourcePath = sourcePath,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeEditorUiState(isNew = recipeId == null))

    init {
        if (draft.value == null) {
            if (recipeId == null) {
                handle[KEY_BASELINE] = SavedDraft.encode(RecipeDraft())
                store(SavedDraft.decode(importedDraft) ?: RecipeDraft())
            } else {
                viewModelScope.launch {
                    val stored = edits.observeDraft(recipeId).first()
                    when {
                        stored == null -> progress.value = progress.value.copy(unknownRecipe = true)
                        draft.value == null -> open(stored)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        ImportFiles.delete(sourcePath)
    }

    /** [stored] is what the form opens with. */
    private fun open(stored: RecipeDraft) {
        handle[KEY_BASELINE] = SavedDraft.encode(stored)
        store(stored)
    }

    /** The form's new photo right now (from the saved state, also before [uiState] has its first value after process death). */
    internal val currentPhoto: String?
        get() = draft.value?.imageUri

    fun onAction(action: RecipeEditorAction) {
        when (action) {
            is RecipeEditorAction.SetName -> {
                edit { it.copy(name = action.name) }
                if (action.name.isNotBlank()) progress.value = progress.value.copy(nameMissing = false)
            }

            is RecipeEditorAction.SetDescription -> edit { it.copy(description = action.description) }

            is RecipeEditorAction.SetServings -> edit { it.copy(servings = action.servings.coerceIn(1, MAX_SERVINGS)) }

            is RecipeEditorAction.SetServingsText -> edit { it.copy(servingsText = action.text) }

            is RecipeEditorAction.SetWorkingMinutes -> edit { it.copy(workingMinutes = action.minutes.coerceIn(0, MAX_MINUTES)) }

            is RecipeEditorAction.SetWaitingMinutes -> edit { it.copy(waitingMinutes = action.minutes.coerceIn(0, MAX_MINUTES)) }

            is RecipeEditorAction.SetSourceUrl -> edit { it.copy(sourceUrl = action.url) }

            is RecipeEditorAction.AddKeyword -> edit { current ->
                val name = action.keyword.name.trim()
                if (name.isEmpty() || current.keywords.any { it.name.equals(name, ignoreCase = true) }) {
                    current
                } else {
                    current.copy(keywords = current.keywords + action.keyword.copy(name = name))
                }
            }

            is RecipeEditorAction.RemoveKeyword -> edit { current ->
                current.copy(keywords = current.keywords.filterIndexed { index, _ -> index != action.index })
            }

            RecipeEditorAction.AddStep -> edit { it.copy(steps = it.steps + DraftStep()) }

            is RecipeEditorAction.RemoveStep -> removeStep(action.step)

            RecipeEditorAction.UndoRemove -> undoRemove()

            is RecipeEditorAction.MoveStep -> moveStep(action.step, action.up)

            is RecipeEditorAction.SetStepName -> editStep(action.step) { it.copy(name = action.name) }

            is RecipeEditorAction.SetStepInstruction -> editStep(action.step) { it.copy(instruction = action.instruction) }

            is RecipeEditorAction.SetStepMinutes -> editStep(action.step) { it.copy(timeMinutes = action.minutes.coerceIn(0, MAX_MINUTES)) }

            is RecipeEditorAction.AddIngredient -> addIngredient(action.step, action.text)

            is RecipeEditorAction.AddHeader -> editStep(action.step) { step ->
                step.copy(ingredients = step.ingredients + DraftIngredient(isHeader = true))
            }

            is RecipeEditorAction.RemoveIngredient -> removeIngredient(action.step, action.ingredient)

            is RecipeEditorAction.MoveIngredient -> moveIngredient(action.step, action.ingredient, action.up)

            is RecipeEditorAction.SetIngredientAmount -> setAmount(action.step, action.ingredient, action.text)

            is RecipeEditorAction.SetIngredientUnit -> editIngredient(action.step, action.ingredient) { it.copy(unit = action.unit) }

            is RecipeEditorAction.SetIngredientFood -> editIngredient(action.step, action.ingredient) { it.copy(food = action.food) }

            is RecipeEditorAction.SetIngredientNote -> editIngredient(action.step, action.ingredient) { it.copy(note = action.note) }

            is RecipeEditorAction.ToggleNoAmount -> editIngredient(action.step, action.ingredient) { it.copy(noAmount = !it.noAmount) }

            is RecipeEditorAction.SetPhoto -> edit { it.copy(imageUri = action.uri) }

            RecipeEditorAction.RemoveWebImage -> edit { it.copy(imageUrl = null) }

            is RecipeEditorAction.KeepImage -> edit { it.copy(keepImage = action.keep) }

            RecipeEditorAction.DiscardKeptEdit -> discardKeptEdit()

            RecipeEditorAction.Save -> save()
        }
    }

    private fun moveStep(step: Int, up: Boolean) {
        val target = if (up) step - 1 else step + 1
        val current = draft.value ?: return
        if (step !in current.steps.indices || target !in current.steps.indices) return
        edit { it.copy(steps = it.steps.moved(step, up)) }
        amountTexts.value = emptyMap()
        effectChannel.trySend(RecipeEditorEffect.Announce(R.string.recipe_editor_step_moved, step + 1, target + 1))
    }

    private fun moveIngredient(step: Int, ingredient: Int, up: Boolean) {
        val target = if (up) ingredient - 1 else ingredient + 1
        val lines = draft.value?.steps?.getOrNull(step)?.ingredients ?: return
        if (ingredient !in lines.indices || target !in lines.indices) return
        editStep(step) { it.copy(ingredients = it.ingredients.moved(ingredient, up)) }
        amountTexts.value = amountTexts.value.filterKeys { !it.startsWith("$step:") }
        effectChannel.trySend(RecipeEditorEffect.Announce(R.string.recipe_editor_ingredient_moved, ingredient + 1, target + 1))
    }

    /** A step goes at once; with anything in it, "Ångra" brings it back with its lines. */
    private fun removeStep(step: Int) {
        val current = draft.value ?: return
        val gone = current.steps.getOrNull(step) ?: return
        removed = Removal(current, amountTexts.value)
        edit { it.copy(steps = it.steps.filterIndexed { index, _ -> index != step }) }
        amountTexts.value = emptyMap()
        val blank = gone.name.isBlank() && gone.instruction.isBlank() && gone.timeMinutes == 0 && gone.ingredients.isEmpty()
        effectChannel.trySend(
            if (blank) {
                RecipeEditorEffect.Announce(R.string.recipe_editor_step_removed, step + 1)
            } else {
                RecipeEditorEffect.Removed(R.string.recipe_editor_step_removed, step + 1)
            },
        )
    }

    private fun removeIngredient(step: Int, ingredient: Int) {
        val current = draft.value ?: return
        val gone = current.steps.getOrNull(step)?.ingredients?.getOrNull(ingredient) ?: return
        removed = Removal(current, amountTexts.value)
        editStep(step) { it.copy(ingredients = it.ingredients.filterIndexed { index, _ -> index != ingredient }) }
        amountTexts.value = amountTexts.value.filterKeys { !it.startsWith("$step:") }
        val blank = gone.food.isNullOrBlank() && gone.unit.isNullOrBlank() && gone.note.isBlank() && gone.amount == 0.0
        effectChannel.trySend(
            if (blank) {
                RecipeEditorEffect.Announce(R.string.recipe_editor_ingredient_removed, ingredient + 1, step + 1)
            } else {
                RecipeEditorEffect.Removed(R.string.recipe_editor_ingredient_removed, ingredient + 1, step + 1)
            },
        )
    }

    private fun undoRemove() {
        val removal = removed ?: return
        removed = null
        store(removal.draft)
        amountTexts.value = removal.amounts
    }

    private fun addIngredient(step: Int, text: String) {
        val parsed = text.takeIf { it.isNotBlank() }?.let { LocalIngredientParser.parse(it, uiState.value.units) }
        val line = when {
            parsed != null -> DraftIngredient(
                amount = parsed.amount,
                unit = parsed.unitName,
                food = parsed.foodName,
                note = parsed.note,
                noAmount = parsed.amount == 0.0,
            )

            text.isNotBlank() -> DraftIngredient(food = text.trim(), noAmount = true)

            else -> DraftIngredient()
        }
        editStep(step) { it.copy(ingredients = it.ingredients + line) }
    }

    private fun setAmount(step: Int, ingredient: Int, text: String) {
        val key = RecipeEditorUiState.amountKey(step, ingredient)
        amountTexts.value = amountTexts.value + (key to text)
        val amount = text.trim().replace(',', '.').toDoubleOrNull()
        if (text.isBlank()) {
            editIngredient(step, ingredient) { it.copy(amount = 0.0) }
        } else if (amount != null && amount >= 0) {
            editIngredient(step, ingredient) { it.copy(amount = amount, noAmount = false) }
        }
    }

    private fun discardKeptEdit() {
        val id = recipeId ?: return
        viewModelScope.launch {
            when (val result = edits.discardEdit(id)) {
                is Outcome.Failure -> effectChannel.trySend(RecipeEditorEffect.Message(R.string.recipe_editor_failed))

                is Outcome.Success -> {
                    progress.value = progress.value.copy(discarded = true)
                    edits.observeDraft(id).first()?.let { open(it) }
                    amountTexts.value = emptyMap()
                    effectChannel.trySend(RecipeEditorEffect.Message(R.string.recipe_editor_kept_edit_discarded))
                }
            }
        }
    }

    private fun save() {
        val state = uiState.value
        if (state.saving || !state.loaded) return
        val draft = state.draft.withTrimmedFoods().withoutRowKeys()
        if (draft.name.isBlank()) {
            progress.value = progress.value.copy(nameMissing = true)
            return
        }
        progress.value = progress.value.copy(saving = true)
        viewModelScope.launch {
            val id = recipeId
            val result = if (id == null) edits.createRecipe(draft) else edits.updateRecipe(id, draft).map { id }
            when (result) {
                is Outcome.Failure -> {
                    val problem = RecipeEditProblem.of(result.error)
                    progress.value = progress.value.copy(saving = false, nameMissing = problem == RecipeEditProblem.NAME_EMPTY)
                    effectChannel.trySend(
                        RecipeEditorEffect.Message(
                            when (problem) {
                                RecipeEditProblem.PHOTO_UNREADABLE -> R.string.recipe_editor_photo_unreadable
                                RecipeEditProblem.UNKNOWN_RECIPE -> R.string.recipe_editor_unknown_recipe
                                else -> R.string.recipe_editor_failed
                            },
                        ),
                    )
                }

                is Outcome.Success -> {
                    val savedId = result.value
                    val text = if (savedId < 0 || (id != null && edits.observeEditStatus(id).first() == RecipeEditStatus.QUEUED)) {
                        R.string.recipe_editor_saved_offline
                    } else {
                        R.string.recipe_editor_saved
                    }
                    effectChannel.trySend(RecipeEditorEffect.Saved(text, draft.name.trim(), savedId, isNew = id == null))
                }
            }
        }
    }

    /** Foods go by name: "Vispgrädde " would be another food in the library than "Vispgrädde". */
    private fun RecipeDraft.withTrimmedFoods(): RecipeDraft = copy(
        steps = steps.map { step -> step.copy(ingredients = step.ingredients.map { it.copy(food = it.food?.trim()) }) },
    )

    private fun edit(change: (RecipeDraft) -> RecipeDraft) {
        val current = draft.value ?: return
        store(change(current))
    }

    private fun editStep(step: Int, change: (DraftStep) -> DraftStep) = edit { current ->
        if (step !in current.steps.indices) {
            current
        } else {
            current.copy(steps = current.steps.mapIndexed { i, s -> if (i == step) change(s) else s })
        }
    }

    private fun editIngredient(step: Int, ingredient: Int, change: (DraftIngredient) -> DraftIngredient) = editStep(step) { current ->
        current.copy(ingredients = current.ingredients.mapIndexed { i, line -> if (i == ingredient) change(line) else line })
    }

    /** Every draft passes here, so a row has its key from the first frame it is shown (and after process death). */
    private fun store(draft: RecipeDraft) {
        val keyed = draft.withRowKeys(lastRowKey)
        lastRowKey = maxOf(lastRowKey, keyed.lastRowKey)
        handle[KEY_DRAFT] = SavedDraft.encode(keyed)
    }

    private fun <T> List<T>.moved(index: Int, up: Boolean): List<T> {
        val target = if (up) index - 1 else index + 1
        if (index !in indices || target !in indices) return this
        return toMutableList().apply { add(target, removeAt(index)) }
    }

    /** The form before a removal. */
    private data class Removal(val draft: RecipeDraft, val amounts: Map<String, String>)

    private data class EditorExtras(
        val readOnly: Boolean,
        val progress: Progress,
        val amounts: Map<String, String>,
        val foods: List<String>,
        val baseline: String?,
    )

    private data class Progress(
        val saving: Boolean = false,
        val nameMissing: Boolean = false,
        val unknownRecipe: Boolean = false,
        val discarded: Boolean = false,
    )

    private companion object {
        const val KEY_DRAFT = "recipe_editor_draft"
        const val KEY_BASELINE = "recipe_editor_baseline"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_SERVINGS = 999
        const val MAX_MINUTES = 100_000
    }
}
