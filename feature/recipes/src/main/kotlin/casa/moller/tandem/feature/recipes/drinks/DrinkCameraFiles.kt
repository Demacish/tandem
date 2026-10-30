package casa.moller.tandem.feature.recipes.drinks

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Fas S review (fix-ui): the full-size originals the camera app writes for "Ny dryck"
 * (`cache/drink-camera/`, often with EXIF location). The repository keeps only its scaled 1600 px
 * copy, so an original is deleted as soon as it is not needed: a cancelled capture, a photo that
 * is replaced or removed, a saved drink, and everything left over when "Ny dryck" opens. Only
 * files inside that directory are ever deleted (canonical paths), never a photo-picker image.
 */
internal object DrinkCameraFiles {
    const val DIRECTORY: String = "drink-camera"

    /** The FileProvider of feature:recipes' manifest (`${applicationId}.drinkphotos`). */
    const val AUTHORITY_SUFFIX: String = ".drinkphotos"

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /** A new, empty file for one capture. */
    fun newFile(context: Context): File {
        val directory = directory(context).apply { mkdirs() }
        return File(directory, "drink-${System.currentTimeMillis()}.jpg")
    }

    /**
     * The camera file behind [uri] (our FileProvider's `content://…drinkphotos/drink_camera/<name>`),
     * or null for any other uri (the photo picker's) or a path outside the directory.
     */
    fun fileOf(context: Context, uri: String?): File? {
        val parsed = uri?.let(Uri::parse) ?: return null
        if (parsed.authority != context.packageName + AUTHORITY_SUFFIX) return null
        val name = parsed.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        return File(directory(context), name).takeIf { inside(context, it) }
    }

    /** Deletes the camera file behind [uri]; anything else is left alone. */
    fun delete(context: Context, uri: String?) {
        fileOf(context, uri)?.delete()
    }

    /** Deletes every camera file except those behind [keep] (the form's photo, a capture under way). */
    fun sweep(context: Context, keep: Collection<String?> = emptyList()) {
        val kept = keep.mapNotNull { fileOf(context, it)?.canonicalPath }.toSet()
        directory(context).listFiles()?.forEach { file ->
            if (inside(context, file) && file.canonicalPath !in kept) file.delete()
        }
    }

    private fun inside(context: Context, file: File): Boolean = try {
        file.canonicalFile.parentFile?.canonicalPath == directory(context).canonicalPath
    } catch (ignored: IOException) {
        false
    }
}
