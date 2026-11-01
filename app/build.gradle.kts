plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gpstracker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.gpstracker"
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }
    // Постоянный ключ из секретов GitHub, чтобы новые сборки ставились поверх старых
    val ks = file("tracker.keystore")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    if (ks.exists() && !ksPass.isNullOrEmpty()) {
        signingConfigs {
            getByName("debug") {
                storeFile = ks
                storePassword = ksPass
                keyAlias = "tracker"
                keyPassword = ksPass
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
