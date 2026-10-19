plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The Morse alphabet is shared with the expression language, where HST scores by it
    // (ADR-0016 §2). It lives in a dependency-free leaf so neither module owns the other.
    implementation(project(":core-morse"))

    testImplementation(kotlin("test-junit"))
}
