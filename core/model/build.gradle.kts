// Pure Kotlin domain models (plan 5.2): no Android types, no serialization annotations.
// Decision B2: everything here stays plain Kotlin so that a later Kotlin Multiplatform port can
// share it. Dates use kotlinx-datetime (LocalDate/LocalTime), instants kotlin.time.Instant.
plugins {
    id("tandem.jvm.library")
}

dependencies {
    // LocalDate (meal plan days) and LocalTime (meal type times) appear in the public API.
    api(libs.kotlinx.datetime)
}
