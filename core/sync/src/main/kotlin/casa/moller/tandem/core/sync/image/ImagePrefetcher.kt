package casa.moller.tandem.core.sync.image

/** Downloads recipe images into the image disk cache ahead of time (plan 3.5, feature #5). */
internal interface ImagePrefetcher {
    /**
     * Loads [urls] into the disk cache, one after the other. Never throws.
     *
     * @return the URLs that are settled: loaded, or answered with a client error a retry cannot
     *   fix (404 and the like). Temporary failures (offline, timeout, 5xx, 429, the image client's
     *   pause after repeated 4xx) are left out, so a later sync run tries them again.
     */
    suspend fun prefetch(urls: List<String>): Set<String>
}
