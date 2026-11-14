package casa.moller.tandem.core.domain.voice

import java.util.Locale

/**
 * Fas 5, device test 2026-11-14 (B1): the full language tag a speech recognizer listens in.
 *
 * A recognizer wants a language WITH its region ("en-US", not "en"): on-device recognizers answer a
 * bare "en" with "language not supported" (error 12) although they have English. The app language
 * often has no region (the per-app language "English" is "en"), so every tag gets one:
 *
 * 1. the region the app language already has ("en-GB"),
 * 2. the phone's own region when the language is spoken there ("en" on a British phone → "en-GB"),
 * 3. the language's usual region ([DEFAULT_REGION]: "en" → "en-US", "de" → "de-DE", "sv" → "sv-SE").
 *
 * When the recognizer reported the languages it supports, those decide: the first candidate it
 * supports, else another region of the same language it supports ("en-GB" on a recognizer with
 * only "en-US" → "en-US"). Pure, so the mapping is unit tested.
 */
object VoiceLocales {

    /** English, for the English commands (B3) when the phone cannot recognise the app's language. */
    const val ENGLISH: String = "en"

    /**
     * The tag to listen in for [appTag] (the app's language, "sv", "en", "en-GB").
     *
     * @param deviceRegion the phone's own region ("SE", "GB"), or null/empty when unknown.
     * @param supported the recognizer's languages (BCP 47, "en-US" or "en_US"); empty = not known.
     */
    fun resolve(appTag: String, deviceRegion: String? = null, supported: Collection<String> = emptyList()): String {
        val locale = Locale.forLanguageTag(appTag.replace('_', '-'))
        val language = locale.language.lowercase(Locale.ROOT).ifEmpty { ENGLISH }
        val candidates = candidates(language, locale.country.uppercase(Locale.ROOT), deviceRegion?.uppercase(Locale.ROOT).orEmpty())
        val known = supported.mapNotNull(::normalize).distinct()
        if (known.isNotEmpty()) {
            candidates.firstOrNull { it in known }?.let { return it }
            // Another region of the language the recognizer has; candidates order the regions it has.
            known.firstOrNull { languageOf(it) == language }?.let { return it }
        }
        return candidates.firstOrNull() ?: language
    }

    /** Whether [tag] is English ("en", "en-GB"). */
    fun isEnglish(tag: String): Boolean = languageOf(tag) == ENGLISH

    /** The language part of [tag], lower case ("sv-SE" → "sv"). */
    fun languageOf(tag: String): String = Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)

    /** In order of preference; when nothing is known about the recognizer, only regions it likely has. */
    private fun candidates(language: String, ownRegion: String, deviceRegion: String): List<String> = buildList {
        if (ownRegion.isNotEmpty()) add(tag(language, ownRegion))
        val usual = COMMON_REGIONS[language].orEmpty() + listOfNotNull(DEFAULT_REGION[language])
        if (deviceRegion.isNotEmpty() && deviceRegion in usual) add(tag(language, deviceRegion))
        DEFAULT_REGION[language]?.let { add(tag(language, it)) }
        // A language without a usual region: the phone's region is the best guess there is.
        if (isEmpty() && deviceRegion.isNotEmpty()) add(tag(language, deviceRegion))
    }.distinct()

    private fun tag(language: String, region: String): String = "$language-$region"

    /** "en_us", "EN-us" → "en-US"; tags without a language are dropped. */
    private fun normalize(tag: String): String? {
        val locale = Locale.forLanguageTag(tag.trim().replace('_', '-'))
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isEmpty()) return null
        val region = locale.country.uppercase(Locale.ROOT)
        return if (region.isEmpty()) language else tag(language, region)
    }

    /**
     * The usual region of a language: the one recognizers ship first. Swedish has one region
 * ("sv" → "sv-SE" also on a Finnish phone, as before the device test).
     * Data, not logic: a language missing here gets the phone's region.
     */
    private val DEFAULT_REGION: Map<String, String> = mapOf(
        "sv" to "SE", "en" to "US", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "BR", "nl" to "NL",
        "da" to "DK", "nb" to "NO", "no" to "NO", "fi" to "FI", "pl" to "PL", "ru" to "RU", "tr" to "TR", "uk" to "UA",
        "cs" to "CZ", "el" to "GR", "hu" to "HU", "ro" to "RO", "ja" to "JP", "ko" to "KR", "zh" to "CN", "hi" to "IN",
        "id" to "ID", "th" to "TH", "vi" to "VN", "ar" to "SA", "he" to "IL",
    )

    /** Other regions recognizers commonly have for a language (the phone's region is used only if listed). */
    private val COMMON_REGIONS: Map<String, Set<String>> = mapOf(
        "en" to setOf("US", "GB", "AU", "CA", "IN", "IE", "NZ", "ZA", "SG", "PH"),
        "de" to setOf("DE", "AT", "CH"),
        "fr" to setOf("FR", "CA", "BE", "CH"),
        "es" to setOf("ES", "US", "MX", "AR", "CL", "CO", "PE"),
        "pt" to setOf("BR", "PT"),
        "nl" to setOf("NL", "BE"),
        "it" to setOf("IT", "CH"),
        "zh" to setOf("CN", "TW", "HK"),
    )
}
