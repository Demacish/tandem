package casa.moller.tandem.core.sync.image

import android.content.Context
import casa.moller.tandem.core.sync.platform.NetworkStatus
import coil3.ImageLoader
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ImagePrefetcher] with the app's [ImageLoader]: fills the disk cache, skips the memory cache.
 * The batch stops early when the device goes offline or after [MAX_TEMPORARY_FAILURES]
 * temporary failures in a row, so a busy or paused server does not burn the whole batch.
 */
internal class CoilImagePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: Provider<ImageLoader>,
    private val network: NetworkStatus,
) : ImagePrefetcher {

    override suspend fun prefetch(urls: List<String>): Set<String> {
        val settled = LinkedHashSet<String>()
        var failuresInARow = 0
        for (url in urls.distinct()) {
            if (!network.online.value || failuresInARow >= MAX_TEMPORARY_FAILURES) break
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
            if (result is SuccessResult || (result is ErrorResult && isPermanent(result.throwable))) {
                settled += url
                failuresInARow = 0
            } else {
                failuresInARow++
            }
        }
        return settled
    }

    /** A client error a retry cannot fix (deleted, forbidden...); 408 and 429 are temporary. */
    private fun isPermanent(error: Throwable): Boolean {
        val code = (error as? HttpException)?.response?.code ?: return false
        return code in CLIENT_ERRORS && code != TIMEOUT && code != TOO_MANY_REQUESTS
    }

    private companion object {
        /** Only the downloaded file matters; decoding small keeps the prefetch cheap. */
        const val PREFETCH_DECODE_SIZE = 64
        const val MAX_TEMPORARY_FAILURES = 3
        val CLIENT_ERRORS = 400..499
        const val TIMEOUT = 408
        const val TOO_MANY_REQUESTS = 429
    }
}
