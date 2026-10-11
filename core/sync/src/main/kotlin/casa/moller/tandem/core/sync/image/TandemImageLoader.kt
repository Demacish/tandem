package casa.moller.tandem.core.sync.image

import android.content.Context
import casa.moller.tandem.core.sync.SyncSchedule
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import java.io.File

/**
 * The app's one Coil [ImageLoader] (plan 5.1 "Bilder"), installed as Coil's singleton at app
 * start (`SyncStarter`), so every `AsyncImage` uses it:
 *
 * - network images only through [AccountImageCallFactory] (Coil's own default OkHttp client is
 *   switched off with the service loader, so no image request bypasses the limits);
 * - bundled demo images (`file:///android_asset/demo/...`) through Coil's built-in asset fetcher;
 * - a disk cache of [SyncSchedule.IMAGE_DISK_CACHE_BYTES] in `noBackupFilesDir`, which Android
 *   does not clear under storage pressure like the cache folder, so images of offline recipes stay
 *   (Tandoor's file names change on every upload, so cached files never go stale).
 */
internal object TandemImageLoader {
    private const val DISK_CACHE_FOLDER = "image_cache"
    private const val MEMORY_CACHE_PERCENT = 0.2

    fun build(context: Context, callFactory: AccountImageCallFactory): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = callFactory)) }
        .serviceLoaderEnabled(false)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.noBackupFilesDir, DISK_CACHE_FOLDER))
                .maxSizeBytes(SyncSchedule.IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .build()
}
