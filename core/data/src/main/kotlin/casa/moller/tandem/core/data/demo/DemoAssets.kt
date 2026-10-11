package casa.moller.tandem.core.data.demo

import android.content.res.AssetManager
import java.io.IOException

/**
 * Reads one file of the built-in demo content (`core/data/src/main/assets/demo/`, plan 6.2
 * "Demoläge"). [path] is relative to that folder, e.g. `index.json` or `sv/recipe-1.json`.
 *
 * Kept as a one-method interface so that [DemoContent] stays plain Kotlin: the app reads the
 * APK's assets ([AndroidDemoAssets]); JVM tests and previews can read the folder from disk.
 */
fun interface DemoAssets {
    /** The UTF-8 text of [path]; throws [IOException] when the file does not exist. */
    @Throws(IOException::class)
    fun read(path: String): String
}

/** [DemoAssets] backed by the APK's assets (`assets/demo/...`). */
class AndroidDemoAssets(private val assets: AssetManager) : DemoAssets {
    override fun read(path: String): String =
        assets.open("${DemoContent.ASSET_ROOT}/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
