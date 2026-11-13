// Fas 5 (wear lane, 0.8.0 "På språng", plan §7 #33, §3.1): the phone's side of Tandem on a Wear OS
// watch. Implements core:data's WatchRepository on the Wearable Data Layer (Google Play services):
// pairing (the watch's own token, created with the active account's token and sent to a nearby
// watch), unpairing, renewal, and the cook timers mirrored to the watch (and "Stoppa" from it).
// Features never depend on this module (checkModuleGraph rule 1); :app binds it with Hilt.
// docs/BYGGE.md "Klockan".
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.datastore)
    implementation(projects.core.common)
    implementation(projects.core.watchlink)

    implementation(libs.play.services.wearable)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
}
