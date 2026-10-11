// Pure Kotlin domain logic (ScaleIngredients, FormatAmount, TimerDetector, recipe display, ...).
// No Android types.
plugins {
    id("tandem.jvm.library")
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.core)

    // Fas 1 domain: the parity tests read the recorded Tandoor recipes and scaling-golden.json.
    testImplementation(libs.kotlinx.serialization.json)
}

// Fas 1 domain: committed Tandoor fixtures (api-schema/fixtures/), read by the parity tests
// (RecipeDisplayGoldenTest). Declared as an input so a changed fixture reruns the tests.
val fixturesDir: Directory = layout.projectDirectory.dir("../../api-schema/fixtures")

tasks.withType<Test>().configureEach {
    systemProperty("tandem.fixturesDir", fixturesDir.asFile.absolutePath)
    inputs.dir(fixturesDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fixtures")
}
