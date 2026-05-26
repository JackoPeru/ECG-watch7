plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.galaxywatch7.health.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.galaxywatch7.health.mobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "0.2.2"
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
