import casa.moller.tandem.buildlogic.configureAndroidCommon
import casa.moller.tandem.buildlogic.configureKtlint
import casa.moller.tandem.buildlogic.intVersion
import casa.moller.tandem.buildlogic.libs
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `tandem.android.application`: com.android.application with the shared SDK levels, Java 17
 * bytecode, lint rules, ktlint and unit-test setup. Flavors, build types and `ciTest`/`ciLint`
 * are declared in `app/build.gradle.kts`.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = libs.intVersion("targetSdk")
            }
            configureKtlint()
            // detekt + Compose rules (ci lane, Fas 1b); `ciLint` depends on `detekt`.
            pluginManager.apply("tandem.detekt")
            // `checkModuleGraph`; app/build.gradle.kts makes its `ciLint` depend on it.
            pluginManager.apply("tandem.module-graph")
        }
    }
}
