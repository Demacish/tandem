package casa.moller.tandem.core.data.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The demo content is packaged as Android assets and readable through the AssetManager. */
@RunWith(AndroidJUnit4::class)
class AndroidDemoAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readsTheDemoContentFromTheAssets() {
        val content = DemoContent(AndroidDemoAssets(context.assets))
        val recipes = content.recipes(Locale.ENGLISH, LocalDate(2026, 10, 11))
        assertEquals(content.recipeIds, recipes.map { it.id })
        for (uri in content.imageUris) {
            val path = "${DemoContent.ASSET_ROOT}/${DemoContent.imageAssetPath(uri)}"
            assertTrue(path, context.assets.open(path).use { it.read() } >= 0)
        }
    }
}
