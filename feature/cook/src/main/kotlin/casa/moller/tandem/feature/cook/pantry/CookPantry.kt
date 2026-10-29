package casa.moller.tandem.feature.cook.pantry

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryEntryUpdate
import casa.moller.tandem.core.ui.pantry.ExpiryLabel
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.core.ui.pantry.expiryText
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.pantry.formatPantryAmount
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.cook.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

// Fas S (hemma-ui lane): "Tog något slut?" after "Klart!" (plan §8.8 "Efter Klart!"): the recipe's
// ingredients that are in the pantry, to set to 0 ("Slut") or one less. Never automatic: units of a
// recipe and of the pantry rarely match, so the cook decides. Hidden for a guest and when nothing of
// the recipe is in the pantry.

/** A pantry entry of one of the recipe's foods. */
data class UsedUpRow(val entry: InventoryEntry, val locationName: String, val urgency: ExpiryUrgency)

/** What "Tog något slut?" shows. */
data class CookPantryState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val today: LocalDate? = null,
    val rows: List<UsedUpRow> = emptyList(),
) {
    val visible: Boolean
        get() = !loading && !readOnly && rows.isNotEmpty()
}

sealed interface CookPantryAction {
    data class MarkEmpty(val entryId: Long) : CookPantryAction

    data class Decrement(val entryId: Long) : CookPantryAction

    data object Undo : CookPantryAction
}

sealed interface CookPantryEffect {
    /** [name] now has [amount] left (0 = slut); "Ångra" undoes it. */
    data class Changed(val name: String, val amount: Double, val unit: String?) : CookPantryEffect

    data class Failed(val error: TandemError) : CookPantryEffect
}

/** See [CookPantryState]; one "Ångra" for the last change. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CookPantryViewModel.Factory::class)
class CookPantryViewModel @AssistedInject internal constructor(
    @Assisted recipeId: Long,
    recipes: RecipeRepository,
    private val pantry: PantryRepository,
    permissions: SpacePermissionsRepository,
    calendar: CalendarDays,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): CookPantryViewModel
    }

    private val effectChannel = Channel<CookPantryEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<CookPantryEffect> = effectChannel.receiveAsFlow()

    private var undo: (suspend () -> Outcome<*>)? = null

    private val foodIds: Flow<Set<Long>> = recipes.observeStoredRecipe(recipeId)
        .map { state -> state.recipe?.ingredients.orEmpty().mapNotNull { it.food?.id?.takeIf { id -> id > 0 } }.toSet() }
        .distinctUntilChanged()

    val uiState: StateFlow<CookPantryState> = combine(
        foodIds.flatMapLatest { ids -> pantry.observePantry().map { groups -> ids to groups } },
        permissions.permissions,
        calendar.observeToday(),
    ) { (ids, groups), perms, today ->
        val rows = groups.flatMap { group ->
            group.entries
                .filter { it.food.id in ids && !it.isEmpty }
                .map { UsedUpRow(it, group.location.name, ExpiryUrgency.of(it.expires, today)) }
        }
        CookPantryState(loading = false, readOnly = perms.readOnly, today = today, rows = rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CookPantryState())

    fun onAction(action: CookPantryAction) {
        when (action) {
            is CookPantryAction.MarkEmpty -> change(action.entryId, empty = true)
            is CookPantryAction.Decrement -> change(action.entryId, empty = false)
            CookPantryAction.Undo -> takeBack()
        }
    }

    private fun change(id: Long, empty: Boolean) {
        val state = uiState.value
        if (state.readOnly) return
        val entry = state.rows.firstOrNull { it.entry.id == id }?.entry ?: return
        viewModelScope.launch {
            val result = if (empty) pantry.markEmpty(id) else pantry.decrement(id)
            when (result) {
                is Outcome.Success -> {
                    undo = { pantry.updateEntry(id, InventoryEntryUpdate(amount = entry.amount)) }
                    val left = if (empty) 0.0 else (entry.amount - 1.0).coerceAtLeast(0.0)
                    effectChannel.send(CookPantryEffect.Changed(entry.food.name, left, entry.unit?.name))
                }

                is Outcome.Failure -> effectChannel.send(CookPantryEffect.Failed(result.error))
            }
        }
    }

    private fun takeBack() {
        val action = undo ?: return
        undo = null
        viewModelScope.launch {
            val result = action()
            if (result is Outcome.Failure) effectChannel.send(CookPantryEffect.Failed(result.error))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** "Tog något slut?" for [recipeId] on the "Klart!" page; its messages go to cook mode's [snackbarHostState]. */
@Composable
fun CookPantryRoute(recipeId: Long, snackbarHostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<CookPantryViewModel, CookPantryViewModel.Factory>(
        key = "cook-pantry-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                when (effect) {
                    is CookPantryEffect.Changed -> {
                        val result = snackbarHostState.showSnackbar(
                            message = resources.changedText(effect),
                            actionLabel = resources.getString(R.string.cook_pantry_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.onAction(CookPantryAction.Undo)
                    }

                    is CookPantryEffect.Failed -> snackbarHostState.showSnackbar(resources.failedText(effect.error))
                }
            }
        }
    }
    CookPantryCard(state = state, onAction = viewModel::onAction, modifier = modifier)
}

private fun Resources.changedText(effect: CookPantryEffect.Changed): String {
    val locale = configuration.locales[0]
    val name = foodTitle(effect.name, locale)
    return if (effect.amount <= 0.0) {
        getString(R.string.cook_pantry_empty, name)
    } else {
        getString(R.string.cook_pantry_left, name, formatPantryAmount(effect.amount, effect.unit, locale))
    }
}

private fun Resources.failedText(error: TandemError): String =
    getString(if (error.dataProblem() == DataProblem.READ_ONLY) R.string.cook_pantry_read_only else R.string.cook_pantry_failed)

/** Test tag of the card. */
const val COOK_PANTRY_TAG = "cook:pantry"

/**
 * The card, folded by default ("Tog något slut?" with how many of the recipe's foods are in the
 * pantry and "Visa"); unfolded, each entry with "−1" and "Slut". Each row is one TalkBack item
 * ("Grädde, 2 dl, Kyl") with the two as actions.
 */
@Composable
internal fun CookPantryCard(
    state: CookPantryState,
    onAction: (CookPantryAction) -> Unit,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
) {
    if (!state.visible) return
    val spacing = TandemTheme.spacing
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().testTag(COOK_PANTRY_TAG),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Column(modifier = Modifier.padding(horizontal = spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    text = stringResource(R.string.cook_pantry_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.cook_pantry_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TandemTextButton(
                text = if (open) {
                    stringResource(R.string.cook_pantry_hide)
                } else {
                    pluralStringResource(R.plurals.cook_pantry_show, state.rows.size, state.rows.size)
                },
                onClick = { open = !open },
                trailingIcon = TandemIcons.ExpandMore,
                modifier = Modifier
                    .padding(horizontal = spacing.sm)
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
            )
            if (open) {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    UsedUpItem(row = row, today = state.today, onAction = onAction)
                }
            }
        }
    }
}

@Composable
private fun UsedUpItem(row: UsedUpRow, today: LocalDate?, onAction: (CookPantryAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val locale = currentLocale()
    val entry = row.entry
    val name = foodTitle(entry.food.name, locale)
    val amount = formatPantryAmount(entry.amount, entry.unit?.name, locale)
    val expiry = if (today != null) entry.expires?.let { expiryText(it, today) } else null
    val description = listOfNotNull(name, amount, row.locationName, expiry).joinToString(", ")
    val emptyLabel = stringResource(R.string.cook_pantry_action_empty)
    val lessLabel = stringResource(R.string.cook_pantry_action_less)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .clearAndSetSemantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(emptyLabel) {
                        onAction(CookPantryAction.MarkEmpty(entry.id))
                        true
                    },
                    CustomAccessibilityAction(lessLabel) {
                        onAction(CookPantryAction.Decrement(entry.id))
                        true
                    },
                )
            }
            .padding(start = spacing.lg, end = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "$amount · ${row.locationName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expiry != null) ExpiryLabel(text = expiry, urgency = row.urgency)
        }
        TandemTextButton(
            text = stringResource(R.string.cook_pantry_action_less_short),
            onClick = { onAction(CookPantryAction.Decrement(entry.id)) },
            modifier = Modifier
                .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                .widthIn(min = TandemTheme.sizes.kitchenTouchTarget),
        )
        TandemTextButton(
            text = emptyLabel,
            onClick = { onAction(CookPantryAction.MarkEmpty(entry.id)) },
            modifier = Modifier.heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
        )
    }
}
