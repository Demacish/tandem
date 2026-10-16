package casa.moller.tandem.core.sync.image

import coil3.ImageLoader
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's image caches. When an account's data is removed (logout, "Clear app data", another
 * user or server on the same account), its recipe photos are removed too (plan 5.5 "Logga ut och
 * radera lokal data"): the disk cache lives in `noBackupFilesDir`, which Android never clears on
 * its own. The cache is shared by every account (decision B14: one active account), so it is
 * emptied as a whole.
 */
internal interface ImageCache {
    /** Empties the memory and disk cache and forgets which images were prefetched. Never throws. */
    suspend fun clear()

    /** Bytes the disk cache uses now (Settings → Sync and storage); 0 when it cannot be read. Never throws. */
    suspend fun sizeBytes(): Long
}

/** The image URLs the sync already prefetched in this process (so a run does not ask again). */
@Singleton
internal class PrefetchedImages @Inject constructor() {
    private val urls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    operator fun contains(url: String): Boolean = url in urls

    fun addAll(settled: Collection<String>) {
        urls += settled
    }

    fun clear() {
        urls.clear()
    }
}

/** [ImageCache] of the app's Coil [ImageLoader] (built only when something is cleared). */
internal class CoilImageCache @Inject constructor(
    private val imageLoader: Provider<ImageLoader>,
    private val prefetched: PrefetchedImages,
) : ImageCache {
    override suspend fun clear() {
        prefetched.clear()
        withContext(Dispatchers.IO) {
            try {
                val loader = imageLoader.get()
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage trouble: the next removal tries again.
            }
        }
    }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        try {
            imageLoader.get().diskCache?.size ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
    }
}
