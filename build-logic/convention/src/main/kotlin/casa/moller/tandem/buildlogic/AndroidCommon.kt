package casa.moller.tandem.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Bytecode level for all modules. CI runs on JDK 21, but the output targets Java 17. */
internal val TANDEM_JAVA_VERSION = JavaVersion.VERSION_17

/**
 * Runs [block] with the `android` extension once an Android application or library plugin is
 * applied, independent of plugin order in the module's `plugins {}` block.
 */
internal fun Project.withAndroid(block: (CommonExtension) -> Unit) {
    listOf("com.android.application", "com.android.library").forEach { id ->
        pluginManager.withPlugin(id) {
            block(extensions.getByName("android") as CommonExtension)
        }
    }
}

/** Settings shared by `:app` and every Android library module. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.compileSdk = libs.intVersion("compileSdk")
    android.defaultConfig.minSdk = libs.intVersion("minSdk")

    android.compileOptions.sourceCompatibility = TANDEM_JAVA_VERSION
    android.compileOptions.targetCompatibility = TANDEM_JAVA_VERSION

    // Robolectric needs merged Android resources in JVM unit tests.
    android.testOptions.unitTests.isIncludeAndroidResources = true

    android.lint.apply {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        // Plan 6.7: English (values/) and Swedish (values-sv/) must both be 100 % complete.
        error += listOf("MissingTranslation", "ExtraTranslation", "HardcodedText")
        htmlReport = true
        xmlReport = true
        // No lint baseline: every lint error must be fixed, not recorded.
    }

    tasks.withType<Test>().configureEach {
        maxHeapSize = "1536m"
        maxParallelForks = 1
        // Hilt's bytecode transform adds class files to the unit-test classpath of every Hilt
        // module, so Gradle 9 sees "test sources" in modules without tests and would fail.
        failOnNoDiscoveredTests.set(false)
        // Robolectric with SDK 36+ on JDK 21 reflects into FileDescriptor via SharedSecrets
        // (ApplicationSharedMemory); without these the sandbox fails with IllegalAccessException.
        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
        )
    }

    dependencies {
        add("testImplementation", libs.lib("junit4"))
        add("testImplementation", libs.lib("robolectric"))
        add("testImplementation", libs.lib("turbine"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        add("testImplementation", libs.lib("androidx-test-core"))
        add("testImplementation", libs.lib("androidx-test-ext-junit"))
    }
}
