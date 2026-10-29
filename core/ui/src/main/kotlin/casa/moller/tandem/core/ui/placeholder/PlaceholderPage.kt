package casa.moller.tandem.core.ui.placeholder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.R

/**
 * Fas S (grund lane): the empty page a new destination shows until its lane builds the screen
 * (docs/BYGGE.md "Fas S – kontrakt"). Not reachable from the UI before the lanes add the entry
 * points; removed by the last lane (rester) when no destination uses it any more.
 */
@Composable
fun PlaceholderPage(title: String, onNavigateBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { TandemTopAppBar(title = title, onNavigateBack = onNavigateBack) },
    ) { padding ->
        EmptyState(
            icon = TandemIcons.Restaurant,
            title = title,
            body = stringResource(R.string.ui_placeholder_body),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
