plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Scoring and save-time validation are *contest-authored expressions*, already compiled by
    // the T3 loader; the engine evaluates them instead of growing a second rule engine
    // (ADR-0005 §2). `api`: ContestRules exposes ContestConfig/FieldType in its signature.
    api(project(":core-contest"))

    testImplementation(kotlin("test-junit"))
}
