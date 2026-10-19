// Repositories: dl.google.com / maven.google.com are unreachable from this machine,
// so the Aliyun mirrors come first. See .scratch/progress.md §6b.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "morse-runner-android"

include(":app")
include(":core-expr")
include(":core-contest")
include(":core-qso")
include(":core-audio")
