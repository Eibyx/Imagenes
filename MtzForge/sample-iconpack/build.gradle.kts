plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Pack de iconos de PRUEBA (diseños geométricos originales) para probar la
// conversión APK -> MTZ en un dispositivo real.
android {
    namespace = "dev.mtzforge.sampleicons"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.mtzforge.sampleicons"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
