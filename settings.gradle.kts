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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Local build/repo -- used to verify published artifacts before pushing to Central.
        maven { url = uri("${rootDir}/build/repo") }
    }
}

rootProject.name = "Ailux"
include(":app")
include(":ailux")
include(":ailux-api")
include(":ailux-core")
include(":ailux-android")
include(":ailux-provider-backend")
include(":ailux-provider-mock")
