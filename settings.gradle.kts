// OpenLink: the control channel between OpenVitals and OpenWatch. Three
// Android libraries, taken into each app as a composite build
// (includeBuild) from a git submodule, until the first third-party client
// makes a Maven publication worth it.
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

rootProject.name = "openlink"
include(":contract")
include(":host")
include(":client")
