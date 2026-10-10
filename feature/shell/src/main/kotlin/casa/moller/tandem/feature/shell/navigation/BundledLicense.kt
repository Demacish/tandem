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

    /** AndroidX, Compose, Kotlin, Hilt, OkHttp, Retrofit, Tink: all Apache License 2.0. */
    LIBRARIES(R.string.shell_license_libraries, R.string.shell_license_apache, "Apache-2.0-MaterialSymbols.txt"),
    ;

    val assetPath: String
        get() = "licenses/$asset"
}
