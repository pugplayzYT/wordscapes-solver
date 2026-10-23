plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pugplayz.wordscapesauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pugplayz.wordscapesauto"
        minSdk = 30
        targetSdk = 35
        versionCode = 17
        versionName = "2.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Bundled on-device Latin OCR: no API key, no model download during first scan.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
