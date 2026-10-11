package casa.moller.tandem.core.sync.image

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException

/** [ImagePrefetcher] with the app's [ImageLoader]: fills the disk cache, skips the memory cache. */
internal class CoilImagePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: Provider<ImageLoader>,
) : ImagePrefetcher {

    override suspend fun prefetch(urls: List<String>): Int {
        var loaded = 0
        for (url in urls.distinct()) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .size(PREFETCH_DECODE_SIZE)
                .build()
            val result = try {
                imageLoader.get().execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (result is SuccessResult) loaded++
        }
        return loaded
    }

    private companion object {
        /** Only the downloaded file matters; decoding small keeps the prefetch cheap. */
        const val PREFETCH_DECODE_SIZE = 64
    }
}
