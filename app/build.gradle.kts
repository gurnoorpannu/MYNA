import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// LLM key lives in local.properties (git-ignored): GEMINI_API_KEY=...  Blank = mock mode.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(name: String, default: String = "") = "\"" + (localProps.getProperty(name) ?: default) + "\""

android {
    namespace = "com.example.myna_mimicyourinteractionsautomate"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myna_mimicyourinteractionsautomate"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", localProp("GEMINI_API_KEY"))
        buildConfigField("String", "GEMINI_MODEL", localProp("GEMINI_MODEL", "gemini-2.5-flash"))
        buildConfigField("String", "GEMINI_EMBED_MODEL", localProp("GEMINI_EMBED_MODEL", "gemini-embedding-001"))
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.text.recognition)   // on-device OCR for text-less elements
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json for JVM tests (Android's is a stub)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}