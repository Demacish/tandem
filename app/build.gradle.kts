// Tandem app module: Application, MainActivity, Hilt graph root, navigation wiring.
plugins {
    id("tandem.android.application")
    id("tandem.android.compose")
    id("tandem.android.hilt")
    // Screenshot tests of the app-level screens (recovery) next to the feature modules' ones.
    id("tandem.android.roborazzi")
}

// Release signing comes ONLY from environment variables set by .github/workflows/release.yml
// (keystore decoded from a GitHub secret into $RUNNER_TEMP, PKCS12). Nothing is committed and
// nothing is read from files in the repo. Without TANDEM_KEYSTORE_PATH the release build is
// unsigned, which keeps local and pr.yml builds working. See docs/RELEASE.md.
val releaseSigningEnv = listOf(
    "TANDEM_KEYSTORE_PATH",
    "TANDEM_KEYSTORE_PASSWORD",
    "TANDEM_KEY_ALIAS",
    "TANDEM_KEY_PASSWORD",
).associateWith { name -> providers.environmentVariable(name).orNull.orEmpty() }
val releaseSigningEnabled = releaseSigningEnv.getValue("TANDEM_KEYSTORE_PATH").isNotEmpty()
if (releaseSigningEnabled) {
    val missing = releaseSigningEnv.filterValues { it.isEmpty() }.keys
    require(missing.isEmpty()) { "Release signing: TANDEM_KEYSTORE_PATH is set but $missing are empty" }
    require(file(releaseSigningEnv.getValue("TANDEM_KEYSTORE_PATH")).isFile) {
        "Release signing: TANDEM_KEYSTORE_PATH does not point to a file"
    }
}

// Release dry runs (release.yml without a tag) build the same R8 + signing path with a throwaway
// key. They get their own package name, so such an APK installs NEXT TO the real app and can never
// block its updates with a signature mismatch. Real tag releases never set this property.
val releaseDryRun = providers.gradleProperty("tandem.dryRun").orNull.toBoolean()

// --- ci lane (Fas 1b): baseline profile generation (benchmark.yml, docs/BYGGE.md "Prestanda").
// The generator needs real class names, so only that CI run builds "minified" without R8.
val baselineProfileGeneration = providers.gradleProperty("tandem.baselineProfile.generate").orNull.toBoolean()

// Optional pre-filled server address for LOCAL or private sideload builds only (plan 4.5: no
// server is hard-coded). Empty unless set with -Ptandem.defaultServerUrl=https://... or in
// ~/.gradle/gradle.properties; play builds never get one, and release.yml refuses to
// build a published release with it set.
val sideloadDefaultServerUrl = providers.gradleProperty("tandem.defaultServerUrl").orElse("").get().trim()
require(sideloadDefaultServerUrl.isEmpty() || Regex("""^https://[^\s"'\\]+$""").matches(sideloadDefaultServerUrl)) {
    "tandem.defaultServerUrl must be empty or an https:// URL without quotes or spaces"
}

android {
    namespace = "casa.moller.tandem"

    defaultConfig {
        applicationId = "casa.moller.tandem"
        versionCode = 5
        versionName = "0.3.0"
    }

    buildFeatures {
        // BuildConfig.VERSION_NAME feeds the User-Agent (AppBuildInfo).
        buildConfig = true
    }

    androidResources {
        // Generates android:localeConfig (per-app language, plan 6.7) from res/resources.properties
        // (unqualifiedResLocale=en) and the values-* folders.
        generateLocaleConfig = true
        // Only ship the languages Tandem is translated to; this also keeps the locale config free
        // of the dozens of languages that AndroidX libraries bring along.
        localeFilters += listOf("en", "sv")
    }

    signingConfigs {
        // PUBLIC, debug-only key committed as app/debug.p12 (standard android/androiddebugkey
        // credentials). Every CI run signs the .dev debug APK with the same key, so a newer CI
        // APK installs over an older one instead of failing with a signature mismatch (which
        // would force an uninstall and wipe the encrypted login). Never used for release builds;
        // the release key lives only in GitHub secrets (docs/RELEASE.md).
        getByName("debug") {
            storeFile = file("debug.p12")
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
                // minSdk 34: scheme v3 (key rotation ready). The signer skips v2 because every
                // supported device verifies v3; v1 (JAR) signing is useless.
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Release smoke test (docs/BYGGE.md, .github/workflows/smoke.yml): EXACTLY the release R8
        // configuration (minify, resource shrinking, full mode, the same keep-rule files), so the
        // :smoke UiAutomator test exercises the code that ships. Only three things differ: the
        // public debug key (so CI can build and install it), its own package name
        // casa.moller.tandem.minified (it can never replace or block the real app), and a
        // network security config that also trusts the smoke test's CA (src/minified/res).
        create("minified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            // Library modules only have debug and release.
            matchingFallbacks += listOf("release")
            // ci lane (Fas 1b): see baselineProfileGeneration above. Never set for the smoke test.
            if (baselineProfileGeneration) {
                isMinifyEnabled = false
                isShrinkResources = false
            }
        }
    }

    // Decision B10 (2026-10-10): GitHub Releases + Obtainium first, Google Play later; no F-Droid
    // flavor (IzzyOnDroid, if ever, takes the sideload APK from GitHub Releases).
    flavorDimensions += "distribution"
    productFlavors {
        // Obtainium / GitHub Releases (the household build).
        create("sideload") {
            dimension = "distribution"
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$sideloadDefaultServerUrl\"")
        }
        // Google Play: restricted permissions differ (plan 4.4/4.5).
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.domain)
    implementation(projects.core.network)
    implementation(projects.core.datastore)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.shell)
    // --- Phase 1 (grund lane): the app wires every module into the Hilt graph. The shell hosts
    // feature:recipes and feature:home; core:timer binds the real TimerController (Phase 1b). ---
    implementation(projects.core.database)
    implementation(projects.core.sync)
    implementation(projects.core.timer)
    implementation(projects.feature.recipes)
    implementation(projects.feature.home)
    implementation(projects.feature.cook)
    // --- grund lane (Fas 2): the Handla and Veckan tabs (hosted by the shell). ---
    implementation(projects.feature.shopping)
    implementation(projects.feature.mealplan)
    // --- sync lane (Phase 1): WorkManager runs core:sync's @HiltWorker workers through the
    // HiltWorkerFactory that TandemApplication provides (Configuration.Provider). ---
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.bundles.lifecycle.compose)
    implementation(libs.bundles.navigation3)
    implementation(libs.bundles.compose.adaptive)
    implementation(libs.kotlinx.coroutines.android)
    // --- ci lane (Fas 1b): installs the baseline profile (app/src/main/baselineProfiles/baseline-prof.txt) on
    // sideloaded installs, where no store compiles it ahead of time. ---
    implementation(libs.androidx.profileinstaller)

    // --- fix-bygg (0.1.0 review): ATF accessibility checks (assertAccessible) in the UI tests. ---
    testImplementation(projects.core.testing)
}

androidComponents {
    // The smoke test runs the sideload build only; a play minified variant would just cost
    // configuration time and are never built.
    beforeVariants(selector().withBuildType("minified")) { variant ->
        variant.enable = variant.flavorName == "sideload"
    }
}

// CI entry points (see .github/workflows/pr.yml): the sideload debug variant is the reference.
tasks.register("ciTest") {
    group = "verification"
    description = "Runs the app unit tests for the sideload debug variant, and the play variant's manifest test."
    dependsOn("testSideloadDebugUnitTest", "testPlayDebugUnitTest")
}

// --- fix-data (0.2.0 review): the play build must never ask for USE_EXACT_ALARM or
// USE_FULL_SCREEN_INTENT (plan 8.1, 4.4). The play variant runs only the merged-manifest test
// (TimerPermissionsManifestTest); every other app test runs on sideload. ---
tasks.withType<Test>().matching { it.name == "testPlayDebugUnitTest" }.configureEach {
    filter.includeTestsMatching("*TimerPermissionsManifestTest")
}
tasks.register("ciLint") {
    group = "verification"
    description = "Runs ktlint, the module graph check and Android Lint for the sideload debug variant."
    dependsOn("ktlintCheck", "checkModuleGraph", "lintSideloadDebug")
}
