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

    // Shared data assets: the loader resolves a constant that names one (the ARRL
    // section table, spec §9), and the station roster receives the MASTER.DTA pool.
    // `api`: the roster and CallFacts expose MasterDta/DxccList in their signatures.
    api(project(":core-data"))

    testImplementation(kotlin("test-junit"))
}
