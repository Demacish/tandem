// Fas S (0.4.0, decision B22): the fridge photo's AI clients (#45, EXPERIMENTAL). Implements
// core:data's FridgeVision with the user's OWN key (core:datastore AiKeyStore, read only here):
// Claude through Anthropic's official Java SDK, Gemini and OpenAI through their REST APIs. Every
// provider gets its own HTTP client without any Tandoor interceptor, header, token or cookie (this
// module never depends on core:network). No automatic retries: a call costs the user credits.
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.datastore)
    implementation(projects.core.common)

    implementation(libs.anthropic.java)
    // The SDK's Jackson Kotlin module needs kotlin-reflect; aligned with our Kotlin version.
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // ai-foto lane: Gemini and OpenAI on core:ai's own OkHttp client (AiHttpClient), never core:network's.
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(projects.core.testing)
    // ai-foto lane: every provider against an in-process fake server (never a real AI API).
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver3)
    // Fas S review (fix-data): an HTTPS fake for the Claude client's cancellation test.
    testImplementation(libs.okhttp.tls)
}
