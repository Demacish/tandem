// Fas 5 (wear lane, 0.8.0 "På språng", plan §7 #33, B15): the Wear OS app. The shopping list from
// its own small store, synced DIRECTLY with Tandoor with the watch's own token (plan §3.1), checks
// that wait in a small outbox, the phone's cook timers mirrored over the Data Layer, and a tile.
// docs/BYGGE.md "Klockan", docs/WEAR.md.
//
// - Same applicationId and signing as the phone app (the Wearable Data Layer only connects apps
//   with the same package and key): debug `.dev` with app/debug.p12, release with the release key
//   from release.yml's environment variables (the same ones :app reads), dry runs `.dryrun`.
// - minSdk 34 (Wear OS 5, Android 14; Galaxy Watch 4 and later got it with One UI 6 Watch), the
//   same as the phone, so the shared modules need no override (docs/BYGGE.md "Klockan").
// - Versions follow the phone app: versionCode and versionName are read from app/build.gradle.kts,
//   so the integration step bumps one file.
// - No Hilt: a small hand-made graph (WearGraph), so core:network's Hilt modules stay unused here.
// - Only pure modules and core:network (checkModuleGraph rule 6): no Room, ML Kit, camera or
//   phone Material 3 on the watch.
plugins {
    id("tandem.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Screenshot tests of the watch screens (small round, large round, square).
    id("tandem.android.roborazzi")
}

val phoneBuildScript: String = providers.fileContents(rootProject.layout.projectDirectory.file("app/build.gradle.kts")).asText.get()
val phoneVersionCode: Int = requireNotNull(Regex("""versionCode = (\d+)""").find(phoneBuildScript)) {
    "versionCode not found in app/build.gradle.kts"
}.groupValues[1].toInt()
val phoneVersionName: String = requireNotNull(Regex("""versionName = "([^"]+)"""").find(phoneBuildScript)) {
    "versionName not found in app/build.gradle.kts"
}.groupValues[1]

// Release signing exactly as :app (environment variables set by release.yml; nothing in the repo).
val releaseSigningEnv = listOf(
    "TANDEM_KEYSTORE_PATH",
    "TANDEM_KEYSTORE_PASSWORD",
    "TANDEM_KEY_ALIAS",
    "TANDEM_KEY_PASSWORD",
).associateWith { name -> providers.environmentVariable(name).orNull.orEmpty() }
val releaseSigningEnabled = releaseSigningEnv.getValue("TANDEM_KEYSTORE_PATH").isNotEmpty()
val releaseDryRun = providers.gradleProperty("tandem.dryRun").orNull.toBoolean()

android {
    namespace = "casa.moller.tandem.wear"

    defaultConfig {
        // The phone app's id: the Data Layer requires the same package on both devices.
        applicationId = "casa.moller.tandem"
        versionCode = phoneVersionCode
        versionName = phoneVersionName
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME and DEBUG feed the User-Agent (WearGraph).
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "sv")
    }

    signingConfigs {
        // The phone's public debug key (app/debug.p12), so a debug watch app pairs with a debug phone app.
        getByName("debug") {
            storeFile = rootProject.file("app/debug.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("TANDEM_KEYSTORE_PATH"))
                storePassword = releaseSigningEnv.getValue("TANDEM_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("TANDEM_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("TANDEM_KEY_PASSWORD")
                storeType = "pkcs12"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        getByName("release") {
            if (releaseSigningEnabled) signingConfig = signingConfigs.getByName("release")
            if (releaseDryRun) {
                applicationIdSuffix = ".dryrun"
                versionNameSuffix = "-dryrun"
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.network)
    implementation(projects.core.watchlink)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle.compose)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.phone.interactions)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// CI entry points (pr.yml runs ciLint and ciTest in every module).
tasks.register("ciTest") {
    group = "verification"
    description = "Runs the Wear OS app's unit tests and screenshot tests (debug)."
    dependsOn("testDebugUnitTest")
}
tasks.register("ciLint") {
    group = "verification"
    description = "Runs ktlint, the module graph check and Android Lint for the Wear OS app (debug)."
    dependsOn("ktlintCheck", "checkModuleGraph", "lintDebug")
}
