package casa.moller.tandem.feature.shell.navigation

import androidx.annotation.StringRes
import casa.moller.tandem.feature.shell.R

/**
 * Third-party material shipped in the APK whose licence text is bundled in
 * core:designsystem's `assets/licenses/` (see NOTICE.txt there).
 *
 * @property asset file name under `assets/licenses/`.
 */
enum class BundledLicense(@StringRes val title: Int, @StringRes val licenseName: Int, val asset: String) {
    BRICOLAGE_GROTESQUE(R.string.shell_license_bricolage, R.string.shell_license_ofl, "OFL-BricolageGrotesque.txt"),
    DM_SANS(R.string.shell_license_dm_sans, R.string.shell_license_ofl, "OFL-DMSans.txt"),
    MATERIAL_SYMBOLS(R.string.shell_license_material_symbols, R.string.shell_license_apache, "Apache-2.0-MaterialSymbols.txt"),

    /** AndroidX (with CameraX), Compose, Kotlin, Hilt, OkHttp, Retrofit, Tink, Jackson and the rest: all Apache License 2.0. */
    LIBRARIES(R.string.shell_license_libraries, R.string.shell_license_apache, "Apache-2.0-MaterialSymbols.txt"),

    /** Fas S: the Anthropic Java SDK (the experimental fridge photo with Claude), MIT. */
    ANTHROPIC_JAVA(R.string.shell_license_anthropic_java, R.string.shell_license_mit, "MIT-AnthropicJava.txt"),

    /** Fas S: Google ML Kit with bundled models (barcodes and receipts), ML Kit Terms of Service. */
    ML_KIT(R.string.shell_license_ml_kit, R.string.shell_license_ml_kit_terms, "MLKit-NOTICE.txt"),
    ;

    val assetPath: String
        get() = "licenses/$asset"
}
