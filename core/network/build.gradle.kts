// Retrofit services per area, tolerant DTOs, interceptors (auth, User-Agent, HTTPS only, rate
// limit), Json config, ErrorClassifier, ServerProbe and TandoorAuthenticator (plan 5.1-5.5).
// Unit tests run on the plain JVM (no Robolectric) against an HTTPS MockWebServer and the
// fixtures recorded from Tandoor 2.6.13, read directly from api-schema/ (no copies).
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        // Keep rules the app's R8 needs for this module (Retrofit service interfaces).
        consumerProguardFiles("consumer-rules.pro")
    }
}

// --- network lane (Phase 1): tests read api-schema/ (fixtures, used-endpoints.txt, the OpenAPI
// schema) through the system property tandem.apiSchemaDir; declared as an input so that a new
// or changed fixture re-runs the tests. ---
val apiSchemaDir: File = rootProject.file("api-schema")
tasks.withType<Test>().configureEach {
    inputs.dir(apiSchemaDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("apiSchema")
    systemProperty("tandem.apiSchemaDir", apiSchemaDir.absolutePath)
}

dependencies {
    // TandemError / Outcome appear in the public API of this module.
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.bundles.network)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.mockwebserver3.junit4)
    testImplementation(libs.okhttp.tls)
}
