// Macrobenchmark and baseline profile generator (ci lane, Fas 1b; docs/BYGGE.md "Prestanda",
// .github/workflows/benchmark.yml).
//
// Like :smoke, a self-instrumenting test APK that drives :app's R8-minified sideload build
// (build type "minified", package casa.moller.tandem.minified) on an emulator, only through
// what is on screen. Everything runs in demo mode: no server and no network.
//
// - StartupBenchmark: cold start (StartupMode.COLD, 10 iterations, median in the job summary).
// - RecipeListScrollBenchmark: flinging the recipe list (frame timing).
// - BaselineProfileGenerator: the startup and list journeys as a baseline profile. benchmark.yml
//   builds the app for it with -Ptandem.baselineProfile.generate=true (no obfuscation), so the
//   profile carries real class names that AGP maps onto every R8 build.
//
// Emulator numbers are a trend only; the real cold start comes from a phone (Mer -> Diagnostik).
plugins {
    id("tandem.android.test")
}

android {
    namespace = "casa.moller.tandem.benchmark"
    targetProjectPath = ":app"
    // The app runs in its own process, exactly as installed; Macrobenchmark kills and starts it.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // :app has the "distribution" flavor dimension; the household's sideload build is measured.
        missingDimensionStrategy("distribution", "sideload")
    }

    signingConfigs {
        // The same PUBLIC debug-only key as :app's debug and minified builds (docs/RELEASE.md).
        create("publicDebug") {
            storeFile = rootProject.file("app/debug.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        // Matches :app's "minified" build type (release R8 configuration, profileable).
        create("minified") {
            signingConfig = signingConfigs.getByName("publicDebug")
        }
    }
}

androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType == "minified" }
}

dependencies {
    implementation(libs.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
