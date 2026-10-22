import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "casa.moller.tandem.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly: the real plugin versions come from the root build's `apply false` block.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
    // --- Phase 1 (grund lane): tandem.android.room configures the Room 3 Gradle plugin's DSL. ---
    compileOnly(libs.room3.gradlePlugin)
    // --- ci lane (Fas 1b): detekt is configured by the convention plugins (tandem.detekt). ---
    compileOnly(libs.detekt.gradlePlugin)

    // Unit tests of the build logic itself (ModuleGraphRules), run by pr.yml as
    // `./gradlew :build-logic:convention:test`.
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks {
    test {
        useJUnitPlatform()
    }
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "tandem.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "tandem.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "tandem.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "tandem.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "tandem.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        // --- release smoke test (:smoke): com.android.test against :app's minified build ---
        register("androidTest") {
            id = "tandem.android.test"
            implementationClass = "AndroidTestConventionPlugin"
        }
        register("jvmLibrary") {
            id = "tandem.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        // --- designsystem lane: screenshot tests ---
        register("androidRoborazzi") {
            id = "tandem.android.roborazzi"
            implementationClass = "AndroidRoborazziConventionPlugin"
        }
        // --- Phase 1 (grund lane) ---
        // Room 3 + KSP with exported schemas in <module>/schemas (core:database).
        register("androidRoom") {
            id = "tandem.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
        // Dependency rules of plan 5.2 as a `checkModuleGraph` task (part of every `ciLint`).
        // Applied automatically by tandem.android.application/library and tandem.jvm.library.
        register("moduleGraph") {
            id = "tandem.module-graph"
            implementationClass = "ModuleGraphConventionPlugin"
        }
        // --- ci lane (Fas 1b) ---
        // detekt (+ Compose rules) with config/detekt/detekt.yml and a per-module baseline; part of
        // every `ciLint`. Applied automatically by the application, library, JVM and test plugins.
        register("detekt") {
            id = "tandem.detekt"
            implementationClass = "DetektConventionPlugin"
        }
    }
}
