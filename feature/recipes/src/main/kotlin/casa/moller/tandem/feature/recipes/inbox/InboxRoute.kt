package casa.moller.tandem.feature.recipes.inbox

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import casa.moller.tandem.feature.recipes.navigation.RecipeInboxKey

/**
 * Fas 5 (rss lane, plan §7 #35): "Inkorg för recept" for [key] (docs/BYGGE.md "Inkorgen för recept").
 *
 * @param onOpenImport tap 2: the import review (#18) with the post's address (`autoImport`); the
 *   shell opens it in the inbox's place on the Recipes tab.
 * @param onOpenSettings "Ställ in" and the top bar's settings: Mer → Inkorg för recept.
 */
@Composable
fun InboxRoute(
    key: RecipeInboxKey,
    onOpenImport: (RecipeImportKey) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<InboxViewModel, InboxViewModel.Factory>(
        key = "recipe-inbox-${key.hashCode()}",
        creationCallback = { factory -> factory.create(key) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val currentOpenImport by rememberUpdatedState(onOpenImport)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is InboxEffect.OpenImport -> currentOpenImport(effect.key)

                is InboxEffect.OpenUrl -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, effect.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: ActivityNotFoundException) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.inbox_no_browser))
                }

                is InboxEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }
    InboxScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}
