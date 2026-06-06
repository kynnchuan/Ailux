plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * :ailux — single-dependency umbrella artifact.
 *
 * v0.1 ships as a single coordinate so consumers can integrate the entire SDK
 * with one line:
 *
 *     implementation("io.github.kynnchuan:ailux-sdk:0.1.0")
 *
 * This module ships no source of its own. It re-exports the five sibling
 * modules (core / api / android / provider-mock / provider-backend) via
 * `api(project(...))` so that all public types are visible to consumers.
 *
 * In v1.x we plan to split these submodules into independently consumable
 * artifacts. Code written against the umbrella will continue to compile —
 * only the dependency declaration may need to be tightened.
 */
android {
    namespace = "com.ailux"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Re-export every public module so a single `implementation("io.github.kynnchuan:ailux-sdk:x.y.z")`
    // gives consumers the full SDK surface (core types + Android runtime + both providers).
    api(project(":ailux-core"))
    api(project(":ailux-api"))
    api(project(":ailux-android"))
    api(project(":ailux-provider-mock"))
    api(project(":ailux-provider-backend"))
}

// Publication configuration (groupId / artifactId / POM / signing) is centralized
// in the root build script. The root script creates the `release` MavenPublication
// early, then attaches AGP's release component after the Android library module is
// evaluated. This module is mapped to artifactId `ailux-sdk`.
