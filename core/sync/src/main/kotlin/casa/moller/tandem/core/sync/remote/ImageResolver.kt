package casa.moller.tandem.core.sync.remote

import casa.moller.tandem.core.network.image.MediaUrls

/** Turns the `image` value of a Tandoor response into the URL that is stored and loaded. */
internal fun interface ImageResolver {
    fun resolve(image: String?): String?

    companion object {
        /** A server's images: relative paths and `http` URLs are placed on the account's base URL (FACTS "Fas 1 e"). */
        fun forBaseUrl(baseUrl: String): ImageResolver = ImageResolver { image -> MediaUrls.resolve(baseUrl, image) }

        /** Demo content: the bundled `file:///android_asset/demo/...` URIs are used as they are (plan 6.2). */
        val AsIs: ImageResolver = ImageResolver { image -> image?.trim()?.takeIf { it.isNotEmpty() } }
    }
}
