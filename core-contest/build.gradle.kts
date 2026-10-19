plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The contest schema compiles its expressions at load time (spec §4/§5, ADR-0004).
    // `api`: ContestConfig exposes Expression/Value in its public surface, so consumers
    // (the app, the QSO engine) must see those types too.
    api(project(":core-expr"))

    testImplementation(kotlin("test-junit"))
}
