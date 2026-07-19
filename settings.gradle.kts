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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Type-safe project accessors: `projects.core.designsystem` instead of `project(":core:designsystem")`.
// A renamed module then fails at compile time rather than at task-graph resolution.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Syekso"
include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:datastore-proto")
include(":core:datastore")
include(":core:crypto")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:ble")
include(":feature:onboarding")
include(":feature:home")
include(":feature:sharing")
include(":feature:intercomcall")
include(":intercom")
 