package casa.moller.tandem.core.common

/**
 * Facts about the running build. `:app` provides a singleton through Hilt
 * (`casa.moller.tandem.di.AppBuildInfoModule`), so library modules never need `BuildConfig`
 * of the app.
 *
 * @property versionName for example `0.0.1` (release) or `0.0.1-dev` (debug).
 * @property distribution the product flavor: `sideload`, `play` or `fdroid`.
 * @property sdkInt `Build.VERSION.SDK_INT` of the device.
 */
data class AppBuildInfo(val versionName: String, val versionCode: Long, val distribution: String, val isDebug: Boolean, val sdkInt: Int) {
    /** User-Agent for every request to Tandoor: `Tandem/<versionName> (Android <sdkInt>)`. */
    val userAgent: String
        get() = "Tandem/$versionName (Android $sdkInt)"
}
