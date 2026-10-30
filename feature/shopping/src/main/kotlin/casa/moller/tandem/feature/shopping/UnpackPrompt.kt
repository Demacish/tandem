package casa.moller.tandem.feature.shopping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

// Fas S (hemma-ui lane): "Packa upp" from Handla after shopping (#46, plan §8.8): when something was
// checked off today and is not unpacked yet, the list offers to put it in the pantry (feature:pantry's
// UnpackKey, opened by the shell). Fas S review (fix-ui): the card sits BELOW the list (after
// "Avbockat"), so it never pushes the aisles down mid-shopping, and "Inte nu" hides it for the day.

/**
 * Fas S review (fix-ui): "Inte nu" on the Packa upp card hides it for the rest of the day, on this
 * device, without marking anything as unpacked (Hemma's card and Mer → Hemma → Packa upp still
 * offer it). Kept for the app's lifetime: after a restart the card may come back the same day.
 */
@Singleton
class UnpackPromptDismissals @Inject constructor() {
    private val dismissedOn = MutableStateFlow<LocalDate?>(null)

    fun dismiss(day: LocalDate) {
        dismissedOn.value = day
    }

    fun observeDismissed(day: LocalDate): Flow<Boolean> = dismissedOn.map { it == day }
}

/** How many of today's checked items wait to be unpacked; 0 for a guest (who cannot unpack) or after "Inte nu" today. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UnpackPromptViewModel @Inject internal constructor(
    pantry: PantryRepository,
    permissions: SpacePermissionsRepository,
    private val calendar: CalendarDays,
    private val dismissals: UnpackPromptDismissals,
) : ViewModel() {
    val count: StateFlow<Int> = combine(
        calendar.observeToday().flatMapLatest { day ->
            combine(pantry.observeUnpackable(day), dismissals.observeDismissed(day)) { items, dismissed ->
                if (dismissed) 0 else items.size
            }
        },
        permissions.permissions,
    ) { count, perms -> if (perms.readOnly) 0 else count }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    /** "Inte nu": hidden until tomorrow; the items stay unpackable. */
    fun dismiss() {
        dismissals.dismiss(calendar.today())
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The card "Packa upp det du handlat? 5 varor bockades av i dag" with "Packa upp" and "Inte nu"; nothing when none wait. */
@Composable
fun UnpackPromptRoute(onUnpack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: UnpackPromptViewModel = hiltViewModel()
    val count by viewModel.count.collectAsStateWithLifecycle()
    UnpackPrompt(count = count, onUnpack = onUnpack, onDismiss = viewModel::dismiss, modifier = modifier)
}

/** Stateless [UnpackPromptRoute] (screenshot tests). */
@Composable
internal fun UnpackPrompt(count: Int, onUnpack: () -> Unit, modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    if (count <= 0) return
    CalloutCard(
        title = stringResource(R.string.shopping_unpack_title),
        body = pluralStringResource(R.plurals.shopping_unpack_body, count, count),
        icon = TandemIcons.ShoppingCartFilled,
        // Neutral, like the sync notice: the text button's colour is readable on it (ATF).
        tone = TandemTone.Neutral,
        modifier = modifier,
        actions = {
            TandemTextButton(text = stringResource(R.string.shopping_unpack_action), onClick = onUnpack)
            TandemTextButton(text = stringResource(R.string.shopping_unpack_not_now), onClick = onDismiss)
        },
    )
}
