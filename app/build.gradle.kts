plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.straylabs.hound"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.straylabs.hound"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // NanoHTTPD for HTTP Server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ZXing core for QR code generation (no UI, just bit-matrix)
    implementation("com.google.zxing:core:3.5.3")

    // ZXing Android Embedded for QR scanning (isTransitive=false avoids duplicate zxing core)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    // DocumentFile for SAF
    implementation("androidx.documentfile:documentfile:1.0.1")

    // OkHttp for HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coil for image loading in Compose
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Pager for swipe navigation
    implementation("androidx.compose.foundation:foundation")

    // Media3 / ExoPlayer for video + audio playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
