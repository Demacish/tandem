import casa.moller.tandem.buildlogic.addBundle
import casa.moller.tandem.buildlogic.bundle
import casa.moller.tandem.buildlogic.configureKtlint
import casa.moller.tandem.buildlogic.lib
import casa.moller.tandem.buildlogic.libs
import casa.moller.tandem.buildlogic.registerCiTasks
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `tandem.jvm.library`: pure Kotlin/JVM module (no Android types), Java 17 bytecode,
 * JUnit 5 (Jupiter) + Turbine + coroutines-test. Registers `ciTest` (test) and `ciLint` (ktlint).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            dependencies {
                add("testImplementation", platform(libs.lib("junit5-bom")))
                addBundle("testImplementation", libs.bundle("jvm-unit-test"))
                add("testRuntimeOnly", libs.lib("junit5-platform-launcher"))
            }
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }
            configureKtlint()
            // detekt + Compose rules (ci lane, Fas 1b); `ciLint` depends on `detekt`.
            pluginManager.apply("tandem.detekt")
            registerCiTasks(testTask = "test", lintTask = null)
        }
    }
}
