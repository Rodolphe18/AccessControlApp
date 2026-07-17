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

rootProject.name = "Oskeys"
include(":app")
include(":core:model")
include(":core:designsystem")
 