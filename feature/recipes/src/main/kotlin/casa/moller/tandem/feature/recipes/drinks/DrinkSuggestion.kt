package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.WineBar
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.drink.RecipePairings
import casa.moller.tandem.core.ui.drink.DrinkSuggestionSlot
import casa.moller.tandem.feature.recipes.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The drink suggestion under a planned recipe in Idag and Veckan (plan §8.8): the drinks and kinds
 * of drink it goes with. [drinkId] is the first drink that can be opened (not one still waiting to
 * be sent), [drinkName] its name, null for kinds only.
 */
@Immutable
data class DrinkSuggestionState(val names: List<String> = emptyList(), val drinkId: Long? = null, val drinkName: String? = null)

/** [DrinkSuggestionState] of a recipe's pairings (pure; the opened drink is the first one already sent). */
internal fun RecipePairings.toSuggestion(): DrinkSuggestionState {
    val openable = drinks.firstOrNull { !it.pending }
    return DrinkSuggestionState(
        names = drinks.map { it.name } + pairings.filter { it.isTypeOnly }.map { it.name },
        drinkId = openable?.id,
        drinkName = openable?.name,
    )
}

@HiltViewModel(assistedFactory = DrinkSuggestionViewModel.Factory::class)
class DrinkSuggestionViewModel @AssistedInject constructor(@Assisted recipeId: Long, drinks: DrinkRepository) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): DrinkSuggestionViewModel
    }

    val uiState: StateFlow<DrinkSuggestionState> = drinks.observeRecipePairings(recipeId)
        .map { it.toSuggestion() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DrinkSuggestionState())

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The shell's slot for `core:ui` `LocalDrinkSuggestion`: tapping opens the drink. */
fun drinkSuggestionSlot(onOpenRecipe: (Long) -> Unit): DrinkSuggestionSlot = DrinkSuggestionSlot { recipeId, modifier, compact ->
    DrinkSuggestionRoute(recipeId = recipeId, onOpenRecipe = onOpenRecipe, modifier = modifier, compact = compact)
}

@Composable
fun DrinkSuggestionRoute(recipeId: Long, onOpenRecipe: (Long) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val viewModel = hiltViewModel<DrinkSuggestionViewModel, DrinkSuggestionViewModel.Factory>(
        key = "drink-suggestion-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DrinkSuggestionRow(state = state, onOpenDrink = onOpenRecipe, modifier = modifier, compact = compact)
}

/**
 * "Passar med: Rioja Crianza, Kraftigt rött" with a glass; ONE TalkBack item; nothing without
 * pairings. TalkBack's "Öppna …" names the drink a tap really opens (fix-ui). [compact]: one line
 * with an ellipsis and no arrow, for the tablet's week grid.
 */
@Composable
fun DrinkSuggestionRow(state: DrinkSuggestionState, onOpenDrink: (Long) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (state.names.isEmpty()) return
    val text = stringResource(R.string.drinks_suggestion, state.names.joinToString(", "))
    val description = stringResource(R.string.drinks_suggestion_description, state.names.joinToString(", "))
    val drinkId = state.drinkId
    val clickable = if (drinkId != null) {
        val openLabel = stringResource(R.string.drinks_open_drink, state.drinkName ?: state.names.first())
        Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { onOpenDrink(drinkId) }
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(clickable)
            .heightIn(min = 48.dp)
            .padding(vertical = TandemTheme.spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) TandemTheme.spacing.xs else TandemTheme.spacing.sm),
    ) {
        Icon(
            TandemIcons.WineBar,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (compact) 16.dp else 20.dp),
        )
        Text(
            text = if (compact) state.names.joinToString(", ") else text,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            maxLines = if (compact) 1 else Int.MAX_VALUE,
            overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = Modifier.weight(1f),
        )
        if (drinkId != null && !compact) {
            Icon(TandemIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
