import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Read backend config from local.properties; when empty the demo falls back to MockProvider.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.ailux.chatdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ailux.chatdemo"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "AILUX_BASE_URL",
            "\"${localProperties.getProperty("ailux.baseUrl", "")}\""
        )
        buildConfigField(
            "String",
            "AILUX_API_KEY",
            "\"${localProperties.getProperty("ailux.apiKey", "")}\""
        )
    }

    signingConfigs {
        create("release") {
            storeFile = file("ailux-release.jks")
            storePassword = "ailux123456"
            keyAlias = "ailux"
            keyPassword = "ailux123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Dependency mode: "source" | "maven-umbrella" | "maven-split"
val ailuxDepMode: String = providers.gradleProperty("AILUX_DEP_MODE").orElse("source").get()
val ailuxGroupId: String = providers.gradleProperty("AILUX_GROUP_ID").orElse("io.github.kynnchuan").get()
val ailuxVersion: String = providers.gradleProperty("AILUX_VERSION").orElse("0.1.0").get()

dependencies {
    // L4 App -> SDK modules (mode-switched)
    when (ailuxDepMode) {
        "maven-umbrella" -> {
            // Single umbrella artifact -- pulls in all sub-modules transitively.
            api("$ailuxGroupId:ailux-sdk:$ailuxVersion")
        }
        "maven-split" -> {
            // Fine-grained remote artifacts -- same split as source mode.
            api("$ailuxGroupId:ailux-api:$ailuxVersion")
            implementation("$ailuxGroupId:ailux-android:$ailuxVersion")
            implementation("$ailuxGroupId:ailux-provider-backend:$ailuxVersion")
            implementation("$ailuxGroupId:ailux-provider-mock:$ailuxVersion")
        }
        else -> {
            // "source" -- project dependencies for local development.
            api(project(":ailux-api"))
            implementation(project(":ailux-android"))
            implementation(project(":ailux-provider-backend"))
            implementation(project(":ailux-provider-mock"))
            implementation(project(":ailux-provider-local"))
            implementation(project(":ailux-runtime-litertlm"))
        }
    }

    // Kotlinx Serialization JSON (needed to construct ToolDefinition.arguments)
    implementation(libs.serialization.json)

    // AndroidX + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.icons.extended)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
