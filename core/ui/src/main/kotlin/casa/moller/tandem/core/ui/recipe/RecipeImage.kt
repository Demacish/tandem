package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * A recipe photo, cropped to fill [modifier]'s size, with a calm placeholder (sand surface and a
 * book icon) while it loads, when it fails and for recipes without an image.
 *
 * Uses Coil's singleton ImageLoader, which the app configures with Tandem's HTTP client (auth
 * header, rate limits, disk cache for offline recipes), so this composable never knows the
 * server. Previews and screenshot tests pass `imageUrl = null` and never touch the network.
 *
 * @param contentDescription null for decorative images (the recipe name is shown next to it).
 * @param dimmed shows the image faded, e.g. for recipes that cannot be opened offline.
 */
@Composable
fun RecipeImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    dimmed: Boolean = false,
    placeholderIconSize: Dp = TandemTheme.sizes.iconLarge,
) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .testTag(RecipeImageTags.of(loaded))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .alpha(if (dimmed) DIMMED_ALPHA else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = TandemIcons.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(placeholderIconSize),
        )
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

/** Opacity of recipes that cannot be opened right now (offline without a stored detail). */
const val DIMMED_ALPHA: Float = 0.45f

/**
 * Test tags of a recipe photo: [LOADED] once the image is on screen, [PLACEHOLDER] while it
 * loads, after a failure and for recipes without one. The app exposes test tags as resource ids
 * (`testTagsAsResourceId`), so the release smoke test (:smoke) can tell from outside the app
 * whether a photo (a demo asset or a cached server image, also offline) really loaded.
 */
object RecipeImageTags {
    const val PLACEHOLDER: String = "recipe_image"
    const val LOADED: String = "recipe_image_loaded"

    fun of(loaded: Boolean): String = if (loaded) LOADED else PLACEHOLDER
}
