package casa.moller.tandem.core.designsystem.component

import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.R

/**
 * Indeterminate M3 Expressive loading indicator (morphing shapes). The only place in the app
 * that opts in to the still experimental LoadingIndicator API, so a later API change touches
 * just this file.
 *
 * @param contained draws the indicator on a primaryContainer disc, for use over images or busy
 *   backgrounds.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TandemLoadingIndicator(modifier: Modifier = Modifier, contained: Boolean = false) {
    val description = stringResource(R.string.tandem_loading)
    val semanticsModifier = modifier.semantics { contentDescription = description }
    if (contained) {
        ContainedLoadingIndicator(modifier = semanticsModifier)
    } else {
        LoadingIndicator(modifier = semanticsModifier)
    }
}
