package casa.moller.tandem.core.data.demo

import java.util.Locale

/** The languages the demo content is written in. Everything else falls back to [ENGLISH]. */
enum class DemoLanguage(val tag: String) {
    SWEDISH("sv"),
    ENGLISH("en"),
    ;

    companion object {
        /** Swedish for any Swedish locale (`sv`, `sv-SE`, `sv-FI`), English otherwise. */
        fun of(locale: Locale): DemoLanguage = if (locale.language == SWEDISH.tag) SWEDISH else ENGLISH
    }
}
