import casa.moller.tandem.buildlogic.configureAndroidCommon
import casa.moller.tandem.buildlogic.configureKtlint
import casa.moller.tandem.buildlogic.defaultNamespace
import casa.moller.tandem.buildlogic.intVersion
import casa.moller.tandem.buildlogic.libs
import casa.moller.tandem.buildlogic.registerCiTasks
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `tandem.android.library`: com.android.library with the shared settings.
 * - namespace defaults to `casa.moller.tandem.<module path>` (`:core:network` ->
 *   `casa.moller.tandem.core.network`); a module may override it.
 * - unit tests and lint run against targetSdk from the catalog.
 * - registers `ciTest` (testDebugUnitTest) and `ciLint` (ktlint + lintDebug).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            val moduleNamespace = defaultNamespace()
            val targetSdk = libs.intVersion("targetSdk")
            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                namespace = moduleNamespace
                testOptions.targetSdk = targetSdk
                lint.targetSdk = targetSdk
            }
            configureKtlint()
            // detekt + Compose rules (ci lane, Fas 1b); `ciLint` depends on `detekt`.
            pluginManager.apply("tandem.detekt")
            registerCiTasks(testTask = "testDebugUnitTest", lintTask = "lintDebug")
        }
    }
}
