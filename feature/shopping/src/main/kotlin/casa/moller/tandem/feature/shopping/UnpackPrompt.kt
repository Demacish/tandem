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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

// Fas S (hemma-ui lane): "Packa upp" from Handla after shopping (#46, plan §8.8): when something was
// checked off today and is not unpacked yet, the list offers to put it in the pantry (feature:pantry's
// UnpackKey, opened by the shell).

/** How many of today's checked items wait to be unpacked; 0 for a guest (who cannot unpack). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UnpackPromptViewModel @Inject internal constructor(
    pantry: PantryRepository,
    permissions: SpacePermissionsRepository,
    calendar: CalendarDays,
) : ViewModel() {
    val count: StateFlow<Int> = combine(
        calendar.observeToday().flatMapLatest { day -> pantry.observeUnpackable(day) },
        permissions.permissions,
    ) { items, perms -> if (perms.readOnly) 0 else items.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The card "Packa upp det du handlat? 5 varor bockades av i dag" with "Packa upp"; nothing when none wait. */
@Composable
fun UnpackPromptRoute(onUnpack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: UnpackPromptViewModel = hiltViewModel()
    val count by viewModel.count.collectAsStateWithLifecycle()
    UnpackPrompt(count = count, onUnpack = onUnpack, modifier = modifier)
}

/** Stateless [UnpackPromptRoute] (screenshot tests). */
@Composable
internal fun UnpackPrompt(count: Int, onUnpack: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    CalloutCard(
        title = stringResource(R.string.shopping_unpack_title),
        body = pluralStringResource(R.plurals.shopping_unpack_body, count, count),
        icon = TandemIcons.ShoppingCartFilled,
        // Neutral, like the sync notice: the text button's colour is readable on it (ATF).
        tone = TandemTone.Neutral,
        modifier = modifier,
        actions = { TandemTextButton(text = stringResource(R.string.shopping_unpack_action), onClick = onUnpack) },
    )
}
