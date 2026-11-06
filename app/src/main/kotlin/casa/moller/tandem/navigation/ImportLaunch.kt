package casa.moller.tandem.navigation

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import casa.moller.tandem.feature.shell.navigation.ShareImportRequest

/**
 * Fas 3 (import lane): reads an `ACTION_SEND` another app sent to Tandem ("Dela till Tandem":
 * a link or text, a photo, a PDF; the manifest's intent filters) into a [ShareImportRequest], and
 * `ACTION_SEND_MULTIPLE` with images: the photographed pages of one recipe (plan §8.4).
 * `MainActivity` is exported, so any app may send this: texts are capped, a stream must be a
 * `content://` uri (a `file://` uri is never read), and extras that cannot be read end as "just
 * open the app". The uri's read grant lasts the activity, so the import screen reads it at once.
 */
internal object ImportLaunch {
    /** A shared text longer than this is cut (a recipe as text fits many times over). */
    const val MAX_TEXT_LENGTH: Int = 20_000

    /** More pages than this in one share are left out (a recipe, not a whole book). */
    const val MAX_PAGES: Int = 20

    /** The request in [intent], or null when it is not a share. */
    fun requestFrom(intent: Intent?): ShareImportRequest? {
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) return pagesFrom(intent)
        if (intent?.action != Intent.ACTION_SEND) return null
        val extras = runCatching { intent.extras }.getOrNull() ?: return null
        val stream = runCatching { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
        if (stream != null) {
            if (stream.scheme != "content") return null
            return ShareImportRequest(fileUri = stream.toString(), mimeType = intent.type?.takeIf { it != "*/*" })
        }
        val text = runCatching { extras.getCharSequence(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return ShareImportRequest(text = text.take(MAX_TEXT_LENGTH))
    }

    /** Only images, only `content://` uris: anything else in the share makes it no share at all. */
    private fun pagesFrom(intent: Intent): ShareImportRequest? {
        if (intent.type?.startsWith("image/") != true) return null
        val streams = runCatching { IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }
            .getOrNull()
            ?.filterNotNull()
            .orEmpty()
        if (streams.isEmpty() || streams.any { it.scheme != "content" }) return null
        return ShareImportRequest(pageUris = streams.take(MAX_PAGES).map { it.toString() })
    }
}
