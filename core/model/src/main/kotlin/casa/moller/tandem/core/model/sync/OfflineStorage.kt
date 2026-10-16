package casa.moller.tandem.core.model.sync

/**
 * What the active account keeps on the device (plan 6.2 "Synk: ladda ner alla recept,
 * lagringsstorlek"), for Settings → Sync and storage.
 *
 * @property recipeCount recipes in the stored index.
 * @property downloadedCount recipes whose detail is stored for offline use.
 * @property databaseBytes size of the app's database files (all accounts, demo excluded).
 * @property imageCacheBytes size of the recipe image cache on disk.
 * @property estimatedDownloadBytes a rough estimate of what downloading the remaining recipes
 *   (details and images) adds; 0 when everything is downloaded.
 */
data class OfflineStorage(
    val recipeCount: Int = 0,
    val downloadedCount: Int = 0,
    val databaseBytes: Long = 0,
    val imageCacheBytes: Long = 0,
    val estimatedDownloadBytes: Long = 0,
) {
    /** Recipes not downloaded yet. */
    val remainingCount: Int
        get() = (recipeCount - downloadedCount).coerceAtLeast(0)

    /** 0.0..1.0 of the recipes downloaded; 1.0 without recipes. */
    val downloadedFraction: Float
        get() = if (recipeCount <= 0) 1f else (downloadedCount.toFloat() / recipeCount).coerceIn(0f, 1f)

    /** Database and image cache together. */
    val totalBytes: Long
        get() = databaseBytes + imageCacheBytes

    companion object {
        /** Nothing stored (no account, or not measured yet). */
        val Empty: OfflineStorage = OfflineStorage()
    }
}
