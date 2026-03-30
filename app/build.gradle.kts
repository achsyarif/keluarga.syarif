plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.keluarga.syarif.app"
    compileSdk = 35 // Kita kunci di Android 15 agar stabil

    defaultConfig {
        applicationId = "com.keluarga.syarif.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // --- PERBAIKAN: Menggunakan versi spesifik yang stabil untuk SDK 35 ---

    // Core & Appcompat (Versi Stabil)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // Activity (Penyebab error utama tadi, kita turunkan ke versi stabil)
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Constraint Layout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Volley (Untuk update checker)
    implementation("com.android.volley:volley:1.2.1")

    // Testing (Biarkan default)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}