import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Machine-local, never committed. Optional keys:
//   onvif.user / onvif.password = login pre-filled when adding a discovered camera
// Build with -PpublicBuild to leave them out, e.g. for an APK that will be shared.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists() && !project.hasProperty("publicBuild")) file.inputStream().use { load(it) }
}

fun quoted(key: String, fallback: String): String {
    val value = localProperties.getProperty(key, fallback)
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "dev.andreyneto.camerinha"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.andreyneto.camerinha"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"

        buildConfigField("String", "ONVIF_USER", quoted("onvif.user", "admin"))
        buildConfigField("String", "ONVIF_PASSWORD", quoted("onvif.password", ""))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideload build: sign with the debug key so `installRelease` just works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.1.0")

    // Low-latency RTSP: RTP straight into MediaCodec, no jitter buffer.
    implementation("com.github.alexeyvasilyev:rtsp-client-android:7.1.1")
}
