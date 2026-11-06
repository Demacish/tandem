package casa.moller.tandem.feature.recipes.recipeimport

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.recipes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Plan §8.4 "Granskningsvyn visar därför originalbilden bredvid den tolkade texten": an AI can
// invent or drop a step, and the original on the screen is what shows it. Below the width the
// import and the editor put it beside the form, it folds out of the way.

/** From this width the original stands beside the result or the form (plan 6.5). */
internal val ORIGINAL_BESIDE_MIN_WIDTH = 840.dp

/** The fold-out "Visa originalet". */
const val IMPORT_ORIGINAL_TOGGLE_TAG = "recipe-import:original"

/** What shows the kept file: the real pages, and a stand-in in the tests (decoding runs on another thread). */
typealias OriginalView = @Composable (path: String, modifier: Modifier) -> Unit

/** The original's pages, one below the other, the whole width. */
@Composable
internal fun OriginalPages(path: String, modifier: Modifier = Modifier) {
    val pages by produceState<List<ImageBitmap>?>(null, path) {
        value = withContext(Dispatchers.IO) { ImportFiles.previews(path).map { it.asImageBitmap() } }
    }
    val spacing = TandemTheme.spacing
    val description = stringResource(R.string.recipe_import_original_description)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        pages?.forEach { page ->
            Image(
                bitmap = page,
                contentDescription = description,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
            )
        }
        if (pages?.isEmpty() == true) {
            Text(text = stringResource(R.string.recipe_import_original_gone), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The original with its heading, for the pane beside the result or the form. */
@Composable
internal fun OriginalPane(path: String, view: OriginalView, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.recipe_import_original),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        view(path, Modifier.fillMaxWidth())
    }
}

/** "Visa originalet" on a narrow window. */
@Composable
internal fun OriginalFold(path: String, view: OriginalView, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
        TandemTextButton(
            text = stringResource(if (open) R.string.recipe_import_original_hide else R.string.recipe_import_original_show),
            onClick = { open = !open },
            modifier = Modifier.testTag(IMPORT_ORIGINAL_TOGGLE_TAG),
        )
        if (open) view(path, Modifier.fillMaxWidth())
    }
}
