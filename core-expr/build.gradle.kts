plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `callWeight(call)` is HST's points rule: the Morse sending time of the worked call
    // (ADR-0016 §2/§5). The alphabet lives in a dependency-free leaf shared with `:core-audio`,
    // so the language does not depend on the audio engine and vice versa.
    implementation(project(":core-morse"))

    testImplementation(kotlin("test-junit"))
}
