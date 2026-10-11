package casa.moller.tandem.core.ui.recipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import coil3.compose.AsyncImage
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
    Box(
        modifier = modifier
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
            )
        }
    }
}

/** Opacity of recipes that cannot be opened right now (offline without a stored detail). */
const val DIMMED_ALPHA: Float = 0.45f
