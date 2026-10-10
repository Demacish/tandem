package casa.moller.tandem.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

/**
 * ktlint (plan 5.6 "Statiskt") for every module: the rules come from the root `.editorconfig`
 * (`android_studio` style, 140 columns, Composables may be PascalCase). Runs as part of `ciLint`.
 * Generated sources (KSP, Hilt, BuildConfig) are never checked.
 */
internal fun Project.configureKtlint() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        version.set(libs.findVersion("ktlint").get().requiredVersion)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
        }
        filter {
            exclude { element -> element.file.path.contains("/build/") }
        }
    }
}
