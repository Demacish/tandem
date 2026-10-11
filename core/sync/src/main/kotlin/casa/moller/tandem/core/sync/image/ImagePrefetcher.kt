package casa.moller.tandem.core.sync.image

/** Downloads recipe images into the image disk cache ahead of time (plan 3.5, feature #5). */
internal interface ImagePrefetcher {
    /** Loads [urls] into the disk cache, one after the other; returns how many succeeded. Never throws. */
    suspend fun prefetch(urls: List<String>): Int
}
