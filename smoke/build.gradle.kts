// Release smoke test (docs/BYGGE.md "Release-röktest", .github/workflows/smoke.yml).
//
// A black-box UiAutomator test that drives :app's R8-minified sideload build (build type
// "minified": the release R8 configuration, the public debug key, package
// casa.moller.tandem.minified) on an emulator, against an HTTPS fake Tandoor that runs inside
// this test APK and serves the recorded Tandoor 2.6.13 fixtures (api-schema/fixtures/2.6.13).
//
// The test never references app classes: it only sees what a user sees (texts on screen), so R8
// obfuscation cannot affect it, and it tests the code that ships. Unit and Robolectric tests run
// unminified code and cannot catch keep-rule mistakes; this can.
plugins {
    id("tandem.android.test")
}

android {
    namespace = "casa.moller.tandem.smoke"
    targetProjectPath = ":app"
    // The test APK instruments itself, not the app: the app runs in its own process exactly as
    // installed, and the test talks to it only through UiAutomator and the fake server.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // :app has the "distribution" flavor dimension; the household's sideload build is tested.
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
        // Matches :app's "minified" build type by name. The test APK itself is not minified.
        create("minified") {
            signingConfig = signingConfigs.getByName("publicDebug")
        }
    }

    sourceSets.getByName("main") {
        // The recorded Tandoor 2.6.13 responses, read by FakeTandoor (no third copy of them).
        assets.directories.add(rootProject.file("api-schema/fixtures/2.6.13").path)
    }
}

androidComponents {
    // Only the minified variant tests something that unit tests cannot (R8's output).
    beforeVariants { variant -> variant.enable = variant.buildType == "minified" }
}

dependencies {
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.mockwebserver3)
    implementation(libs.okhttp.tls)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
