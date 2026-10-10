package casa.moller.tandem.core.ui.language

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The languages the app is translated to (plan 6.7). [tag] null = follow the system. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    SWEDISH("sv"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag != null && it.tag == tag?.substringBefore('-')?.lowercase() } ?: SYSTEM
    }
}

/** Reads and changes the per-app language. */
interface LanguageController {
    val current: AppLanguage

    fun select(language: AppLanguage)
}

/**
 * Per-app language through the platform [LocaleManager] (API 33+, minSdk is 34). The choice also
 * shows up in Android's Settings -> Apps -> Tandem -> Language; the system recreates the
 * activity with the new locale, no restart needed. Failures (e.g. in tests) leave the language
 * unchanged.
 */
internal class LocaleManagerLanguageController(private val context: Context) : LanguageController {
    private val manager: LocaleManager?
        get() = context.getSystemService(LocaleManager::class.java)

    override val current: AppLanguage
        get() = runCatching {
            val locales = manager?.applicationLocales
            if (locales == null || locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromTag(locales[0].toLanguageTag())
        }.getOrDefault(AppLanguage.SYSTEM)

    override fun select(language: AppLanguage) {
        runCatching {
            manager?.applicationLocales = language.tag?.let { LocaleList.forLanguageTags(it) }
                ?: LocaleList.getEmptyLocaleList()
        }
    }
}

@Composable
fun rememberLanguageController(): LanguageController {
    val context = LocalContext.current
    return remember(context) { LocaleManagerLanguageController(context) }
}
