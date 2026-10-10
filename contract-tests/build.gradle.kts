// Contract tests against a temporary Tandoor started inside GitHub Actions (contract.yml).
// Phase0ContractTest is skipped unless the environment variable TANDOOR_URL is set, so pr.yml
// stays green without a server (SanitizerTest always runs). Never point TANDOOR_URL at a
// production server: the tests revoke tokens and exhaust the login throttle on purpose.
// Raw OkHttp + kotlinx.serialization only; no dependency on :core:network.
plugins {
    id("tandem.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.serialization.json)
}

val tandoorUrl: Provider<String> = providers.environmentVariable("TANDOOR_URL").orElse("")
val recordDir: Provider<Directory> = layout.buildDirectory.dir("contract-recordings")

tasks.withType<Test>().configureEach {
    // A different server URL must never reuse a cached or up-to-date result.
    inputs.property("tandoorUrl", tandoorUrl)
    // Sanitized request/response recordings + observed-facts.json, one folder per image tag.
    systemProperty("tandem.contract.recordDir", recordDir.get().asFile.absolutePath)
    if (tandoorUrl.get().isNotEmpty()) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            showStandardStreams = true
        }
    }
}
