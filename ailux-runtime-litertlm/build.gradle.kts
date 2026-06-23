plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ailux.runtime.litertlm"
    compileSdk = 35

    defaultConfig {
        // LiteRT-LM 0.13.x has no official minSdk statement on the docs page;
        // we follow the project-wide baseline of 23 and will tighten if the
        // upstream POM declares a higher floor.
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // LiteRT-LM 0.13.x's AAR is compiled with Kotlin 2.3.x (metadata
        // 2.3.0).  Our project line is 2.2.21 (the highest 2.x that AGP
        // 8.10.1 supports without forcing an AGP bump), so we must tell the
        // compiler explicitly to tolerate the higher-versioned metadata.
        // We accept the risk: the LiteRT-LM API surface is Java-friendly
        // (suspend funs + plain data classes / enums), with no advanced 2.3-
        // only features we'd be silently degrading.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    // The upstream LiteRT-LM AAR ships its own AndroidManifest with the
    // <uses-native-library> declarations for OpenCL/VNDK; we still re-declare
    // them in our own manifest for clarity and to survive manifest-merger
    // edge cases when the host app overrides things.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
            )
        }
    }
}

dependencies {

    implementation(project(":ailux-core"))
    // Engine SPI lives in :ailux-runtime; we depend on it via `api` so
    // downstream Provider modules (e.g. :ailux-provider-local) that pull
    // ailux-runtime-litertlm transitively get the SPI types as well.
    api(project(":ailux-runtime"))
    implementation(libs.coroutines.core)

    // Upstream LiteRT-LM. Pinned to a concrete release for reproducibility;
    // the docs use `latest.release` but that breaks reproducible builds.
    api(libs.litertlm.android)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
