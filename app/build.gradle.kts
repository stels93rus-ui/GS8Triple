plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.gs8triple"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.gs8triple"
        minSdk = 28
        targetSdk = 28
        versionCode = 7
        versionName = "0.6.0"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
