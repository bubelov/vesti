pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // The Kotlin/Wasm toolchain adds its own Node.js distribution repository
    // to the root project, which FAIL_ON_PROJECT_REPOS rejects. Prefer project
    // repositories so the toolchain can provision Node; all module dependencies
    // still resolve from the settings repositories below.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Vesti"

include(":app")
include(":shared")
include(":ui")
include(":webApp")
include(":desktopApp")
