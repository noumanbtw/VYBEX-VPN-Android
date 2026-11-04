plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vybex.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vybex.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
