import casa.moller.tandem.buildlogic.TANDEM_JAVA_VERSION
import casa.moller.tandem.buildlogic.configureKtlint
import casa.moller.tandem.buildlogic.intVersion
import casa.moller.tandem.buildlogic.libs
import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `tandem.android.test`: com.android.test (a test APK that runs against another module's APK,
 * here the release smoke test `:smoke`) with the shared SDK levels and Java 17 bytecode, plus
 * ktlint. Registers `ciLint` (ktlint only: the module has no unit tests and no shipped code).
 * Target project, build types and signing are declared in the module itself.
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            extensions.configure<TestExtension> {
                compileSdk = libs.intVersion("compileSdk")
                defaultConfig.minSdk = libs.intVersion("minSdk")
                defaultConfig.targetSdk = libs.intVersion("targetSdk")
                compileOptions.sourceCompatibility = TANDEM_JAVA_VERSION
                compileOptions.targetCompatibility = TANDEM_JAVA_VERSION
            }
            configureKtlint()
            tasks.register("ciLint") {
                group = "verification"
                description = "Runs ktlint for this module."
                dependsOn("ktlintCheck")
            }
        }
    }
}
