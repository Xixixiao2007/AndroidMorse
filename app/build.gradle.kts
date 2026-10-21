import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing material lives OUTSIDE the repository: the keystore and its password file are
// the only copy of the key that can ever sign an update for this application id. Reading it from a
// path outside the tree (overridable with MR_SIGNING_PROPERTIES) keeps the secret out of git while
// still letting a clean checkout build a signed release on this machine.
val signingProperties: Properties? = run {
    val path = System.getenv("MR_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.android-keys/morserunner-signing.properties"
    val f = File(path)
    if (!f.isFile) {
        println("WARNING: no release signing properties at $path — app-release.apk will be unsigned")
        null
    } else Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "com.bh4gzk.morserunner"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.bh4gzk.morserunner"
        minSdk = 24
        targetSdk = 35
        // 1.85.4 is the Morse Runner release this port tracks; the fourth number is the ANDROID
        // port's own revision, bumped on every published build. Deriving the name from versionCode
        // keeps the two from drifting (the copyright page shows BuildConfig.VERSION_NAME).
        versionCode = 5
        versionName = "1.85.4.3"
    }

    signingConfigs {
        if (signingProperties != null) {
            create("release") {
                storeFile = File(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                if (signingProperties.getProperty("storeType") != null) {
                    storeType = signingProperties.getProperty("storeType")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingProperties != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Same RTL text rendering as the debug build: the contest engine writes its strings in
            // place, and shrinking is off, so the release binary differs only by its signing and by
            // the absence of the debuggable flag.
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME feeds the copyright page, so the build must generate it
        // (AGP 8 leaves BuildConfig off unless it is asked for).
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":core-contest"))
    implementation(project(":core-qso"))
    implementation(project(":core-audio"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // HstSessionTest reads the expected weights straight from the module (T16).
    testImplementation(project(":core-morse"))

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
