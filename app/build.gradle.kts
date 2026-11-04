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
        versionCode = 3
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
