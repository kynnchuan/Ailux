pluginManagement {
    repositories {
        // Aliyun mirrors for faster downloads in China
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
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
        // Aliyun mirrors for faster downloads in China
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // Local build/repo -- used to verify published artifacts before pushing to Central.
        maven { url = uri("${rootDir}/build/repo") }
    }
}

rootProject.name = "Ailux"
include(":samples:chat-demo")
include(":ailux")
include(":ailux-api")
include(":ailux-core")
include(":ailux-android")
include(":ailux-provider-backend")
include(":ailux-provider-mock")
include(":ailux-runtime")
include(":ailux-runtime-litertlm")
include(":ailux-provider-local")
