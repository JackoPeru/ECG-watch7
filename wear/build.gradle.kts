plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.galaxywatch7.health.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.galaxywatch7.health.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    val samsungSensorSdk = file("libs/samsung-health-sensor-api.aar")
    if (samsungSensorSdk.exists()) {
        implementation(files(samsungSensorSdk))
    }
}
