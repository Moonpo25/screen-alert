plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.screenalert"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screenalert"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // مكتبة النصوص الإنجليزية (16.0.1)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // مكتبة النصوص العربية (16.0.0)
    implementation("com.google.mlkit:text-recognition-arabic:16.0.0")
    
    implementation("com.google.code.gson:gson:2.10.1")
}
