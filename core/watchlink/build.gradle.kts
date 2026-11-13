// Fas 5 (wear lane, 0.8.0 "På språng", plan §7 #33, §3.1): what the phone and the Wear OS app say to
// each other over the Wearable Data Layer. Pure Kotlin (no Android or Play services types), so both
// sides (:core:watch on the phone, :wear on the watch) share one definition of the paths, the
// payloads and the watch token's lifetime, and the rules are unit tested on the plain JVM.
plugins {
    id("tandem.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
