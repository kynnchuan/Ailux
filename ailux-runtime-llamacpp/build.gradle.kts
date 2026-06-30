plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ─────────────────────────────────────────────────────────────────────────────
// Native build gate.
//
// llama.cpp ships as raw C/C++ source we compile ourselves into a per-ABI
// `.so` (this is the first Ailux module that owns native compilation — see
// spec v0.3.1 §1.3 "model ≠ inference framework").  That requires the Android
// NDK + CMake to be installed locally, which is NOT available on plain JVM CI
// runners or sandboxes.
//
// To keep the Kotlin layer + unit tests buildable everywhere while still
// allowing a one-flag real native build on a developer machine, the
// externalNativeBuild block is gated behind a Gradle property:
//
//   ./gradlew :ailux-runtime-llamacpp:assemble -Pailux.llamacpp.nativeBuild=true
//
// Default (property absent / false): no externalNativeBuild is registered, the
// module compiles Kotlin only, and the JNI `external fun`s resolve to a `.so`
// the consumer is expected to supply (prebuilt and dropped into jniLibs, or
// produced by a prior native build). Unit tests that don't touch JNI run fine.
// ─────────────────────────────────────────────────────────────────────────────
val nativeBuildEnabled: Boolean =
    (project.findProperty("ailux.llamacpp.nativeBuild") as String?)?.toBoolean() ?: false

android {
    namespace = "com.ailux.runtime.llamacpp"
    compileSdk = 35

    defaultConfig {
        // llama.cpp needs a reasonably modern bionic; we keep the project-wide
        // baseline of 23. End-to-end on-device LLM realistically needs ≥ 8 (the
        // arm64 floor) anyway.
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // arm64-v8a only. Per spec §7.1: in 2026 effectively 100% of devices
            // that can run an on-device LLM (≥ 4 GB RAM) are arm64; armeabi-v7a
            // (32-bit) can't carry the weights. x86_64 emulators are out of
            // scope (would double the .aar size) — use an arm64 device.
            abiFilters += "arm64-v8a"
        }

        if (nativeBuildEnabled) {
            externalNativeBuild {
                cmake {
                    // Match the engine constructor defaults; the C++ side reads
                    // these via -D flags. Vulkan is opt-in (GGML_VULKAN) because
                    // not every NDK/driver combo has the headers.
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DGGML_VULKAN=OFF",
                    )
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    if (nativeBuildEnabled) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                // Pin to an NDK-bundled CMake that ships with AGP 8.10's
                // recommended toolchain. Adjust if your local SDK differs.
                version = "3.22.1"
            }
        }
        // NDK version is intentionally NOT pinned here so it follows whatever the
        // developer has installed; pin it in your local build if you need
        // reproducibility (e.g. ndkVersion = "27.0.12077973").
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

    implementation(project(":ailux-core"))
    // Engine SPI lives in :ailux-runtime; depend on it via `api` so downstream
    // Provider modules that pull this module transitively also get the SPI
    // types (mirrors :ailux-runtime-litertlm).
    api(project(":ailux-runtime"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
