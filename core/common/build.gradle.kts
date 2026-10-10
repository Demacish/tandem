// Pure Kotlin helpers shared by all layers: dispatchers, Result types, time, logging, build info.
plugins {
    id("tandem.jvm.library")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
