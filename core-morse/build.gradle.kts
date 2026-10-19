plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The Morse alphabet itself, with no consumers: `:core-audio` sends it and `:core-expr`
    // scores with it (ADR-0016 §2). Deliberately dependency-free so neither direction of the
    // audio/expression split has to own the table.
    testImplementation(kotlin("test-junit"))
}
