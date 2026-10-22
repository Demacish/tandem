// Root build. Every plugin is declared here with `apply false` so that all of them share one
// classloader. This also lifts AGP's built-in Kotlin from its default KGP 2.2.10 / KSP 2.2.10
// to the versions in the catalog (Kotlin 2.4.21, KSP 2.3.12). Do not remove any line.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint) apply false
    // --- ci lane (Fas 1b): detekt, applied by the tandem.detekt convention plugin. ---
    alias(libs.plugins.detekt) apply false
}
